package se.lublin.mumla

/**
 * What a headset / Bluetooth media button (play-pause, headset hook) does while connected.
 * The [prefValue] strings are the entry values of `@array/mediaButtonActionValues`.
 */
enum class MediaButtonAction(val prefValue: String) {
    /** Media buttons are ignored. */
    NONE("none"),

    /** Toggle push-to-talk in PTT mode, toggle self-mute in every other transmit mode. */
    AUTO("auto"),

    /** Always toggle self-mute. */
    MUTE("mute");

    companion object {
        @JvmStatic
        fun fromPrefValue(value: String?): MediaButtonAction =
            entries.firstOrNull { it.prefValue == value } ?: AUTO
    }
}
