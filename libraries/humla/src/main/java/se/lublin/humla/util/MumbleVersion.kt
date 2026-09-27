/*
 * Copyright (C) 2026 The Mumla authors
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
package se.lublin.humla.util

import se.lublin.humla.protobuf.Mumble

/**
 * Mumble version numbers. The legacy format (v1) is `0xMMMMmmpp`; the current one (v2) is a
 * 64-bit value with 16 bits each for major, minor and patch at bits 48, 32 and 16.
 */
internal object MumbleVersion {
    private const val OFFSET_MAJOR = 48
    private const val OFFSET_MINOR = 32
    private const val OFFSET_PATCH = 16
    private const val FIELD_MASK = 0xFFFFL
    private const val LEGACY_MAX_MINOR_PATCH = 0xFFL
    private const val LEGACY_OFFSET_MAJOR = 16
    private const val LEGACY_OFFSET_MINOR = 8

    /**
     * The version this client advertises. From 1.5.0 on, servers of 1.5.0 or newer send voice and
     * UDP pings in the protobuf format (see [se.lublin.humla.net.UdpProtocol]).
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

    /** The version in [msg] in the v2 format, preferring v2 when present; 0 if unknown. */
    fun v2Of(msg: Mumble.Version): Long = when {
        msg.hasVersionV2() -> msg.versionV2
        msg.hasVersionV1() -> fromLegacy(msg.versionV1)
        else -> 0L
    }

    /** The version in [msg] as "major.minor.patch", preferring v2 when present; null if unknown. */
    fun displayOf(msg: Mumble.Version): String? {
        val v2 = when {
            msg.hasVersionV2() -> msg.versionV2
            msg.hasVersionV1() -> fromLegacy(msg.versionV1)
            else -> return null
        }
        val major = (v2 ushr OFFSET_MAJOR) and FIELD_MASK
        val minor = (v2 ushr OFFSET_MINOR) and FIELD_MASK
        val patch = (v2 ushr OFFSET_PATCH) and FIELD_MASK
        return "$major.$minor.$patch"
    }

    private fun fromLegacy(v1: Int): Long = v2(
        v1 ushr LEGACY_OFFSET_MAJOR,
        (v1 ushr LEGACY_OFFSET_MINOR) and LEGACY_MAX_MINOR_PATCH.toInt(),
        v1 and LEGACY_MAX_MINOR_PATCH.toInt(),
    )

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
