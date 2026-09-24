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

package se.lublin.humla.protocol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.util.Log;

import se.lublin.humla.R;
import se.lublin.humla.audio.AudioInput;
import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.audio.capture.AndroidAudioEffects;
import se.lublin.humla.audio.capture.AudioSourcePolicy;
import se.lublin.humla.audio.capture.CaptureFrame;
import se.lublin.humla.audio.capture.CapturePipeline;
import se.lublin.humla.audio.capture.CaptureWiring;
import se.lublin.humla.audio.capture.EchoCancellationMode;
import se.lublin.humla.audio.capture.NoiseSuppressionMode;
import se.lublin.humla.audio.capture.SpeexPreprocessor;
import se.lublin.humla.audio.encoder.CELT11Encoder;
import se.lublin.humla.audio.encoder.CELT7Encoder;
import se.lublin.humla.audio.encoder.IEncoder;
import se.lublin.humla.audio.encoder.OpusEncoder;
import se.lublin.humla.audio.inputmode.IInputMode;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.exception.AudioInitializationException;
import se.lublin.humla.exception.NativeAudioException;
import se.lublin.humla.model.User;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.net.PacketBuffer;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.HumlaNetworkListener;

/**
 * Bridges the protocol's audio messages to the input and output threads. Audio playback and
 * recording are controlled exclusively by the protocol. Changing input/output settings after the
 * audio threads are initialized recreates them in most cases. {@link #shutdown()} cleans up both
 * threads; restarting afterwards is safe.
 */
public class AudioHandler extends HumlaNetworkListener implements AudioInput.AudioInputListener {
    private static final String TAG = AudioHandler.class.getName();

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE/100;
    public static final int MAX_BUFFER_SIZE = 960;

    /**
     * The android.media.audiofx effects are not wired through this class yet, so the source policy
     * is asked with both off. Replace this constant with the real setting once they are.
     */
    private static final AndroidAudioEffects NO_ANDROID_EFFECTS = new AndroidAudioEffects(false, false);

    private final Context mContext;
    private final HumlaLogger mLogger;
    private final AudioManager mAudioManager;
    private final AudioInput mInput;
    private final AudioOutput mOutput;
    /**
     * Capture chain in front of the encoder. Touched only by the capture thread in
     * {@link #onAudioInputReceived}, released in {@link #shutdown()}.
     */
    private final CapturePipeline mCapturePipeline;
    private AudioOutput.AudioOutputListener mOutputListener;
    private AudioEncodeListener mEncodeListener;

    private int mSession;
    /** Written under mEncoderLock, read without it by getCodec(). */
    private volatile HumlaUDPMessageType mCodec;
    private IEncoder mEncoder;
    private int mFrameCounter;

    private final int mAudioStream;
    private final int mAudioSource;
    private int mSampleRate;
    private int mBitrate;
    private int mFramesPerPacket;
    private final IInputMode mInputMode;
    private final float mAmplitudeBoost;

    private boolean mInitialized;
    /** The own mute flags. Replaced by initialize(), updated by the protocol thread, read by capture. */
    private volatile SelfMuteState mMuteState = new SelfMuteState(false, false, false);
    private boolean mHalfDuplex;
    private boolean mPreprocessorEnabled;
    private final String mNoiseSuppressionMethod;
    private final int mSpeexNoiseSuppressDb;
    /** The platform effects the user switched on, attached to the recorder's session. */
    private final AndroidAudioEffects mAndroidAudioEffects;
    private String mEchoCancellationMethod;
    /** The last observed talking state. False if muted, or the input mode is not active. */
    private boolean mTalking;

    private final Object mEncoderLock;
    /** Set from the service, read by the capture thread for each packet. */
    private volatile byte mTargetId;

    public AudioHandler(Context context, HumlaLogger logger, int audioStream, int audioSource,
                        int sampleRate, int targetBitrate, int targetFramesPerPacket,
                        IInputMode inputMode, byte targetId, float amplitudeBoost,
                        boolean halfDuplexEnabled,
                        boolean preprocessorEnabled, String echoCancellationMethod,
                        String noiseSuppressionMethod, int speexNoiseSuppressDb,
                        AndroidAudioEffects androidAudioEffects,
                        AudioEncodeListener encodeListener,
                        AudioOutput.AudioOutputListener outputListener) throws AudioInitializationException, NativeAudioException {
        mContext = context;
        mLogger = logger;
        mAudioStream = audioStream;
        mSampleRate = sampleRate;
        mBitrate = targetBitrate;
        mFramesPerPacket = targetFramesPerPacket;
        mInputMode = inputMode;
        mAmplitudeBoost = amplitudeBoost;
        mHalfDuplex = halfDuplexEnabled;
        mPreprocessorEnabled = preprocessorEnabled;
        mNoiseSuppressionMethod = noiseSuppressionMethod;
        mSpeexNoiseSuppressDb = speexNoiseSuppressDb;
        mAndroidAudioEffects = androidAudioEffects != null ? androidAudioEffects : NO_ANDROID_EFFECTS;
        mEchoCancellationMethod = echoCancellationMethod;
        mEncodeListener = encodeListener;
        mOutputListener = outputListener;
        mTalking = false;
        mTargetId = targetId;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mEncoderLock = new Object();

        final EchoCancellationMode echo = EchoCancellationMode.fromPreferenceValue(echoCancellationMethod);
        // Recording source and audio mode must agree: AudioSourcePolicy also resolves the source
        // inside PcmCaptureSource, so the WebRTC canceller never captures on VOICE_COMMUNICATION
        // while the manager is still in MODE_NORMAL. In a session AudioRouter already holds
        // MODE_IN_COMMUNICATION; this is a second request for the same mode.
        if (AudioSourcePolicy.needsCommunicationMode(mAndroidAudioEffects, echo)) {
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }
        mAudioSource = AudioSourcePolicy.resolve(audioSource, mAndroidAudioEffects, echo);

        if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new AudioInitializationException("RECORD_AUDIO permission not granted");
        }
        mInput = new AudioInput(this, mAudioSource, mSampleRate, mEchoCancellationMethod, mAndroidAudioEffects);
        // `preprocessor_enabled` means "suppress noise" (RNNoise). Echo cancellation is AEC3 or
        // none, per routed device. Both ends of the canceller come from one call: the chain goes
        // to the capture thread, the far-end tap to AudioOutput's playback thread, which must feed
        // the reference for AEC to work.
        CaptureWiring.Wiring wiring = CaptureWiring.wire(
                mInput.getSampleRate(), mInputMode, mAmplitudeBoost,
                mNoiseSuppressionMethod != null
                        ? NoiseSuppressionMode.fromPreferenceValue(mNoiseSuppressionMethod)
                        : (mPreprocessorEnabled ? NoiseSuppressionMode.RNNOISE : NoiseSuppressionMode.NONE),
                echo, mSpeexNoiseSuppressDb, mLogger);
        mCapturePipeline = wiring.getPipeline();
        mOutput = new AudioOutput(mOutputListener, wiring.getFarEnd());
    }

    /**
     * Starts the audio output and input threads.
     * Will create both the input and output modules if they haven't been created yet.
     */
    public synchronized void initialize(User self, int maxBandwidth, HumlaUDPMessageType codec) throws AudioException {
        if(mInitialized) return;
        mSession = self.getSession();

        setMaxBandwidth(maxBandwidth);
        setCodec(codec);
        mMuteState = new SelfMuteState(self.isMuted() || self.isLocalMuted(), self.isSelfMuted(),
                self.isSuppressed());
        startRecording();
        mOutput.startPlaying(mAudioStream);

        mInitialized = true;
    }

    /**
     * Starts a recording AudioInput thread.
     * @throws AudioException if the input thread failed to initialize, or if a thread was already
     *                        recording.
     */
    private void startRecording() throws AudioException {
        synchronized (mInput) {
            if (!mInput.isRecording()) {
                mInput.startRecording();
            } else {
                throw new AudioException("Attempted to start recording while recording!");
            }
        }
    }

    /**
     * Stops the recording AudioInput thread.
     * @throws AudioException if there was no thread recording.
     */
    private void stopRecording() throws AudioException {
        synchronized (mInput) {
            if (mInput.isRecording()) {
                mInput.stopRecording();
            } else {
                throw new AudioException("Attempted to stop recording while not recording!");
            }
        }
    }

    /**
     * Returns whether or not the handler has been initialized.
     * @return true if the handler is ready to play and record audio.
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    public boolean isPlaying() {
        synchronized (mOutput) {
            return mOutput.isPlaying();
        }
    }

    public HumlaUDPMessageType getCodec() {
        return mCodec;
    }

    public void recreateEncoder() throws NativeAudioException {
        setCodec(mCodec);
    }

    /**
     * Replaces the encoder under mEncoderLock, so that destroying the old encoder cannot race
     * encoding or shutdown(), which hold the same lock.
     */
    public void setCodec(HumlaUDPMessageType codec) throws NativeAudioException {
        synchronized (mEncoderLock) {
            setCodecLocked(codec);
        }
    }

    private void setCodecLocked(HumlaUDPMessageType codec) throws NativeAudioException {
        mCodec = codec;

        if (mEncoder != null) {
            mEncoder.destroy();
            mEncoder = null;
        }

        if (codec == null) {
            Log.w(TAG, "setCodec(null) Input disabled.");
            return;
        }

        IEncoder encoder;
        switch (codec) {
            case UDPVoiceCELTAlpha:
                encoder = new CELT7Encoder(SAMPLE_RATE, AudioHandler.FRAME_SIZE, 1,
                        mFramesPerPacket, mBitrate, MAX_BUFFER_SIZE);
                break;
            case UDPVoiceCELTBeta:
                encoder = new CELT11Encoder(SAMPLE_RATE, 1, mFramesPerPacket);
                break;
            case UDPVoiceOpus:
                encoder = new OpusEncoder(SAMPLE_RATE, 1, FRAME_SIZE, mFramesPerPacket, mBitrate,
                        MAX_BUFFER_SIZE);
                break;
            default:
                Log.w(TAG, "Unsupported codec, input disabled.");
                return;
        }

        // Resampling and preprocessing happen in mCapturePipeline, before the voice detector.
        mEncoder = encoder;
    }

    public int getAudioStream() {
        return mAudioStream;
    }

    public int getAudioSource() {
        return mAudioSource;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getBitrate() {
        return mBitrate;
    }

    /**
     * Sets the maximum bandwidth available for audio input as obtained from the server.
     * Adjusts the bitrate and frames per packet accordingly to meet the server's requirement.
     * @param maxBandwidth The server-reported maximum bandwidth, in bps.
     */
    private void setMaxBandwidth(int maxBandwidth) throws AudioException {
        if (maxBandwidth == -1) {
            return;
        }
        int bitrate = mBitrate;
        int framesPerPacket = mFramesPerPacket;
        // Logic as per desktop Mumble's AudioInput::adjustBandwidth for consistency.
        if (HumlaConnection.calculateAudioBandwidth(bitrate, framesPerPacket) > maxBandwidth) {
            if (framesPerPacket <= 4 && maxBandwidth <= 32000) {
                framesPerPacket = 4;
            } else if (framesPerPacket == 1 && maxBandwidth <= 64000) {
                framesPerPacket = 2;
            } else if (framesPerPacket == 2 && maxBandwidth <= 48000) {
                framesPerPacket = 4;
            }
            while (HumlaConnection.calculateAudioBandwidth(bitrate, framesPerPacket)
                    > maxBandwidth && bitrate > 8000) {
                bitrate -= 1000;
            }
        }
        bitrate = Math.max(8000, bitrate);

        if (bitrate != mBitrate ||
                framesPerPacket != mFramesPerPacket) {
            mBitrate = bitrate;
            mFramesPerPacket = framesPerPacket;

            mLogger.logInfo(mContext.getString(R.string.audio_max_bandwidth,
                    maxBandwidth/1000, maxBandwidth/1000, framesPerPacket * 10));
        }
    }

    public int getFramesPerPacket() {
        return mFramesPerPacket;
    }

    public float getAmplitudeBoost() {
        return mAmplitudeBoost;
    }

    /**
     * Returns whether or not the audio handler is operating in half duplex mode, muting outgoing
     * audio when incoming audio is received.
     * @return true if the handler is in half duplex mode.
     */
    public boolean isHalfDuplex() {
        return mHalfDuplex;
    }

    public int getCurrentBandwidth() {
        return HumlaConnection.calculateAudioBandwidth(mBitrate, mFramesPerPacket);
    }

    /**
     * Shuts down the audio handler, halting input and output.
     */
    public synchronized void shutdown() {
        synchronized (mInput) {
            mInput.shutdown();
        }
        // After the capture thread is joined, because every stage in it is single-threaded and
        // release() frees native state the loop reaches on every frame.
        mCapturePipeline.release();
        synchronized (mOutput) {
            mOutput.stopPlaying();
        }
        synchronized (mEncoderLock) {
            if (mEncoder != null) {
                mEncoder.destroy();
                mEncoder = null;
            }
        }
        mInitialized = false;

        mEncodeListener.onTalkingStateChanged(false);
    }

    @Override
    public void messageCodecVersion(Mumble.CodecVersion msg) {
        if (!mInitialized)
            return; // Only listen to change events in this handler.

        HumlaUDPMessageType codec;
        if (msg.hasOpus() && msg.getOpus()) {
            codec = HumlaUDPMessageType.UDPVoiceOpus;
        } else if (msg.hasBeta() && !msg.getPreferAlpha()) {
            codec = HumlaUDPMessageType.UDPVoiceCELTBeta;
        } else {
            codec = HumlaUDPMessageType.UDPVoiceCELTAlpha;
        }

        synchronized (mEncoderLock) {
            if (codec == mCodec) return;
            try {
                setCodecLocked(codec);
            } catch (NativeAudioException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void messageServerSync(Mumble.ServerSync msg) {
        try {
            setMaxBandwidth(msg.hasMaxBandwidth() ? msg.getMaxBandwidth() : -1);
        } catch (AudioException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void messageUserState(Mumble.UserState msg) {
        if (!mInitialized)
            return; // We shouldn't initialize on UserState- wait for ServerSync.

        // Stop audio input if the user is muted, and resume if the user has set talking enabled.
        if (msg.hasSession() && msg.getSession() == mSession) {
            mMuteState.update(msg);
        }
    }

    @Override
    public void messageVoiceData(byte[] data, HumlaUDPMessageType messageType) {
        synchronized (mOutput) {
            mOutput.queueVoiceData(data, messageType);
        }
    }

    @Override
    public void onAudioInputReceived(short[] frame, int frameSize) {
        // Resample, preprocess every frame, then detect, then boost. The result and its samples
        // belong to the pipeline and are valid only until the next call.
        CaptureFrame processed = mCapturePipeline.process(frame, frameSize);
        boolean talking = processed.getTransmit();
        talking &= !mMuteState.isMuted();

        if (mTalking ^ talking) {
            mEncodeListener.onTalkingStateChanged(talking);
            if (mHalfDuplex) {
                mAudioManager.setStreamMute(getAudioStream(), talking);
            }
        }

        synchronized (mEncoderLock) {
            if (mEncoder != null) {
                try {
                    if (talking) {
                        // Already boosted by the pipeline; length is the produced frame's, not
                        // the array's.
                        mEncoder.encode(processed.getSamples(), processed.getLength());
                        mFrameCounter++;
                    } else if (mTalking) {
                        mEncoder.terminate();
                    }
                } catch (NativeAudioException e) {
                    e.printStackTrace();
                }
                if (mEncoder.isReady()) {
                    sendEncodedAudio();
                }
            }
        }

        mTalking = talking;
        if (!talking) {
            mInputMode.waitForInput();
        }
    }

    public void setVoiceTargetId(byte id) {
        mTargetId = id;
    }

    public void clearVoiceTarget() {
        // A target ID of 0 indicates normal talking.
        mTargetId = 0;
    }

    /**
     * Fetches the buffered audio from the current encoder and sends it to the server.
     */
    private void sendEncodedAudio() {
        int frames = mEncoder.getBufferedFrames();

        int flags = 0;
        flags |= mCodec.ordinal() << 5;
        flags |= mTargetId & 0x1F;

        final byte[] packetBuffer = new byte[1024];
        packetBuffer[0] = (byte) (flags & 0xFF);

        PacketBuffer ds = new PacketBuffer(packetBuffer, 1024);
        ds.skip(1);
        ds.writeLong(mFrameCounter - frames);
        mEncoder.getEncodedData(ds);
        int length = ds.size();
        ds.rewind();

        byte[] packet = ds.dataBlock(length);
        mEncodeListener.onAudioEncoded(packet, length);
    }

    public interface AudioEncodeListener {
        void onAudioEncoded(byte[] data, int length);
        void onTalkingStateChanged(boolean talking);
    }

    /**
     * A builder to configure and instantiate the audio protocol handler.
     */
    public static class Builder {
        private Context mContext;
        private HumlaLogger mLogger;
        private int mAudioStream;
        private int mAudioSource;
        private int mTargetBitrate;
        private int mTargetFramesPerPacket;
        private int mInputSampleRate;
        private float mAmplitudeBoost;
        private boolean mHalfDuplexEnabled;
        private boolean mPreprocessorEnabled;
        private String mEchoCancellationMethod;
        private int mSpeexNoiseSuppressDb = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB;
        private boolean mAndroidNoiseSuppressor;
        private boolean mAndroidAutomaticGainControl;
        private IInputMode mInputMode;
        private AudioEncodeListener mEncodeListener;
        private AudioOutput.AudioOutputListener mTalkingListener;

        public Builder setContext(Context context) {
            mContext = context;
            return this;
        }

        public Builder setLogger(HumlaLogger logger) {
            mLogger = logger;
            return this;
        }

        public Builder setAudioStream(int audioStream) {
            mAudioStream = audioStream;
            return this;
        }

        public Builder setAudioSource(int audioSource) {
            mAudioSource = audioSource;
            return this;
        }

        public Builder setTargetBitrate(int targetBitrate) {
            mTargetBitrate = targetBitrate;
            return this;
        }

        public Builder setTargetFramesPerPacket(int targetFramesPerPacket) {
            mTargetFramesPerPacket = targetFramesPerPacket;
            return this;
        }

        public Builder setInputSampleRate(int inputSampleRate) {
            mInputSampleRate = inputSampleRate;
            return this;
        }

        public Builder setAmplitudeBoost(float amplitudeBoost) {
            mAmplitudeBoost = amplitudeBoost;
            return this;
        }

        public Builder setHalfDuplexEnabled(boolean halfDuplexEnabled) {
            mHalfDuplexEnabled = halfDuplexEnabled;
            return this;
        }

        private String mNoiseSuppressionMethod;

        public Builder setNoiseSuppressionMethod(String method) {
            mNoiseSuppressionMethod = method;
            return this;
        }

        public Builder setPreprocessorEnabled(boolean preprocessorEnabled) {
            mPreprocessorEnabled = preprocessorEnabled;
            return this;
        }

        public Builder setEchoCancellationMethod(String echoCancellationMethod) {
            mEchoCancellationMethod = echoCancellationMethod;
            return this;
        }

        public Builder setSpeexNoiseSuppressDb(int speexNoiseSuppressDb) {
            mSpeexNoiseSuppressDb = speexNoiseSuppressDb;
            return this;
        }

        public Builder setAndroidNoiseSuppressor(boolean enabled) {
            mAndroidNoiseSuppressor = enabled;
            return this;
        }

        public Builder setAndroidAutomaticGainControl(boolean enabled) {
            mAndroidAutomaticGainControl = enabled;
            return this;
        }

        public Builder setEncodeListener(AudioEncodeListener encodeListener) {
            mEncodeListener = encodeListener;
            return this;
        }

        public Builder setTalkingListener(AudioOutput.AudioOutputListener talkingListener) {
            mTalkingListener = talkingListener; // TODO: remove user dependency from AudioOutput
            return this;
        }

        public Builder setInputMode(IInputMode inputMode) {
            mInputMode = inputMode;
            return this;
        }

        /**
         * Creates a new AudioHandler for the given session and begins managing input/output.
         * @return An initialized audio handler.
         */
        public AudioHandler initialize(User self, int maxBandwidth, HumlaUDPMessageType codec, byte targetId) throws AudioException {
            AudioHandler handler = new AudioHandler(mContext, mLogger, mAudioStream, mAudioSource,
                    mInputSampleRate, mTargetBitrate, mTargetFramesPerPacket, mInputMode, targetId,
                    mAmplitudeBoost, mHalfDuplexEnabled,
                    mPreprocessorEnabled, mEchoCancellationMethod,
                    mNoiseSuppressionMethod, mSpeexNoiseSuppressDb,
                    new AndroidAudioEffects(mAndroidNoiseSuppressor, mAndroidAutomaticGainControl),
                    mEncodeListener, mTalkingListener);
            handler.initialize(self, maxBandwidth, codec);
            return handler;
        }
    }
}
