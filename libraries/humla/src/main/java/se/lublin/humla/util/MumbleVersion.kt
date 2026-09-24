package se.lublin.humla.util

import se.lublin.humla.Constants
import se.lublin.humla.protobuf.Mumble

/**
 * Mumble version numbers. The legacy format (v1) is `0xMMMMmmpp`; the current one (v2) is a
 * 64-bit value with 16 bits each for major, minor and patch at bits 48, 32 and 16.
 */
object MumbleVersion {
    private const val OFFSET_MAJOR = 48
    private const val OFFSET_MINOR = 32
    private const val OFFSET_PATCH = 16
    private const val FIELD_MASK = 0xFFFFL
    private const val LEGACY_MAX_MINOR_PATCH = 0xFFL
    private const val LEGACY_OFFSET_MAJOR = 16
    private const val LEGACY_OFFSET_MINOR = 8

    /**
     * The version this client advertises. It must stay below 1.5.0 as long as voice uses the legacy
     * UDP format: servers pick the protobuf format for clients reporting 1.5.0 or newer.
     */
    val CLIENT_V2: Long = v2(Constants.PROTOCOL_MAJOR, Constants.PROTOCOL_MINOR, Constants.PROTOCOL_PATCH)
    val CLIENT_LEGACY: Int = toLegacy(CLIENT_V2)

    fun v2(major: Int, minor: Int, patch: Int): Long =
        ((major.toLong() and FIELD_MASK) shl OFFSET_MAJOR) or
            ((minor.toLong() and FIELD_MASK) shl OFFSET_MINOR) or
            ((patch.toLong() and FIELD_MASK) shl OFFSET_PATCH)

    /** [v2] in the legacy format; minor and patch above 255 are clamped, as Mumble does. */
    fun toLegacy(v2: Long): Int {
        val major = (v2 ushr OFFSET_MAJOR) and FIELD_MASK
        val minor = minOf((v2 ushr OFFSET_MINOR) and FIELD_MASK, LEGACY_MAX_MINOR_PATCH)
        val patch = minOf((v2 ushr OFFSET_PATCH) and FIELD_MASK, LEGACY_MAX_MINOR_PATCH)
        return ((major shl LEGACY_OFFSET_MAJOR) or (minor shl LEGACY_OFFSET_MINOR) or patch).toInt()
    }

    /** The version in [msg] in the legacy format, preferring v2 when present; 0 if unknown. */
    fun legacyOf(msg: Mumble.Version): Int = when {
        msg.hasVersionV2() -> toLegacy(msg.versionV2)
        msg.hasVersionV1() -> msg.versionV1
        else -> 0
    }

    /** The Version message this client sends, with both version formats. */
    fun clientVersion(release: String, os: String, osVersion: String): Mumble.Version =
        Mumble.Version.newBuilder()
            .setVersionV1(CLIENT_LEGACY)
            .setVersionV2(CLIENT_V2)
            .setRelease(release)
            .setOs(os)
            .setOsVersion(osVersion)
            .build()
}
