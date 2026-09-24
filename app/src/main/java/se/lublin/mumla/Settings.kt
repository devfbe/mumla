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
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import se.lublin.humla.Constants
import se.lublin.humla.audio.capture.AdaptiveVadTracker
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.SpeexPreprocessor
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Typed access to the app's preferences. Each preference's key and default live in the companion;
 * the properties read and write through to disk, so they always reflect the current value.
 */
@Suppress("TooManyFunctions") // One accessor per preference.
class Settings private constructor(private val context: Context) {

    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /** Rewrites the keys older versions left behind. Runs once per process, on first use. */
    private fun migrateLegacyKeys() {
        // The first two were replaced by the audio chooser.
        val legacy = listOf(LEGACY_PREF_ECHO_CANCELLATION_METHOD, LEGACY_PREF_DISABLE_OPUS, LEGACY_PREF_HANDSET_MODE)
            .filter(preferences::contains)
        if (legacy.isEmpty()) return
        // The handset mode was the earpiece; a default output the user already picked wins.
        val earpiece = preferences.getBoolean(LEGACY_PREF_HANDSET_MODE, false) &&
            !preferences.contains(PREF_DEFAULT_OUTPUT)
        preferences.edit {
            legacy.forEach(::remove)
            if (earpiece) putString(PREF_DEFAULT_OUTPUT, DEFAULT_OUTPUT_EARPIECE)
        }
    }

    private fun booleanPref(key: String, default: Boolean) = object : ReadWriteProperty<Settings, Boolean> {
        override fun getValue(thisRef: Settings, property: KProperty<*>) = preferences.getBoolean(key, default)
        override fun setValue(thisRef: Settings, property: KProperty<*>, value: Boolean) =
            preferences.edit { putBoolean(key, value) }
    }

    private fun intPref(key: String, default: Int) = ReadOnlyProperty<Settings, Int> { _, _ ->
        preferences.getInt(key, default)
    }

    private fun stringPref(key: String, default: String) = ReadOnlyProperty<Settings, String> { _, _ ->
        preferences.getString(key, default) ?: default
    }

    /** One of [ARRAY_INPUT_METHODS]; an unknown stored value reads as voice activity. */
    var inputMethod: String
        get() = preferences.getString(PREF_INPUT_METHOD, ARRAY_INPUT_METHOD_VOICE)
            ?.takeIf { it in ARRAY_INPUT_METHODS } ?: ARRAY_INPUT_METHOD_VOICE
        set(value) {
            require(value in ARRAY_INPUT_METHODS) { "Invalid input method $value" }
            preferences.edit { putString(PREF_INPUT_METHOD, value) }
        }

    /** The input method as a Humla `Constants.TRANSMIT_*` value. */
    val humlaInputMethod: Int
        get() = when (inputMethod) {
            ARRAY_INPUT_METHOD_PTT -> Constants.TRANSMIT_PUSH_TO_TALK
            ARRAY_INPUT_METHOD_CONTINUOUS -> Constants.TRANSMIT_CONTINUOUS
            else -> Constants.TRANSMIT_VOICE_ACTIVITY
        }

    val inputSampleRate: Int get() = preferences.getString(PREF_INPUT_RATE, DEFAULT_RATE)!!.toInt()

    val inputQuality: Int by intPref(PREF_INPUT_QUALITY, DEFAULT_INPUT_QUALITY)

    val amplitudeBoostMultiplier: Float
        get() = preferences.getInt(PREF_AMPLITUDE_BOOST, DEFAULT_AMPLITUDE_BOOST).toFloat() / 100

    val detectionThreshold: Float get() = preferences.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD).toFloat() / 100

    val pushToTalkKey: Int by intPref(PREF_PUSH_KEY, DEFAULT_PUSH_KEY)

    val hotCorner: String by stringPref(PREF_HOT_CORNER_KEY, DEFAULT_HOT_CORNER)

    val isHotCornerEnabled: Boolean get() = hotCorner != ARRAY_HOT_CORNER_NONE

    /** A [Gravity] value, or 0 if the hot corner is disabled. */
    val hotCornerGravity: Int
        get() = when (hotCorner) {
            ARRAY_HOT_CORNER_BOTTOM_LEFT -> Gravity.LEFT or Gravity.BOTTOM
            ARRAY_HOT_CORNER_BOTTOM_RIGHT -> Gravity.RIGHT or Gravity.BOTTOM
            ARRAY_HOT_CORNER_TOP_LEFT -> Gravity.LEFT or Gravity.TOP
            ARRAY_HOT_CORNER_TOP_RIGHT -> Gravity.RIGHT or Gravity.TOP
            else -> 0
        }

    val pttButtonHeight: Int by intPref(PREF_PTT_BUTTON_HEIGHT, DEFAULT_PTT_BUTTON_HEIGHT)

    /** Database id of the default certificate, or negative if none is set. */
    var defaultCertificateId: Long
        get() = preferences.getLong(PREF_CERT_ID, NO_CERTIFICATE)
        set(value) = preferences.edit { putLong(PREF_CERT_ID, value) }

    val isUsingCertificate: Boolean get() = defaultCertificateId >= 0

    fun disableCertificate() {
        defaultCertificateId = NO_CERTIFICATE
    }

    val defaultUsername: String by stringPref(PREF_DEFAULT_USERNAME, DEFAULT_DEFAULT_USERNAME)

    val isPushToTalkToggle: Boolean by booleanPref(PREF_PTT_TOGGLE, DEFAULT_PTT_TOGGLE)

    /** Whether other apps may start and stop transmission through the talk broadcast. */
    val isExternalPushToTalkAllowed: Boolean by booleanPref(PREF_ALLOW_EXTERNAL_PTT, DEFAULT_ALLOW_EXTERNAL_PTT)

    val isPushToTalkButtonShown: Boolean
        get() = !preferences.getBoolean(PREF_PUSH_BUTTON_HIDE_KEY, DEFAULT_PUSH_BUTTON_HIDE)

    val isChatNotifyEnabled: Boolean by booleanPref(PREF_CHAT_NOTIFY, DEFAULT_CHAT_NOTIFY)

    val isTextToSpeechEnabled: Boolean by booleanPref(PREF_USE_TTS, DEFAULT_USE_TTS)

    val isShortTextToSpeechMessagesEnabled: Boolean by booleanPref(PREF_SHORT_TTS_MESSAGES, DEFAULT_SHORT_TTS_MESSAGES)

    val isAutoReconnectEnabled: Boolean by booleanPref(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)

    val isTcpForced: Boolean by booleanPref(PREF_FORCE_TCP, DEFAULT_FORCE_TCP)

    var isTorEnabled: Boolean by booleanPref(PREF_USE_TOR, DEFAULT_USE_TOR)

    val isMuted: Boolean by booleanPref(PREF_MUTED, DEFAULT_MUTED)

    val isDeafened: Boolean by booleanPref(PREF_DEAFENED, DEFAULT_DEAFENED)

    var isFirstRun: Boolean by booleanPref(PREF_FIRST_RUN, DEFAULT_FIRST_RUN)

    /** Whether remote chat images may be fetched; never while Tor is on, since the fetch would bypass it. */
    val shouldLoadExternalImages: Boolean
        get() = preferences.getBoolean(PREF_LOAD_IMAGES, DEFAULT_LOAD_IMAGES) && !isTorEnabled

    fun setMutedAndDeafened(muted: Boolean, deafened: Boolean) {
        preferences.edit {
            putBoolean(PREF_MUTED, muted || deafened)
            putBoolean(PREF_DEAFENED, deafened)
        }
    }

    val framesPerPacket: Int get() = preferences.getString(PREF_FRAMES_PER_PACKET, DEFAULT_FRAMES_PER_PACKET)!!.toInt()

    val isHalfDuplex: Boolean by booleanPref(PREF_HALF_DUPLEX, DEFAULT_HALF_DUPLEX)

    /** Voice output without a headset: speaker, or the earpiece (with proximity sensor). */
    val isEarpieceDefaultOutput: Boolean
        get() = preferences.getString(PREF_DEFAULT_OUTPUT, DEFAULT_OUTPUT_SPEAKER) == DEFAULT_OUTPUT_EARPIECE

    val isPttSoundEnabled: Boolean by booleanPref(PREF_PTT_SOUND, DEFAULT_PTT_SOUND)

    val isPreprocessorEnabled: Boolean by booleanPref(PREF_PREPROCESSOR_ENABLED, DEFAULT_PREPROCESSOR_ENABLED)

    /**
     * The stored value, else what the legacy `preprocessor_enabled` checkbox decided. Writes only
     * this key, not [PREF_PREPROCESSOR_ENABLED]: every written key triggers its own `configure`,
     * and two would rebuild the audio chain twice with the mic dead in between.
     */
    var noiseSuppressionMethod: String
        get() = preferences.getString(PREF_NOISE_SUPPRESSION_METHOD, null)
            ?: if (isPreprocessorEnabled) "rnnoise" else "none"
        set(value) = preferences.edit { putString(PREF_NOISE_SUPPRESSION_METHOD, value) }

    val noiseSuppressionMode: NoiseSuppressionMode
        get() = NoiseSuppressionMode.fromPreferenceValue(noiseSuppressionMethod)

    /** Anything outside [SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB] is the default. */
    val speexNoiseSuppressDb: Int
        get() = preferences.getString(PREF_SPEEX_NOISE_SUPPRESS_DB, null)?.toIntOrNull()
            ?.takeIf { it in SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB } ?: DEFAULT_SPEEX_NOISE_SUPPRESS_DB

    val vadMode: VadMode get() = VadMode.fromPreferenceValue(preferences.getString(PREF_VAD_MODE, DEFAULT_VAD_MODE))

    /**
     * The whole voice-gate configuration as one value. Every read is clamped here because
     * [VadConfig] throws on out-of-range values and preference files can hold anything (debug
     * edits, downgrades).
     */
    val vadConfig: VadConfig
        get() {
            val holdMs = preferences.getInt(PREF_VAD_HOLD_MS, DEFAULT_VAD_HOLD_MS)
                .coerceIn(0, MAX_VAD_HOLD_MS).toLong()
            // A ListPreference, so the value on disk is a string.
            val onsetFrames = (preferences.getString(PREF_VAD_ONSET_FRAMES, null)?.toIntOrNull()
                ?: DEFAULT_VAD_ONSET_FRAMES).coerceIn(1, MAX_VAD_ONSET_FRAMES)
            return when (vadMode) {
                VadMode.AMPLITUDE -> VadConfig.amplitude(detectionThreshold, holdMs, onsetFrames)
                VadMode.PROBABILITY -> {
                    val start = percent(PREF_VAD_START, DEFAULT_VAD_START)
                    val stop = percent(PREF_VAD_STOP, DEFAULT_VAD_STOP).coerceAtMost(start)
                    VadConfig(VadMode.PROBABILITY, start, stop, holdMs, onsetFrames = onsetFrames)
                }
                VadMode.ADAPTIVE -> VadConfig.adaptive(
                    snrFraction = percent(PREF_VAD_SENSITIVITY, DEFAULT_VAD_SENSITIVITY),
                    holdTimeMs = holdMs,
                    onsetFrames = onsetFrames,
                    adaptiveFloor = preferences.getBoolean(PREF_VAD_ADAPTIVE_FLOOR, DEFAULT_VAD_ADAPTIVE_FLOOR),
                    manualFloorDbfs = (-preferences.getInt(PREF_VAD_FLOOR_DB, DEFAULT_VAD_FLOOR_DB).toFloat())
                        .coerceIn(AdaptiveVadTracker.MIN_FLOOR_DBFS, AdaptiveVadTracker.MAX_FLOOR_DBFS),
                )
            }
        }

    /** A 0..100 slider as a fraction. */
    private fun percent(key: String, default: Int): Float = preferences.getInt(key, default).coerceIn(0, 100) / 100f

    /** The two `android.media.audiofx` effects attached to the recorder's session. */
    val androidAudioEffects: AndroidAudioEffects
        get() = AndroidAudioEffects(
            noiseSuppressor = preferences.getBoolean(PREF_ANDROID_NOISE_SUPPRESSOR, DEFAULT_ANDROID_NOISE_SUPPRESSOR),
            automaticGainControl = preferences.getBoolean(PREF_ANDROID_AGC, DEFAULT_ANDROID_AGC),
        )

    /** The user's echo-cancellation choice for a device kind, or null to use the kind's default. */
    fun getEchoCancellationOverride(category: AudioDeviceCategory): Boolean? {
        val key = echoCancellationKey(category)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    fun setEchoCancellationOverride(category: AudioDeviceCategory, enabled: Boolean) {
        preferences.edit { putBoolean(echoCancellationKey(category), enabled) }
    }

    /** What runs on a device of [category]: the user's choice, else the kind's default. */
    fun isEchoCancellationEnabled(category: AudioDeviceCategory): Boolean =
        getEchoCancellationOverride(category) ?: category.echoCancellationByDefault

    /** Every override the user has made, for `SessionConfig.echoCancellationOverrides`. */
    val echoCancellationOverrides: Map<AudioDeviceCategory, Boolean>
        get() = AudioDeviceCategory.entries.mapNotNull { c -> getEchoCancellationOverride(c)?.let { c to it } }.toMap()

    val shouldStayAwake: Boolean by booleanPref(PREF_STAY_AWAKE, DEFAULT_STAY_AWAKE)

    val shouldShowUserCount: Boolean by booleanPref(PREF_SHOW_USER_COUNT, DEFAULT_SHOW_USER_COUNT)

    /** Wallpaper-based colours instead of the brand scheme, where the platform offers them. */
    val isDynamicColorEnabled: Boolean by booleanPref(PREF_DYNAMIC_COLORS, DEFAULT_DYNAMIC_COLORS)

    val shouldStartUpInPinnedMode: Boolean by booleanPref(PREF_START_UP_IN_PINNED_MODE, DEFAULT_START_UP_IN_PINNED_MODE)

    val newsShownVersions: Set<String>
        get() = preferences.getStringSet(PREF_NEWS_SHOWN_VERSIONS, null).orEmpty()

    fun addNewsShownVersions(versions: List<String>) {
        // Copied: the set getStringSet returns must not be modified.
        val shownVersions = HashSet(newsShownVersions)
        if (shownVersions.addAll(versions.filter { it.isNotEmpty() })) {
            preferences.edit { putStringSet(PREF_NEWS_SHOWN_VERSIONS, shownVersions) }
        }
    }

    fun resetNewsShownVersion() {
        preferences.edit { putStringSet(PREF_NEWS_SHOWN_VERSIONS, HashSet()) }
    }

    var isBluetoothScoEnabled: Boolean by booleanPref(PREF_BLUETOOTH_SCO, DEFAULT_BLUETOOTH_SCO)

    val mediaButtonAction: MediaButtonAction
        get() = MediaButtonAction.fromPrefValue(
            preferences.getString(PREF_MEDIA_BUTTON_ACTION, DEFAULT_MEDIA_BUTTON_ACTION),
        )

    var isBatteryOptimizationAsked: Boolean by booleanPref(
        PREF_BATTERY_OPTIMIZATION_ASKED, DEFAULT_BATTERY_OPTIMIZATION_ASKED,
    )

    var isMicrophonePermissionAsked: Boolean by booleanPref(PREF_MICROPHONE_PERMISSION_ASKED, false)

    var isNotificationPermissionAsked: Boolean by booleanPref(PREF_NOTIFICATION_PERMISSION_ASKED, false)

    companion object {
        const val PREF_INPUT_METHOD = "audioInputMethod"
        const val ARRAY_INPUT_METHOD_VOICE = "voiceActivity"
        const val ARRAY_INPUT_METHOD_PTT = "ptt"
        const val ARRAY_INPUT_METHOD_CONTINUOUS = "continuous"
        val ARRAY_INPUT_METHODS: Set<String> =
            setOf(ARRAY_INPUT_METHOD_VOICE, ARRAY_INPUT_METHOD_PTT, ARRAY_INPUT_METHOD_CONTINUOUS)

        // NOTE: When changing DEFAULTs, also change the default in the matching settings_*.xml.

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

        const val PREF_ALLOW_EXTERNAL_PTT = "allow_external_ptt"
        const val DEFAULT_ALLOW_EXTERNAL_PTT = false

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
        const val PREF_DYNAMIC_COLORS = "dynamic_colors"
        const val DEFAULT_DYNAMIC_COLORS = false
        const val PREF_LANGUAGE = "language"

        const val PREF_PTT_BUTTON_HEIGHT = "pttButtonHeight"
        const val DEFAULT_PTT_BUTTON_HEIGHT = 150

        /** Database id of the default certificate; see [se.lublin.mumla.db.DatabaseCertificate]. */
        const val PREF_CERT_ID = "certificateId"

        const val PREF_DEFAULT_USERNAME = "defaultUsername"
        const val DEFAULT_DEFAULT_USERNAME = "Mumla_User"

        const val PREF_FORCE_TCP = "forceTcp"
        const val DEFAULT_FORCE_TCP = false

        const val PREF_USE_TOR = "useTor"
        const val DEFAULT_USE_TOR = false

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

        /** A ListPreference: [DEFAULT_OUTPUT_SPEAKER] (the default) or [DEFAULT_OUTPUT_EARPIECE]. */
        const val PREF_DEFAULT_OUTPUT = "default_output"
        const val DEFAULT_OUTPUT_SPEAKER = "speaker"
        const val DEFAULT_OUTPUT_EARPIECE = "earpiece"

        /** The handset switch the default output replaced; migrated and removed on first read. */
        private const val LEGACY_PREF_HANDSET_MODE = "handset_mode"

        const val PREF_PTT_SOUND = "ptt_sound"
        const val DEFAULT_PTT_SOUND = false

        const val PREF_PREPROCESSOR_ENABLED = "preprocessor_enabled"
        const val DEFAULT_PREPROCESSOR_ENABLED = true

        const val PREF_NOISE_SUPPRESSION_METHOD = "noise_suppression_method"

        /** Stored as a string because it is a ListPreference; -15/-25/-35. */
        const val PREF_SPEEX_NOISE_SUPPRESS_DB = "speex_noise_suppress_db"
        const val DEFAULT_SPEEX_NOISE_SUPPRESS_DB = -25

        /**
         * One of [VadMode.preferenceValue]. Switching modes keeps `vadThreshold` on disk, so going
         * back to amplitude restores the old calibration.
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

        const val MAX_VAD_HOLD_MS = 2000

        /** The transient guard, in 10 ms frames; the library default is one. */
        const val PREF_VAD_ONSET_FRAMES = "vad_onset_frames"
        const val DEFAULT_VAD_ONSET_FRAMES = 2

        /** Five frames (50 ms) already clips a word's beginning audibly. */
        const val MAX_VAD_ONSET_FRAMES = 5

        const val PREF_ANDROID_NOISE_SUPPRESSOR = "android_noise_suppressor"
        const val DEFAULT_ANDROID_NOISE_SUPPRESSOR = false
        const val PREF_ANDROID_AGC = "android_agc"
        const val DEFAULT_ANDROID_AGC = false


        /**
         * Playback always uses the voice-call stream: the audio router holds communication mode and
         * routes devices explicitly, and only this stream follows that route (and the volume keys).
         */
        const val PLAYBACK_STREAM = android.media.AudioManager.STREAM_VOICE_CALL

        private const val PREF_ECHO_CANCELLATION_PREFIX = "echo_cancellation_"

        /** The global echo method the audio chooser replaced; removed on first read. */
        private const val LEGACY_PREF_ECHO_CANCELLATION_METHOD = "echo_cancellation_method"

        /** The "avoid Opus" checkbox; Opus is now always offered. Removed on first read. */
        private const val LEGACY_PREF_DISABLE_OPUS = "disableOpus"

        /** The preference key of the echo-cancellation override for [category]. */
        fun echoCancellationKey(category: AudioDeviceCategory): String =
            PREF_ECHO_CANCELLATION_PREFIX + category.name.lowercase()

        val ECHO_CANCELLATION_KEYS: Set<String> = AudioDeviceCategory.entries.map { echoCancellationKey(it) }.toSet()

        const val PREF_STAY_AWAKE = "stay_awake"
        const val DEFAULT_STAY_AWAKE = false

        const val PREF_SHOW_USER_COUNT = "show_user_count"
        const val DEFAULT_SHOW_USER_COUNT = false

        const val PREF_START_UP_IN_PINNED_MODE = "startUpInPinnedMode"
        const val DEFAULT_START_UP_IN_PINNED_MODE = false

        const val PREF_NEWS_SHOWN_VERSIONS = "newsShownVersions"

        /** Use a connected Bluetooth headset automatically while connected. */
        const val PREF_BLUETOOTH_SCO = "pref_bluetooth_sco"
        const val DEFAULT_BLUETOOTH_SCO = true

        /** Headset / AVRCP media button behavior, one of [MediaButtonAction.prefValue]. */
        const val PREF_MEDIA_BUTTON_ACTION = "media_button_action"
        const val DEFAULT_MEDIA_BUTTON_ACTION = "auto"

        /** True once the battery-optimization exemption has been offered. */
        const val PREF_BATTERY_OPTIMIZATION_ASKED = "battery_optimization_asked"
        const val DEFAULT_BATTERY_OPTIMIZATION_ASKED = false

        /** True once the microphone permission has been requested. */
        const val PREF_MICROPHONE_PERMISSION_ASKED = "microphone_permission_asked"

        /** True once the notification permission has been requested. */
        const val PREF_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

        private const val NO_CERTIFICATE = -1L

        @Volatile
        private var instance: Settings? = null

        /**
         * The settings of [context]'s application. Created, and the legacy keys migrated, on first
         * use; a new application (as in tests) gets a new instance.
         */
        fun getInstance(context: Context): Settings {
            val app = context.applicationContext
            instance?.takeIf { it.context === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.context === app }
                    ?: Settings(app).also {
                        it.migrateLegacyKeys()
                        instance = it
                    }
            }
        }
    }
}
