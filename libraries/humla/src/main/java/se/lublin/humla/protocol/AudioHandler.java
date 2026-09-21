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
import android.media.AudioDeviceInfo;
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
 * Bridges the protocol's audio messages to our input and output threads.
 * A useful intermediate for reducing code coupling.
 * Audio playback and recording is exclusively controlled by the protocol.
 * Changes to input/output instance vars after the audio threads have been initialized will recreate
 * them in most cases (they're immutable for the purpose of avoiding threading issues).
 * Calling shutdown() will cleanup both input and output threads. It is safe to restart after.
 * Created by andrew on 23/04/14.
 */
public class AudioHandler extends HumlaNetworkListener implements AudioInput.AudioInputListener {
    private static final String TAG = AudioHandler.class.getName();

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE/100;
    public static final int MAX_BUFFER_SIZE = 960;

    /**
     * Spec B6's android.media.audiofx effects are not wired through this class yet, so the source
     * policy is asked with both of them off. The moment they are, this constant is the thing that
     * has to become the real setting -- not a second copy of the condition.
     */
    private static final AndroidAudioEffects NO_ANDROID_EFFECTS = new AndroidAudioEffects(false, false);

    private final Context mContext;
    private final HumlaLogger mLogger;
    private final AudioManager mAudioManager;
    private final AudioInput mInput;
    private final AudioOutput mOutput;
    /**
     * Spec B1's capture chain, in front of the encoder rather than inside it. Touched only by the
     * capture thread in {@link #onAudioInputReceived}, and released in {@link #shutdown()}.
     */
    private final CapturePipeline mCapturePipeline;
    private AudioOutput.AudioOutputListener mOutputListener;
    private AudioEncodeListener mEncodeListener;

    private int mSession;
    private HumlaUDPMessageType mCodec;
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
    /** True if the user is muted on the server. */
    private boolean mMuted;
    private boolean mBluetoothOn;
    private boolean mHalfDuplex;
    private boolean mPreprocessorEnabled;
    private final String mNoiseSuppressionMethod;
    private String mEchoCancellationMethod;
    /** The last observed talking state. False if muted, or the input mode is not active. */
    private boolean mTalking;

    private final Object mEncoderLock;
    private byte mTargetId;

    public AudioHandler(Context context, HumlaLogger logger, int audioStream, int audioSource,
                        int sampleRate, int targetBitrate, int targetFramesPerPacket,
                        IInputMode inputMode, byte targetId, float amplitudeBoost,
                        boolean bluetoothEnabled, boolean halfDuplexEnabled,
                        boolean preprocessorEnabled, String echoCancellationMethod,
                        String noiseSuppressionMethod,
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
        mBluetoothOn = bluetoothEnabled;
        mHalfDuplex = halfDuplexEnabled;
        mPreprocessorEnabled = preprocessorEnabled;
        mNoiseSuppressionMethod = noiseSuppressionMethod;
        mEchoCancellationMethod = echoCancellationMethod;
        mEncodeListener = encodeListener;
        mOutputListener = outputListener;
        mTalking = false;
        mTargetId = targetId;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mEncoderLock = new Object();

        final EchoCancellationMode echo = EchoCancellationMode.fromPreferenceValue(echoCancellationMethod);
        // One decision for the recording source and the audio mode, because they have to agree:
        // AudioSourcePolicy also resolves the source inside PcmCaptureSource, so asking it here
        // rather than testing for "system" is what keeps the WebRTC canceller from capturing on
        // VOICE_COMMUNICATION while the manager still sits in MODE_NORMAL. Some AECs -- ours
        // included, it needs a shared clock with the playback path -- will not work otherwise.
        //
        // KNOWN DEFECT, and the reason echo cancellation is not the default: this mode changes
        // how Android routes *output*, and initialize() below still opens the playback track on
        // whatever stream the app chose -- STREAM_MUSIC unless handset mode is on. A media-stream
        // track does not follow the communication route, and a Galaxy S25 on "system" reports
        // hearing nobody at all. The fix belongs to the routing seam (setCommunicationDevice to
        // the built-in speaker when handset mode is off, track on the communication stream), not
        // here. Settings.DEFAULT_ECHO_CANCELLATION_METHOD and EchoCancellationDefaultRouteTest
        // hold the default at "none" until it lands.
        if (AudioSourcePolicy.needsCommunicationMode(NO_ANDROID_EFFECTS, echo)) {
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            // Keep playback audible: in MODE_IN_COMMUNICATION the route follows the
            // communication device, which defaults to the earpiece. Select the built-in
            // speaker unless the user asked for handset mode -- and never touch a route
            // something else already claimed (a Bluetooth headset chosen by ScoRouter),
            // which is what the null/earpiece test below is for.
            if (mAudioStream != AudioManager.STREAM_VOICE_CALL) {
                AudioDeviceInfo speaker = null;
                for (AudioDeviceInfo d : mAudioManager.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { speaker = d; break; }
                }
                AudioDeviceInfo current = mAudioManager.getCommunicationDevice();
                boolean unclaimed = current == null
                        || current.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
                if (speaker != null && unclaimed) {
                    mLogger.logInfo("routing playback to the loudspeaker for echo cancellation");
                    mAudioManager.setCommunicationDevice(speaker);
                }
            }
        }
        mAudioSource = AudioSourcePolicy.resolve(audioSource, NO_ANDROID_EFFECTS, echo);

        if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new AudioInitializationException("RECORD_AUDIO permission not granted");
        }
        mInput = new AudioInput(this, mAudioSource, mSampleRate, mEchoCancellationMethod);
        // The existing `preprocessor_enabled` switch keeps its meaning -- "suppress noise" -- and
        // changes what does the suppressing: RNNoise instead of speex inside the encoder. The echo
        // setting picks between the platform canceller, which AudioInput attaches to the
        // AudioRecord session exactly as before, and ours -- never both, they are two values of
        // one preference.
        //
        // Both ends of the WebRTC canceller are taken from one call and handed to the two threads
        // that need them: the chain to the capture thread, and the far-end tap to AudioOutput's
        // playback thread. Wiring only the near end measured -0.62 dB of residual echo against
        // -22.32 dB with the reference fed (task 2).
        CaptureWiring.Wiring wiring = CaptureWiring.wire(
                mInput.getSampleRate(), mInputMode, mAmplitudeBoost,
                mNoiseSuppressionMethod != null
                        ? NoiseSuppressionMode.fromPreferenceValue(mNoiseSuppressionMethod)
                        : (mPreprocessorEnabled ? NoiseSuppressionMode.RNNOISE : NoiseSuppressionMode.NONE),
                echo, mLogger);
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
        setServerMuted(self.isMuted() || self.isLocalMuted() || self.isSuppressed());
        startRecording();
        // Ensure that if a bluetooth SCO connection is active, we use the VOICE_CALL stream.
        // This is required by Android for compatibility with SCO.
        mOutput.startPlaying(mBluetoothOn ? AudioManager.STREAM_VOICE_CALL : mAudioStream);

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
     * Sets whether or not the server wants the client muted.
     * @param muted Whether the user is muted on the server.
     */
    private void setServerMuted(boolean muted) throws AudioException {
        mMuted = muted;
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
     * Replaces the encoder. Takes mEncoderLock itself, so that destroying the old encoder cannot
     * race encode() or shutdown(), which hold the same lock. messageCodecVersion() already held it
     * around its call; initialize() and recreateEncoder() did not. The lock is reentrant, so the
     * existing nesting in messageCodecVersion() is unaffected.
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

        // No PreprocessingEncoder and no ResamplingEncoder any more: mCapturePipeline does both,
        // and before the voice detector rather than after it (spec B1). Wrapping either one here
        // as well would denoise twice and resample twice.
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
        mBluetoothOn = false;

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

        if (codec != mCodec) {
            try {
                synchronized (mEncoderLock) {
                    setCodec(codec);
                }
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
        if (msg.hasSession() && msg.getSession() == mSession &&
                (msg.hasMute() || msg.hasSelfMute() || msg.hasSuppress())) {
            try {
                setServerMuted(msg.getMute() || msg.getSelfMute() || msg.getSuppress());
            } catch (AudioException e) {
                e.printStackTrace();
            }
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
        // Spec B1: resample, preprocess *every* frame, then detect, then boost. The detector now
        // judges the denoised frame instead of the raw one, which is the whole point -- and it
        // means the same detection_threshold slider position corresponds to a different level than
        // it did before. The result object and its samples belong to the pipeline and are valid
        // only until the next call; they are read here, never kept.
        CaptureFrame processed = mCapturePipeline.process(frame, frameSize);
        boolean talking = processed.getTransmit();
        talking &= !mMuted;

        if (mTalking ^ talking) {
            mEncodeListener.onTalkingStateChanged(talking);
            if (mHalfDuplex) {
                mAudioManager.setStreamMute(getAudioStream(), talking);
            }

            synchronized (mEncoderLock) {
                // Terminate encoding when talking stops.
                if (!talking && mEncoder != null) {
                    try {
                        mEncoder.terminate();
                    } catch (NativeAudioException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        if (talking) {
            // The pipeline has already applied the amplitude boost, after the detector rather than
            // before it, so the amplification slider no longer moves the voice-activation
            // threshold with it. Note the length: the frame the pipeline produced, not the array's
            // size -- they are equal today and the pipeline is what may change that.
            synchronized (mEncoderLock) {
                if (mEncoder != null) {
                    try {
                        mEncoder.encode(processed.getSamples(), processed.getLength());
                        mFrameCounter++;
                    } catch (NativeAudioException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        synchronized (mEncoderLock) {
            if (mEncoder != null && mEncoder.isReady()) {
                sendEncodedAudio();
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
        private boolean mBluetoothEnabled;
        private boolean mHalfDuplexEnabled;
        private boolean mPreprocessorEnabled;
        private String mEchoCancellationMethod;
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

        public Builder setBluetoothEnabled(boolean bluetoothEnabled) {
            mBluetoothEnabled = bluetoothEnabled;
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
                    mAmplitudeBoost, mBluetoothEnabled, mHalfDuplexEnabled,
                    mPreprocessorEnabled, mEchoCancellationMethod,
                    mNoiseSuppressionMethod, mEncodeListener, mTalkingListener);
            handler.initialize(self, maxBandwidth, codec);
            return handler;
        }
    }
}
