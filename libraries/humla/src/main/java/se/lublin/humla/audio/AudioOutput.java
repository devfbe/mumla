/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import se.lublin.humla.audio.capture.FarEndFrameChunker;
import se.lublin.humla.exception.AudioInitializationException;
import se.lublin.humla.exception.NativeAudioException;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.net.PacketBuffer;
import se.lublin.humla.protocol.AudioHandler;

/**
 * Created by andrew on 16/07/13.
 */
public class AudioOutput implements Runnable, AudioOutputSpeech.TalkStateListener {
    private static final String TAG = AudioOutput.class.getName();

    private Map<Integer, AudioOutputSpeech> mAudioOutputs = new HashMap<>();
    private AudioTrack mAudioTrack;
    private int mBufferSize;
    private Thread mThread;
    private final Object mInactiveLock = new Object(); // Lock that the audio thread waits on when there's no audio to play. Wake when we get a frame.
    private final Lock mPacketLock;
    private boolean mRunning = false;
    private boolean mWoken = false; // set by every notify() on mInactiveLock

    private Handler mMainHandler;
    private AudioOutputListener mListener;
    private final IAudioMixer<float[], short[]> mMixer;
    private ExecutorService mDecodeExecutorService;
    /**
     * The far-end reference for AEC3, or null when the WebRTC canceller is not in the capture
     * chain -- which includes the case where it was asked for and could not be built. Written once
     * in the constructor and read only by the playback thread in {@link #run()}.
     */
    private final FarEndFrameChunker mFarEnd;

    /**
     * @param farEnd where every mixed buffer is handed over a second time, on its way to the
     *               speaker. It belongs to this thread: {@link FarEndFrameChunker} is not
     *               thread-safe and one chunker serves one playback thread, while the sink behind
     *               it takes the one lock that also covers the capture thread.
     */
    public AudioOutput(AudioOutputListener listener, FarEndFrameChunker farEnd) {
        mListener = listener;
        mFarEnd = farEnd;
        mMainHandler = new Handler(Looper.getMainLooper());
        mDecodeExecutorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        mPacketLock = new ReentrantLock();
        mMixer = new BasicClippingShortMixer();
    }

    public Thread startPlaying(int audioStream) throws AudioInitializationException {
        if (mThread != null || mRunning)
            return null;

        int minBufferSize = AudioTrack.getMinBufferSize(AudioHandler.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mBufferSize = Math.min(minBufferSize, AudioHandler.FRAME_SIZE * 12);
        Log.v(TAG, "Using buffer size " + mBufferSize + ", system's min buffer size: " + minBufferSize);

        try {
            mAudioTrack = new AudioTrack(audioStream,
                    AudioHandler.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    mBufferSize,
                    AudioTrack.MODE_STREAM);
        } catch (IllegalArgumentException e) {
            throw new AudioInitializationException(e);
        }

        mThread = new Thread(this);
        mThread.start();
        return mThread;
    }

    public void stopPlaying() {
        if(!mRunning)
            return;

        mRunning = false;
        synchronized (mInactiveLock) {
            mWoken = true;
            mInactiveLock.notify(); // Wake inactive lock if active
        }
        try {
            mThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mThread = null;

        mPacketLock.lock();
        for(AudioOutputSpeech speech : mAudioOutputs.values()) {
            speech.destroy();
        }
        mPacketLock.unlock();

        mAudioOutputs.clear();
        mAudioTrack.release();
        mAudioTrack = null;
    }

    public boolean isPlaying() {
        return mRunning;
    }

    @Override
    public void run() {
        Log.v(TAG, "Started thread.");
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        mRunning = true;
        mAudioTrack.play();

        final short[] mix = new short[mBufferSize];

        while(mRunning) {
            if(fetchAudio(mix, 0, mBufferSize)) {
                // The same samples the speaker gets, handed to the canceller before the write
                // rather than after it: write() blocks until the track has room, and every
                // millisecond the reference spends waiting here is a millisecond it is later than
                // the capture frame that will carry its echo. The chunker copies what it takes, so
                // the APM's render-side processing -- which may modify a frame in place -- cannot
                // reach this buffer on its way to AudioTrack.
                if (mFarEnd != null) {
                    mFarEnd.push(mix, mBufferSize);
                }
                mAudioTrack.write(mix, 0, mBufferSize);
            } else {
                Log.v(TAG, "Pausing thread.");
                synchronized (mInactiveLock) {
                    mAudioTrack.flush();
                    mAudioTrack.pause();

                    try {
                        if (mFarEnd != null) {
                            // AEC3 estimates the delay between what the speaker plays and what the
                            // microphone hears, and it estimates it from a *continuous* reference.
                            // Letting the stream stop here is what makes the first fragment of a
                            // word leak through after a silence: the filter has to re-converge.
                            // Silence at the real-time rate keeps that estimate alive, and costs
                            // one wakeup per buffer (120 ms at 48 kHz) while nobody is speaking.
                            // mWoken separates a real frame arriving from the timeout; without it
                            // a timed wait cannot tell the two apart -- and it also closes a
                            // pre-existing lost-notify hole, where a notify() landing before this
                            // block was entered left the thread waiting forever.
                            java.util.Arrays.fill(mix, (short) 0);
                            final long tickMs = Math.max(1L, (mBufferSize * 1000L)
                                    / AudioHandler.SAMPLE_RATE);
                            mWoken = false;
                            while (mRunning && !mWoken) {
                                mInactiveLock.wait(tickMs);
                                if (!mWoken) {
                                    mFarEnd.push(mix, mBufferSize);
                                }
                            }
                        } else {
                            mInactiveLock.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    mWoken = false;
                    mAudioTrack.play();
                }
                Log.v(TAG, "Resuming thread.");
            }
        }

        mAudioTrack.flush();
        mAudioTrack.stop();
    }

    /**
     * Fetches audio data from registered audio output users and mixes them into the given buffer.
     * TODO: add priority speaker support.
     * @param buffer The buffer to mix output data into.
     * @param bufferOffset The offset of the
     * @param bufferSize The size of the buffer.
     * @return true if the buffer contains audio data.
     */
    private boolean fetchAudio(short[] buffer, int bufferOffset, int bufferSize) {
        Arrays.fill(buffer, bufferOffset, bufferOffset + bufferSize, (short) 0);
        final List<IAudioMixerSource<float[]>> sources = new ArrayList<>();
        try {
            mPacketLock.lock();
            // Parallelize decoding using a fixed thread pool equal to the number of cores
            List<Future<AudioOutputSpeech.Result>> futureResults =
                    mDecodeExecutorService.invokeAll(mAudioOutputs.values());
            for(Future<AudioOutputSpeech.Result> future : futureResults) {
                AudioOutputSpeech.Result result = future.get();
                if (result.isAlive()) {
                    sources.add(result);
                } else {
                    AudioOutputSpeech speech = result.getSpeechOutput();
                    Log.v(TAG, "Deleted audio user " + speech.getUser().getName());
                    mAudioOutputs.remove(speech.getSession());
                    speech.destroy();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return false;
        } finally {
            mPacketLock.unlock();
        }

        if (sources.size() == 0)
            return false;

        mMixer.mix(sources, buffer, bufferOffset, bufferSize);
        return true;
    }

    public void queueVoiceData(byte[] data, HumlaUDPMessageType messageType) {
        if(!mRunning)
            return;

        byte msgFlags = (byte) (data[0] & 0x1f);
        PacketBuffer pds = new PacketBuffer(data, data.length);
        pds.skip(1);
        int session = (int) pds.readLong();
        User user = mListener.getUser(session);
        if(user != null && !user.isLocalMuted()) {
            // TODO check for whispers here
            int seq = (int) pds.readLong();

            // Synchronize so we don't destroy an output while we add a buffer to it.
            mPacketLock.lock();
            AudioOutputSpeech aop = mAudioOutputs.get(session);
            if(aop != null && aop.getCodec() != messageType) {
                aop.destroy();
                aop = null;
            }
            if(aop == null) {
                try {
                    aop = new AudioOutputSpeech(user, messageType, mBufferSize, this);
                } catch (NativeAudioException e) {
                    Log.v(TAG, "Failed to create audio user " + user.getName());
                    e.printStackTrace();
                    return;
                }
                Log.v(TAG, "Created audio user " + user.getName());
                mAudioOutputs.put(session, aop);
            }
            mPacketLock.unlock();

            PacketBuffer dataBuffer = new PacketBuffer(pds.bufferBlock(pds.left()));
            aop.addFrameToBuffer(dataBuffer, msgFlags, seq);

            synchronized (mInactiveLock) {
                mWoken = true;
                mInactiveLock.notify();
            }
        }

    }

    @Override
    public void onTalkStateUpdated(final int session, final TalkState state) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                final User user = mListener.getUser(session);
                if(user != null && user.getTalkState() != state) {
                    user.setTalkState(state);
                    mListener.onUserTalkStateUpdated(user);
                }
            }
        });
    }

    public static interface AudioOutputListener {
        /**
         * Called when a user's talking state is changed.
         * @param user The user whose talking state has been modified.
         */
        public void onUserTalkStateUpdated(User user);

        /**
         * Used to set audio-related user data.
         * @return The user for the associated session.
         */
        public User getUser(int session);
    }
}
