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

package se.lublin.mumla

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.preference.PreferenceManager
import se.lublin.humla.Constants
import se.lublin.humla.audio.capture.AdaptiveVadTracker
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.SpeexPreprocessor
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode

/**
 * Settings class for universal access to the app's preferences.
 *
 * Streams B (audio) and P (platform) add keys and accessors here; keep the Java-visible
 * API (static constants, `getInstance`, method names) stable because Java callers remain.
 */
class Settings private constructor(context: Context) {

    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun getInputMethod(): String {
        val method = preferences.getString(PREF_INPUT_METHOD, ARRAY_INPUT_METHOD_VOICE)
        // Set default method for users who used to use handset mode before removal.
        return if (method != null && method in ARRAY_INPUT_METHODS) method else ARRAY_INPUT_METHOD_VOICE
    }

    /**
     * Converts the preference input method value to the one used to connect to a server via Humla.
     * @return An input method value used to instantiate a Humla service.
     */
    fun getHumlaInputMethod(): Int = when (val inputMethod = getInputMethod()) {
        ARRAY_INPUT_METHOD_VOICE -> Constants.TRANSMIT_VOICE_ACTIVITY
        ARRAY_INPUT_METHOD_PTT -> Constants.TRANSMIT_PUSH_TO_TALK
        ARRAY_INPUT_METHOD_CONTINUOUS -> Constants.TRANSMIT_CONTINUOUS
        else -> throw RuntimeException("Could not convert input method '$inputMethod' to a Humla input method id!")
    }

    fun setInputMethod(inputMethod: String) {
        if (inputMethod in ARRAY_INPUT_METHODS) {
            preferences.edit().putString(PREF_INPUT_METHOD, inputMethod).apply()
        } else {
            throw RuntimeException("Invalid input method $inputMethod")
        }
    }

    fun getInputSampleRate(): Int = preferences.getString(PREF_INPUT_RATE, DEFAULT_RATE)!!.toInt()

    fun getInputQuality(): Int = preferences.getInt(PREF_INPUT_QUALITY, DEFAULT_INPUT_QUALITY)

    fun getAmplitudeBoostMultiplier(): Float =
        preferences.getInt(PREF_AMPLITUDE_BOOST, DEFAULT_AMPLITUDE_BOOST).toFloat() / 100

    fun getDetectionThreshold(): Float =
        preferences.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD).toFloat() / 100

    fun getPushToTalkKey(): Int = preferences.getInt(PREF_PUSH_KEY, DEFAULT_PUSH_KEY)

    fun getHotCorner(): String = preferences.getString(PREF_HOT_CORNER_KEY, DEFAULT_HOT_CORNER)!!

    /** @return true if a hot corner should be shown. */
    fun isHotCornerEnabled(): Boolean = ARRAY_HOT_CORNER_NONE != getHotCorner()

    /** @return A [Gravity] value, or 0 if the hot corner is disabled. */
    fun getHotCornerGravity(): Int = when (getHotCorner()) {
        ARRAY_HOT_CORNER_BOTTOM_LEFT -> Gravity.LEFT or Gravity.BOTTOM
        ARRAY_HOT_CORNER_BOTTOM_RIGHT -> Gravity.RIGHT or Gravity.BOTTOM
        ARRAY_HOT_CORNER_TOP_LEFT -> Gravity.LEFT or Gravity.TOP
        ARRAY_HOT_CORNER_TOP_RIGHT -> Gravity.RIGHT or Gravity.TOP
        else -> 0
    }

    /** @return the height of the PTT button */
    fun getPTTButtonHeight(): Int = preferences.getInt(PREF_PTT_BUTTON_HEIGHT, DEFAULT_PTT_BUTTON_HEIGHT)

    /**
     * Returns a database identifier for the default certificate, or a negative number if there is
     * no default certificate set.
     */
    fun getDefaultCertificate(): Long = preferences.getLong(PREF_CERT_ID, -1)

    fun getDefaultUsername(): String = preferences.getString(PREF_DEFAULT_USERNAME, DEFAULT_DEFAULT_USERNAME)!!

    fun isPushToTalkToggle(): Boolean = preferences.getBoolean(PREF_PTT_TOGGLE, DEFAULT_PTT_TOGGLE)

    fun isPushToTalkButtonShown(): Boolean = !preferences.getBoolean(PREF_PUSH_BUTTON_HIDE_KEY, DEFAULT_PUSH_BUTTON_HIDE)

    fun isChatNotifyEnabled(): Boolean = preferences.getBoolean(PREF_CHAT_NOTIFY, DEFAULT_CHAT_NOTIFY)

    fun isTextToSpeechEnabled(): Boolean = preferences.getBoolean(PREF_USE_TTS, DEFAULT_USE_TTS)

    fun isShortTextToSpeechMessagesEnabled(): Boolean =
        preferences.getBoolean(PREF_SHORT_TTS_MESSAGES, DEFAULT_SHORT_TTS_MESSAGES)

    fun isAutoReconnectEnabled(): Boolean = preferences.getBoolean(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)

    fun isTcpForced(): Boolean = preferences.getBoolean(PREF_FORCE_TCP, DEFAULT_FORCE_TCP)

    fun isOpusDisabled(): Boolean = preferences.getBoolean(PREF_DISABLE_OPUS, DEFAULT_DISABLE_OPUS)

    fun isTorEnabled(): Boolean = preferences.getBoolean(PREF_USE_TOR, DEFAULT_USE_TOR)

    fun disableTor() {
        preferences.edit().putBoolean(PREF_USE_TOR, false).apply()
    }

    fun isMuted(): Boolean = preferences.getBoolean(PREF_MUTED, DEFAULT_MUTED)

    fun isDeafened(): Boolean = preferences.getBoolean(PREF_DEAFENED, DEFAULT_DEAFENED)

    fun isFirstRun(): Boolean = preferences.getBoolean(PREF_FIRST_RUN, DEFAULT_FIRST_RUN)

    fun shouldLoadExternalImages(): Boolean = preferences.getBoolean(PREF_LOAD_IMAGES, DEFAULT_LOAD_IMAGES)

    fun setMutedAndDeafened(muted: Boolean, deafened: Boolean) {
        preferences.edit()
            .putBoolean(PREF_MUTED, muted || deafened)
            .putBoolean(PREF_DEAFENED, deafened)
            .apply()
    }

    fun setFirstRun(run: Boolean) {
        preferences.edit().putBoolean(PREF_FIRST_RUN, run).apply()
    }

    fun getFramesPerPacket(): Int = preferences.getString(PREF_FRAMES_PER_PACKET, DEFAULT_FRAMES_PER_PACKET)!!.toInt()

    fun isHalfDuplex(): Boolean = preferences.getBoolean(PREF_HALF_DUPLEX, DEFAULT_HALF_DUPLEX)

    fun isHandsetMode(): Boolean = preferences.getBoolean(PREF_HANDSET_MODE, DEFAULT_HANDSET_MODE)

    fun isPttSoundEnabled(): Boolean = preferences.getBoolean(PREF_PTT_SOUND, DEFAULT_PTT_SOUND)

    fun isPreprocessorEnabled(): Boolean = preferences.getBoolean(PREF_PREPROCESSOR_ENABLED, DEFAULT_PREPROCESSOR_ENABLED)

    fun getNoiseSuppressionMethod(): String =
        preferences.getString(PREF_NOISE_SUPPRESSION_METHOD,
            if (isPreprocessorEnabled()) "rnnoise" else "none")!!

    /**
     * Written by the channel-list menu and by the audio settings screen, so the chain can be
     * switched without a restart.
     *
     * **One key, deliberately.** The first version of this also wrote [PREF_PREPROCESSOR_ENABLED]
     * to keep the legacy flag in step, and every key written is a `configureExtras` of its own:
     * one tap rebuilt the whole audio chain twice, measured 93 ms apart, with the microphone dead
     * in between. The legacy flag is only ever *read* now, by [getNoiseSuppressionMode], and only
     * while the new key has never been written.
     */
    fun setNoiseSuppressionMethod(method: String) {
        preferences.edit().putString(PREF_NOISE_SUPPRESSION_METHOD, method).apply()
    }

    /**
     * Spec B2/B3. The stored value wins; an installation that has never seen this key keeps what it
     * has been running, which is what the legacy `preprocessor_enabled` checkbox decided.
     */
    fun getNoiseSuppressionMode(): NoiseSuppressionMode =
        NoiseSuppressionMode.fromPreferenceValue(getNoiseSuppressionMethod())

    /** Spec B9. Anything outside [SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB] is the default. */
    fun getSpeexNoiseSuppressDb(): Int {
        val stored = preferences.getString(PREF_SPEEX_NOISE_SUPPRESS_DB, null)?.toIntOrNull()
        return if (stored != null && stored in SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB) stored
        else DEFAULT_SPEEX_NOISE_SUPPRESS_DB
    }

    fun getEchoCancellationMode(): EchoCancellationMode =
        EchoCancellationMode.fromPreferenceValue(getEchoCancellationMethod())

    fun getVadMode(): VadMode = VadMode.fromPreferenceValue(preferences.getString(PREF_VAD_MODE, DEFAULT_VAD_MODE))

    /**
     * The whole voice-gate configuration as one value, so that a settings change reaches the
     * running detector in one call instead of one call per slider.
     *
     * Every read is clamped here rather than at the detector: [VadConfig]'s constructor throws on a
     * value out of range, and a preference file is user-writable in a debug build and survives a
     * downgrade. A crash on startup because a slider holds 140 is not a better answer than 100.
     */
    fun getVadConfig(): VadConfig {
        val holdMs = preferences.getInt(PREF_VAD_HOLD_MS, DEFAULT_VAD_HOLD_MS)
            .coerceIn(0, MAX_VAD_HOLD_MS).toLong()
        // A ListPreference, so the value on disk is a string even though it counts frames.
        val onsetFrames = (preferences.getString(PREF_VAD_ONSET_FRAMES, null)?.toIntOrNull()
            ?: DEFAULT_VAD_ONSET_FRAMES).coerceIn(1, MAX_VAD_ONSET_FRAMES)
        return when (getVadMode()) {
            VadMode.AMPLITUDE -> VadConfig.amplitude(getDetectionThreshold(), holdMs, onsetFrames)
            VadMode.PROBABILITY -> {
                val start = preferences.getInt(PREF_VAD_START, DEFAULT_VAD_START).coerceIn(0, 100) / 100f
                val stop = (preferences.getInt(PREF_VAD_STOP, DEFAULT_VAD_STOP).coerceIn(0, 100) / 100f)
                    .coerceAtMost(start)
                VadConfig(VadMode.PROBABILITY, start, stop, holdMs, onsetFrames = onsetFrames)
            }
            VadMode.ADAPTIVE -> VadConfig.adaptive(
                snrFraction = preferences.getInt(PREF_VAD_SENSITIVITY, DEFAULT_VAD_SENSITIVITY)
                    .coerceIn(0, 100) / 100f,
                holdTimeMs = holdMs,
                onsetFrames = onsetFrames,
                adaptiveFloor = preferences.getBoolean(PREF_VAD_ADAPTIVE_FLOOR, DEFAULT_VAD_ADAPTIVE_FLOOR),
                manualFloorDbfs = (-preferences.getInt(PREF_VAD_FLOOR_DB, DEFAULT_VAD_FLOOR_DB).toFloat())
                    .coerceIn(AdaptiveVadTracker.MIN_FLOOR_DBFS, AdaptiveVadTracker.MAX_FLOOR_DBFS),
            )
        }
    }

    /** Spec B6: the two `android.media.audiofx` effects attached to the recorder's session. */
    fun getAndroidAudioEffects(): AndroidAudioEffects = AndroidAudioEffects(
        noiseSuppressor = preferences.getBoolean(PREF_ANDROID_NOISE_SUPPRESSOR, DEFAULT_ANDROID_NOISE_SUPPRESSOR),
        automaticGainControl = preferences.getBoolean(PREF_ANDROID_AGC, DEFAULT_ANDROID_AGC),
    )

    fun getEchoCancellationMethod(): String =
        preferences.getString(PREF_ECHO_CANCELLATION_METHOD, DEFAULT_ECHO_CANCELLATION_METHOD)!!

    /** Written by the channel-list menu so the chain can be switched without a restart. */
    fun setEchoCancellationMethod(method: String) {
        preferences.edit().putString(PREF_ECHO_CANCELLATION_METHOD, method).apply()
    }

    fun shouldStayAwake(): Boolean = preferences.getBoolean(PREF_STAY_AWAKE, DEFAULT_STAY_AWAKE)

    fun setDefaultCertificateId(defaultCertificateId: Long) {
        preferences.edit().putLong(PREF_CERT_ID, defaultCertificateId).apply()
    }

    fun disableCertificate() {
        preferences.edit().putLong(PREF_CERT_ID, -1).apply()
    }

    fun isUsingCertificate(): Boolean = getDefaultCertificate() >= 0

    /** @return true if the user count should be shown next to channels. */
    fun shouldShowUserCount(): Boolean = preferences.getBoolean(PREF_SHOW_USER_COUNT, DEFAULT_SHOW_USER_COUNT)

    fun shouldStartUpInPinnedMode(): Boolean =
        preferences.getBoolean(PREF_START_UP_IN_PINNED_MODE, DEFAULT_START_UP_IN_PINNED_MODE)

    fun getNewsShownVersions(): Set<String> =
        preferences.getStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet())!!

    fun addNewsShownVersions(versions: List<String>) {
        // Copy: getStringSet docs state that the returned set must not be modified.
        val shownVersions = HashSet(preferences.getStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet())!!)
        val added = shownVersions.addAll(versions.filter { it.isNotEmpty() })
        if (added) {
            preferences.edit().putStringSet(PREF_NEWS_SHOWN_VERSIONS, shownVersions).apply()
        }
    }

    fun resetNewsShownVersion() {
        preferences.edit().putStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet()).apply()
    }

    fun isBluetoothScoEnabled(): Boolean =
        preferences.getBoolean(PREF_BLUETOOTH_SCO, DEFAULT_BLUETOOTH_SCO)

    fun setBluetoothScoEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_BLUETOOTH_SCO, enabled).apply()
    }

    fun getMediaButtonAction(): MediaButtonAction =
        MediaButtonAction.fromPrefValue(
            preferences.getString(PREF_MEDIA_BUTTON_ACTION, DEFAULT_MEDIA_BUTTON_ACTION)
        )

    fun isBatteryOptimizationAsked(): Boolean =
        preferences.getBoolean(PREF_BATTERY_OPTIMIZATION_ASKED, DEFAULT_BATTERY_OPTIMIZATION_ASKED)

    fun setBatteryOptimizationAsked(asked: Boolean) {
        preferences.edit().putBoolean(PREF_BATTERY_OPTIMIZATION_ASKED, asked).apply()
    }

    companion object {
        const val PREF_INPUT_METHOD = "audioInputMethod"
        /** Voice activity transmits depending on the amplitude of user input. */
        const val ARRAY_INPUT_METHOD_VOICE = "voiceActivity"
        /** Push to talk transmits on command. */
        const val ARRAY_INPUT_METHOD_PTT = "ptt"
        /** Continuous transmits always. */
        const val ARRAY_INPUT_METHOD_CONTINUOUS = "continuous"
        @JvmField
        val ARRAY_INPUT_METHODS: Set<String> = setOf(ARRAY_INPUT_METHOD_VOICE, ARRAY_INPUT_METHOD_PTT, ARRAY_INPUT_METHOD_CONTINUOUS)

        // NOTE: When changing DEFAULTs, the default value in the corresponding
        // widget in settings_PAGE.xml must also be changed. It doesn't pick this
        // up itself...

        const val PREF_THRESHOLD = "vadThreshold"
        const val DEFAULT_THRESHOLD = 50

        const val PREF_PUSH_KEY = "talkKey"
        const val DEFAULT_PUSH_KEY = -1

        const val PREF_HOT_CORNER_KEY = "hotCorner"
        const val ARRAY_HOT_CORNER_NONE = "none"
        const val ARRAY_HOT_CORNER_TOP_LEFT = "topLeft"
        const val ARRAY_HOT_CORNER_BOTTOM_LEFT = "bottomLeft"
        const val ARRAY_HOT_CORNER_TOP_RIGHT = "topRight"
        const val ARRAY_HOT_CORNER_BOTTOM_RIGHT = "bottomRight"
        const val DEFAULT_HOT_CORNER = ARRAY_HOT_CORNER_NONE

        const val PREF_PUSH_BUTTON_HIDE_KEY = "hidePtt"
        const val DEFAULT_PUSH_BUTTON_HIDE = false

        const val PREF_PTT_TOGGLE = "togglePtt"
        const val DEFAULT_PTT_TOGGLE = false

        const val PREF_INPUT_RATE = "input_quality"
        const val DEFAULT_RATE = "48000"

        const val PREF_INPUT_QUALITY = "input_bitrate"
        const val DEFAULT_INPUT_QUALITY = 40000

        const val PREF_AMPLITUDE_BOOST = "inputVolume"
        const val DEFAULT_AMPLITUDE_BOOST = 100

        const val PREF_CHAT_NOTIFY = "chatNotify"
        const val DEFAULT_CHAT_NOTIFY = true

        const val PREF_USE_TTS = "useTts"
        const val DEFAULT_USE_TTS = true

        const val PREF_SHORT_TTS_MESSAGES = "shortTtsMessages"
        const val DEFAULT_SHORT_TTS_MESSAGES = false

        const val PREF_AUTO_RECONNECT = "autoReconnect"
        const val DEFAULT_AUTO_RECONNECT = true

        const val PREF_THEME = "theme"
        const val PREF_LANGUAGE = "language"

        const val PREF_PTT_BUTTON_HEIGHT = "pttButtonHeight"
        const val DEFAULT_PTT_BUTTON_HEIGHT = 150

        /** The DB identifier for the default certificate. @see se.lublin.mumla.db.DatabaseCertificate */
        const val PREF_CERT_ID = "certificateId"

        const val PREF_DEFAULT_USERNAME = "defaultUsername"
        const val DEFAULT_DEFAULT_USERNAME = "Mumla_User" // funny var name

        const val PREF_FORCE_TCP = "forceTcp"
        const val DEFAULT_FORCE_TCP = false

        const val PREF_USE_TOR = "useTor"
        const val DEFAULT_USE_TOR = false

        const val PREF_DISABLE_OPUS = "disableOpus"
        const val DEFAULT_DISABLE_OPUS = false

        const val PREF_MUTED = "muted"
        const val DEFAULT_MUTED = false

        const val PREF_DEAFENED = "deafened"
        const val DEFAULT_DEAFENED = false

        const val PREF_FIRST_RUN = "firstRun"
        const val DEFAULT_FIRST_RUN = true

        const val PREF_LOAD_IMAGES = "load_images"
        const val DEFAULT_LOAD_IMAGES = true

        const val PREF_FRAMES_PER_PACKET = "audio_per_packet"
        const val DEFAULT_FRAMES_PER_PACKET = "2"

        const val PREF_HALF_DUPLEX = "half_duplex"
        const val DEFAULT_HALF_DUPLEX = false

        const val PREF_HANDSET_MODE = "handset_mode"
        const val DEFAULT_HANDSET_MODE = false

        const val PREF_PTT_SOUND = "ptt_sound"
        const val DEFAULT_PTT_SOUND = false

        const val PREF_PREPROCESSOR_ENABLED = "preprocessor_enabled"
        const val DEFAULT_PREPROCESSOR_ENABLED = true

        const val PREF_NOISE_SUPPRESSION_METHOD = "noise_suppression_method"
        const val PREF_ECHO_CANCELLATION_METHOD = "echo_cancellation_method"

        /** Stored as a string because it is a ListPreference; spec B9 allows -15/-25/-35. */
        const val PREF_SPEEX_NOISE_SUPPRESS_DB = "speex_noise_suppress_db"
        const val DEFAULT_SPEEX_NOISE_SUPPRESS_DB = -25

        /**
         * One of [VadMode.preferenceValue].
         *
         * **The default is the new mode, and that is a user-visible change rather than a silent
         * one.** `VadConfig`'s KDoc refuses a default that takes a working slider away from someone
         * who never saw the new key -- the objection is to a control that silently stops doing
         * anything. Here the settings screen shows the mode, explains what the slider means in it,
         * disables the controls the mode does not use, and leaves `vadThreshold` on disk, so
         * switching back restores the old calibration exactly. What made the objection bite was
         * *silence*, and the screen is the answer to it.
         */
        const val PREF_VAD_MODE = "vad_mode"
        const val DEFAULT_VAD_MODE = "adaptive"

        /** Percent; the fraction of the measured speech-to-floor gap a frame has to clear. */
        const val PREF_VAD_SENSITIVITY = "vad_sensitivity"
        const val DEFAULT_VAD_SENSITIVITY = 65

        const val PREF_VAD_ADAPTIVE_FLOOR = "vad_adaptive_floor"
        const val DEFAULT_VAD_ADAPTIVE_FLOOR = true

        /** dB **below** full scale, so the slider can stay positive. 45 means -45 dBFS. */
        const val PREF_VAD_FLOOR_DB = "vad_floor_db"
        const val DEFAULT_VAD_FLOOR_DB = 45

        /** Percent, [VadMode.PROBABILITY] only. */
        const val PREF_VAD_START = "vad_start"
        const val DEFAULT_VAD_START = 60
        const val PREF_VAD_STOP = "vad_stop"
        const val DEFAULT_VAD_STOP = 30

        const val PREF_VAD_HOLD_MS = "vad_hold_ms"
        const val DEFAULT_VAD_HOLD_MS = 250

        /** Two seconds of hold is already longer than any pause inside a word. */
        const val MAX_VAD_HOLD_MS = 2000

        /**
         * The transient guard, in 10 ms frames. Two by default here while the library keeps one,
         * because a library default that changes every caller is the silent migration this project
         * refuses; this is the user-facing default and the settings screen explains it.
         */
        const val PREF_VAD_ONSET_FRAMES = "vad_onset_frames"
        const val DEFAULT_VAD_ONSET_FRAMES = 2

        /** Five frames is 50 ms of a word's beginning, which is already audible as a clipped word. */
        const val MAX_VAD_ONSET_FRAMES = 5

        const val PREF_ANDROID_NOISE_SUPPRESSOR = "android_noise_suppressor"
        const val DEFAULT_ANDROID_NOISE_SUPPRESSOR = false
        const val PREF_ANDROID_AGC = "android_agc"
        const val DEFAULT_ANDROID_AGC = false


        /**
         * Still "none", and **blocked from moving** until the playback route is fixed.
         *
         * Any other value makes `AudioSourcePolicy.needsCommunicationMode` true, and
         * `AudioHandler` then puts the AudioManager into `MODE_IN_COMMUNICATION` -- while the
         * playback `AudioTrack` is opened on the stream `ServerConnectTask:61` chose, which is
         * `STREAM_MUSIC` for everyone who has not switched handset mode on. In communication mode
         * Android routes by the communication device, and a media-stream track no longer follows
         * it. Reported from a Galaxy S25 on `"system"`: **the user hears nobody at all.**
         *
         * The fix is a routing one -- `AudioManager.setCommunicationDevice` to the built-in
         * speaker when handset mode is off, and the track on the communication stream -- and the
         * `AndroidCommunicationDevices` seam that owns it lives in the core stream, not here.
         * `EchoCancellationDefaultRouteTest` fails the moment this constant changes, on purpose.
         */
        const val DEFAULT_ECHO_CANCELLATION_METHOD = "none"

        const val PREF_STAY_AWAKE = "stay_awake"
        const val DEFAULT_STAY_AWAKE = false

        const val PREF_SHOW_USER_COUNT = "show_user_count"
        const val DEFAULT_SHOW_USER_COUNT = false

        const val PREF_START_UP_IN_PINNED_MODE = "startUpInPinnedMode"
        const val DEFAULT_START_UP_IN_PINNED_MODE = false

        const val PREF_NEWS_SHOWN_VERSIONS = "newsShownVersions"

        /** Route audio through a Bluetooth headset (SCO) whenever connected. Spec P2. */
        const val PREF_BLUETOOTH_SCO = "pref_bluetooth_sco"
        const val DEFAULT_BLUETOOTH_SCO = false

        /** Headset / AVRCP media button behavior, one of [MediaButtonAction.prefValue]. Spec P1. */
        const val PREF_MEDIA_BUTTON_ACTION = "media_button_action"
        const val DEFAULT_MEDIA_BUTTON_ACTION = "auto"

        /** True once the battery-optimization exemption has been offered. Spec P4. */
        const val PREF_BATTERY_OPTIMIZATION_ASKED = "battery_optimization_asked"
        const val DEFAULT_BATTERY_OPTIMIZATION_ASKED = false

        @JvmStatic
        fun getInstance(context: Context): Settings = Settings(context)
    }
}
