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
import android.media.AudioDeviceInfo
import android.view.Gravity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import se.lublin.humla.audio.capture.AdaptiveVadTracker
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.SpeexPreprocessor
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.util.Constants
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
        // Obsolete keys, replaced by the audio chooser or dropped.
        val legacy = listOf(
            LEGACY_PREF_ECHO_CANCELLATION_METHOD, LEGACY_PREF_DISABLE_OPUS, LEGACY_PREF_HANDSET_MODE,
            LEGACY_PREF_DEFAULT_OUTPUT,
        ).filter(preferences::contains)
        if (legacy.isEmpty()) return
        // Both meant the earpiece; the newer choice wins over the older, a saved device over both.
        val earpiece = !preferences.contains(AUDIO_DEVICE.key) &&
            if (preferences.contains(LEGACY_PREF_DEFAULT_OUTPUT)) {
                preferences.getString(LEGACY_PREF_DEFAULT_OUTPUT, null) == LEGACY_DEFAULT_OUTPUT_EARPIECE
            } else {
                preferences.getBoolean(LEGACY_PREF_HANDSET_MODE, false)
            }
        preferences.edit {
            legacy.forEach(::remove)
            if (earpiece) {
                putString(AUDIO_DEVICE.key, encode(PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
            }
        }
    }

    /** Reads and writes [pref] through to disk. */
    private fun <T : Any> pref(pref: Pref<T>) = object : ReadWriteProperty<Settings, T> {
        override fun getValue(thisRef: Settings, property: KProperty<*>): T = preferences.read(pref)
        override fun setValue(thisRef: Settings, property: KProperty<*>, value: T) =
            preferences.edit { write(pref, value) }
    }

    /** One of [ARRAY_INPUT_METHODS]; an unknown stored value reads as voice activity. */
    var inputMethod: String
        get() = preferences.read(INPUT_METHOD)
            .takeIf { it in ARRAY_INPUT_METHODS } ?: ARRAY_INPUT_METHOD_VOICE
        set(value) {
            require(value in ARRAY_INPUT_METHODS) { "Invalid input method $value" }
            preferences.edit { putString(INPUT_METHOD.key, value) }
        }

    /** The input method as a Humla `Constants.TRANSMIT_*` value. */
    val humlaInputMethod: Int
        get() = when (inputMethod) {
            ARRAY_INPUT_METHOD_PTT -> Constants.TRANSMIT_PUSH_TO_TALK
            ARRAY_INPUT_METHOD_CONTINUOUS -> Constants.TRANSMIT_CONTINUOUS
            else -> Constants.TRANSMIT_VOICE_ACTIVITY
        }

    val inputSampleRate: Int get() = preferences.read(INPUT_RATE).toInt()

    val inputQuality: Int by pref(INPUT_QUALITY)

    val amplitudeBoostMultiplier: Float
        get() = preferences.read(AMPLITUDE_BOOST).toFloat() / PERCENT

    val detectionThreshold: Float get() = preferences.read(THRESHOLD).toFloat() / PERCENT

    val pushToTalkKey: Int by pref(TALK_KEY)

    val hotCorner: String by pref(HOT_CORNER)

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

    val pttButtonHeight: Int by pref(PTT_BUTTON_HEIGHT)

    /** Database id of the default certificate, or negative if none is set. */
    var defaultCertificateId: Long
        get() = preferences.read(CERT_ID)
        set(value) = preferences.edit { write(CERT_ID, value) }

    val isUsingCertificate: Boolean get() = defaultCertificateId >= 0

    fun disableCertificate() {
        defaultCertificateId = NO_CERTIFICATE
    }

    val defaultUsername: String by pref(DEFAULT_USERNAME)

    val isPushToTalkToggle: Boolean by pref(PTT_TOGGLE)

    /** Whether other apps may start and stop transmission through the talk broadcast. */
    val isExternalPushToTalkAllowed: Boolean by pref(ALLOW_EXTERNAL_PTT)

    val isPushToTalkButtonShown: Boolean
        get() = !preferences.read(PUSH_BUTTON_HIDE)

    val isChatNotifyEnabled: Boolean by pref(CHAT_NOTIFY)

    val isTextToSpeechEnabled: Boolean by pref(USE_TTS)

    val isShortTextToSpeechMessagesEnabled: Boolean by pref(SHORT_TTS_MESSAGES)

    val isAutoReconnectEnabled: Boolean by pref(AUTO_RECONNECT)

    val isTcpForced: Boolean by pref(FORCE_TCP)

    var isTorEnabled: Boolean by pref(USE_TOR)

    val isMuted: Boolean by pref(MUTED)

    val isDeafened: Boolean by pref(DEAFENED)

    var isFirstRun: Boolean by pref(FIRST_RUN)

    /** Whether remote chat images may be fetched; never while Tor is on, since the fetch would bypass it. */
    /** Whether typed chat messages are sent as Markdown turned into HTML. */
    val isMarkdownEnabled: Boolean by pref(MARKDOWN)

    val shouldLoadExternalImages: Boolean
        get() = preferences.read(LOAD_IMAGES) && !isTorEnabled

    fun setMutedAndDeafened(muted: Boolean, deafened: Boolean) {
        preferences.edit {
            putBoolean(MUTED.key, muted || deafened)
            putBoolean(DEAFENED.key, deafened)
        }
    }

    val framesPerPacket: Int get() = preferences.read(FRAMES_PER_PACKET).toInt()

    val isHalfDuplex: Boolean by pref(HALF_DUPLEX)

    /** The device picked in the audio chooser; null (nothing stored) routes automatically. */
    var preferredAudioDevice: PreferredAudioDevice?
        get() = preferences.getString(AUDIO_DEVICE.key, null)?.let(::decode)
        set(value) = preferences.edit {
            if (value == null) remove(AUDIO_DEVICE.key) else putString(AUDIO_DEVICE.key, encode(value))
        }

    /** `type` or `type:address`; the type has no colon, the address may. */
    private fun encode(device: PreferredAudioDevice): String =
        device.address?.let { "${device.type}:$it" } ?: "${device.type}"

    private fun decode(stored: String): PreferredAudioDevice? {
        val type = stored.substringBefore(':').toIntOrNull() ?: return null
        return PreferredAudioDevice(type, stored.substringAfter(':', "").ifEmpty { null })
    }

    val isPttSoundEnabled: Boolean by pref(PTT_SOUND)

    val isPreprocessorEnabled: Boolean by pref(PREPROCESSOR_ENABLED)

    /**
     * The stored value, else what the legacy `preprocessor_enabled` checkbox decided. Writes only
     * this key, not [PREPROCESSOR_ENABLED]: every written key triggers its own `configure`,
     * and two would rebuild the audio chain twice with the mic dead in between.
     */
    var noiseSuppressionMethod: String
        get() = preferences.getString(NOISE_SUPPRESSION_METHOD.key, null)
            ?: if (isPreprocessorEnabled) "rnnoise" else "none"
        set(value) = preferences.edit { putString(NOISE_SUPPRESSION_METHOD.key, value) }

    val noiseSuppressionMode: NoiseSuppressionMode
        get() = NoiseSuppressionMode.fromPreferenceValue(noiseSuppressionMethod)

    /** Anything outside [SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB] is the default. */
    val speexNoiseSuppressDb: Int
        get() = preferences.getString(SPEEX_NOISE_SUPPRESS_DB.key, null)?.toIntOrNull()
            ?.takeIf { it in SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB } ?: SPEEX_NOISE_SUPPRESS_DB.default

    val vadMode: VadMode get() = VadMode.fromPreferenceValue(preferences.read(VAD_MODE))

    /**
     * The whole voice-gate configuration as one value. Every read is clamped here because
     * [VadConfig] throws on out-of-range values and preference files can hold anything (debug
     * edits, downgrades).
     */
    val vadConfig: VadConfig
        get() {
            val holdMs = preferences.read(VAD_HOLD_MS)
                .coerceIn(0, MAX_VAD_HOLD_MS).toLong()
            // A ListPreference, so the value on disk is a string.
            val onsetFrames = (preferences.getString(VAD_ONSET_FRAMES.key, null)?.toIntOrNull()
                ?: VAD_ONSET_FRAMES.default).coerceIn(1, MAX_VAD_ONSET_FRAMES)
            return when (vadMode) {
                VadMode.AMPLITUDE -> VadConfig.amplitude(detectionThreshold, holdMs, onsetFrames)
                VadMode.PROBABILITY -> {
                    val start = percent(VAD_START)
                    val stop = percent(VAD_STOP).coerceAtMost(start)
                    VadConfig(VadMode.PROBABILITY, start, stop, holdMs, onsetFrames = onsetFrames)
                }
                VadMode.ADAPTIVE -> VadConfig.adaptive(
                    snrFraction = percent(VAD_SENSITIVITY),
                    holdTimeMs = holdMs,
                    onsetFrames = onsetFrames,
                    adaptiveFloor = preferences.read(VAD_ADAPTIVE_FLOOR),
                    manualFloorDbfs = (-preferences.read(VAD_FLOOR_DB).toFloat())
                        .coerceIn(AdaptiveVadTracker.MIN_FLOOR_DBFS, AdaptiveVadTracker.MAX_FLOOR_DBFS),
                )
            }
        }

    /** A 0..100 slider as a fraction. */
    private fun percent(pref: Pref<Int>): Float = preferences.read(pref).coerceIn(0, PERCENT) / PERCENT.toFloat()

    /** The two `android.media.audiofx` effects attached to the recorder's session. */
    val androidAudioEffects: AndroidAudioEffects
        get() = AndroidAudioEffects(
            noiseSuppressor = preferences.read(ANDROID_NOISE_SUPPRESSOR),
            automaticGainControl = preferences.read(ANDROID_AGC),
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

    val shouldStayAwake: Boolean by pref(STAY_AWAKE)

    val shouldShowUserCount: Boolean by pref(SHOW_USER_COUNT)

    /** Wallpaper-based colours instead of the brand scheme, where the platform offers them. */
    val isDynamicColorEnabled: Boolean by pref(DYNAMIC_COLORS)

    val shouldStartUpInPinnedMode: Boolean by pref(START_UP_IN_PINNED_MODE)

    val newsShownVersions: Set<String>
        get() = preferences.getStringSet(NEWS_SHOWN_VERSIONS.key, null).orEmpty()

    fun addNewsShownVersions(versions: List<String>) {
        // Copied: the set getStringSet returns must not be modified.
        val shownVersions = HashSet(newsShownVersions)
        if (shownVersions.addAll(versions.filter { it.isNotEmpty() })) {
            preferences.edit { putStringSet(NEWS_SHOWN_VERSIONS.key, shownVersions) }
        }
    }

    fun resetNewsShownVersion() {
        preferences.edit { putStringSet(NEWS_SHOWN_VERSIONS.key, HashSet()) }
    }

    var isBluetoothScoEnabled: Boolean by pref(BLUETOOTH_SCO)

    val mediaButtonAction: MediaButtonAction
        get() = MediaButtonAction.fromPrefValue(
            preferences.read(MEDIA_BUTTON_ACTION),
        )

    var isBatteryOptimizationAsked: Boolean by pref(BATTERY_OPTIMIZATION_ASKED)

    var isMicrophonePermissionAsked: Boolean by pref(MICROPHONE_PERMISSION_ASKED)

    var isNotificationPermissionAsked: Boolean by pref(NOTIFICATION_PERMISSION_ASKED)

    companion object {
        val INPUT_METHOD = Pref("audioInputMethod", ARRAY_INPUT_METHOD_VOICE)
        const val ARRAY_INPUT_METHOD_VOICE = "voiceActivity"
        const val ARRAY_INPUT_METHOD_PTT = "ptt"
        const val ARRAY_INPUT_METHOD_CONTINUOUS = "continuous"
        val ARRAY_INPUT_METHODS: Set<String> =
            setOf(ARRAY_INPUT_METHOD_VOICE, ARRAY_INPUT_METHOD_PTT, ARRAY_INPUT_METHOD_CONTINUOUS)


        val THRESHOLD = Pref("vadThreshold", 50)

        val TALK_KEY = Pref("talkKey", -1)

        val HOT_CORNER = Pref("hotCorner", ARRAY_HOT_CORNER_NONE)
        const val ARRAY_HOT_CORNER_NONE = "none"
        const val ARRAY_HOT_CORNER_TOP_LEFT = "topLeft"
        const val ARRAY_HOT_CORNER_BOTTOM_LEFT = "bottomLeft"
        const val ARRAY_HOT_CORNER_TOP_RIGHT = "topRight"
        const val ARRAY_HOT_CORNER_BOTTOM_RIGHT = "bottomRight"

        val PUSH_BUTTON_HIDE = Pref("hidePtt", false)

        val PTT_TOGGLE = Pref("togglePtt", false)

        val ALLOW_EXTERNAL_PTT = Pref("allow_external_ptt", false)

        val INPUT_RATE = Pref("input_quality", "48000")

        val INPUT_QUALITY = Pref("input_bitrate", 40000)

        val AMPLITUDE_BOOST = Pref("inputVolume", 100)

        val CHAT_NOTIFY = Pref("chatNotify", true)

        val USE_TTS = Pref("useTts", true)

        val SHORT_TTS_MESSAGES = Pref("shortTtsMessages", false)

        val AUTO_RECONNECT = Pref("autoReconnect", true)

        val THEME = Pref("theme", "system")
        val DYNAMIC_COLORS = Pref("dynamic_colors", false)
        val LANGUAGE = Pref("language", "system")

        val PTT_BUTTON_HEIGHT = Pref("pttButtonHeight", 150)

        /** Database id of the default certificate; see [se.lublin.mumla.db.DatabaseCertificate]. */
        val CERT_ID = Pref("certificateId", NO_CERTIFICATE)

        val DEFAULT_USERNAME = Pref("defaultUsername", "Mumla_User")

        val FORCE_TCP = Pref("forceTcp", false)

        val USE_TOR = Pref("useTor", false)

        val MUTED = Pref("muted", false)

        val DEAFENED = Pref("deafened", false)

        val FIRST_RUN = Pref("firstRun", true)

        val MARKDOWN = Pref("markdown_messages", true)
        val LOAD_IMAGES = Pref("load_images", true)

        val FRAMES_PER_PACKET = Pref("audio_per_packet", "2")

        val HALF_DUPLEX = Pref("half_duplex", false)

        /** Written by the audio chooser; see [preferredAudioDevice]. */
        val AUDIO_DEVICE: Pref<String?> = Pref("audio_device", null)

        /** The speaker/earpiece list and the handset switch before it; migrated and removed on first read. */
        private const val LEGACY_PREF_DEFAULT_OUTPUT = "default_output"
        private const val LEGACY_DEFAULT_OUTPUT_EARPIECE = "earpiece"
        private const val LEGACY_PREF_HANDSET_MODE = "handset_mode"

        val PTT_SOUND = Pref("ptt_sound", false)

        val PREPROCESSOR_ENABLED = Pref("preprocessor_enabled", true)

        val NOISE_SUPPRESSION_METHOD: Pref<String?> = Pref("noise_suppression_method", null)

        /** Stored as a string because it is a ListPreference; -15/-25/-35. */
        val SPEEX_NOISE_SUPPRESS_DB = Pref("speex_noise_suppress_db", -25)

        /**
         * One of [VadMode.preferenceValue]. Switching modes keeps `vadThreshold` on disk, so going
         * back to amplitude restores the old calibration.
         */
        val VAD_MODE = Pref("vad_mode", "adaptive")

        /** Percent; the fraction of the measured speech-to-floor gap a frame has to clear. */
        val VAD_SENSITIVITY = Pref("vad_sensitivity", 65)

        val VAD_ADAPTIVE_FLOOR = Pref("vad_adaptive_floor", true)

        /** dB **below** full scale, so the slider can stay positive. 45 means -45 dBFS. */
        val VAD_FLOOR_DB = Pref("vad_floor_db", 45)

        /** Percent, [VadMode.PROBABILITY] only. */
        val VAD_START = Pref("vad_start", 60)
        val VAD_STOP = Pref("vad_stop", 30)

        val VAD_HOLD_MS = Pref("vad_hold_ms", 250)

        const val MAX_VAD_HOLD_MS = 2000

        /** The transient guard, in 10 ms frames; the library default is one. */
        val VAD_ONSET_FRAMES = Pref("vad_onset_frames", 2)

        /** Five frames (50 ms) already clips a word's beginning audibly. */
        const val MAX_VAD_ONSET_FRAMES = 5

        val ANDROID_NOISE_SUPPRESSOR = Pref("android_noise_suppressor", false)
        val ANDROID_AGC = Pref("android_agc", false)


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

        val STAY_AWAKE = Pref("stay_awake", false)

        val SHOW_USER_COUNT = Pref("show_user_count", false)

        val START_UP_IN_PINNED_MODE = Pref("startUpInPinnedMode", false)

        val NEWS_SHOWN_VERSIONS: Pref<Set<String>> = Pref("newsShownVersions", emptySet())

        /** Use a connected Bluetooth headset automatically while connected. */
        val BLUETOOTH_SCO = Pref("pref_bluetooth_sco", true)

        /** Headset / AVRCP media button behavior, one of [MediaButtonAction.prefValue]. */
        val MEDIA_BUTTON_ACTION = Pref("media_button_action", "auto")

        /** The general settings row for the battery-optimization exemption; stores nothing. */
        const val PREF_BATTERY_OPTIMIZATION = "battery_optimization"

        /** True once the battery-optimization exemption has been offered. */
        val BATTERY_OPTIMIZATION_ASKED = Pref("battery_optimization_asked", false)

        /** True once the microphone permission has been requested. */
        val MICROPHONE_PERMISSION_ASKED = Pref("microphone_permission_asked", false)

        /** True once the notification permission has been requested. */
        val NOTIFICATION_PERMISSION_ASKED = Pref("notification_permission_asked", false)

        private const val NO_CERTIFICATE = -1L
        private const val PERCENT = 100

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
