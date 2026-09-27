/*
 * Copyright (C) 2026 The Mumla Authors
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

@file:Suppress("DEPRECATION") // se.lublin.humla.util.Constants is deprecated; TRANSMIT_* has no successor yet.

package se.lublin.humla.session

import android.media.AudioManager
import android.media.MediaRecorder
import se.lublin.humla.audio.capture.SpeexPreprocessor
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.model.Server
import se.lublin.humla.util.Constants

/**
 * Everything a client configures a session with, handed to `HumlaService.configure` as a whole.
 * The connection fields take effect on the next connection; everything else applies live.
 */
data class SessionConfig(
    val server: Server? = null,
    /** Sent to the server as the client's release name. */
    val clientName: String = "",
    val certificate: ClientCertificate? = null,
    /** An optional trust store for CA certificates. */
    val trustStorePath: String? = null,
    val trustStorePassword: String? = null,
    val trustStoreFormat: String? = null,
    val forceTcp: Boolean = false,
    /** Proxy through a local Orbot; implies TCP for voice. */
    val useTor: Boolean = false,
    /** User ids muted locally on connection. */
    val localMuteHistory: List<Int> = emptyList(),
    /** User ids ignored locally on connection. */
    val localIgnoreHistory: List<Int> = emptyList(),
    /**
     * Stored local volumes by `LocalVolumes.keyOf`, read when a connection starts. Not a
     * connection field: the session keeps its own copy current through `setLocalVolume`.
     */
    val localVolumes: Map<String, Float> = emptyMap(),

    val autoReconnect: Boolean = false,
    /** Sent to the server right away while connected. */
    val accessTokens: List<String> = emptyList(),
    /** One of `Constants.TRANSMIT_*`. */
    val transmitMode: Int = Constants.TRANSMIT_VOICE_ACTIVITY,
    val vadConfig: VadConfig = VadConfig.DEFAULT,
    val amplitudeBoost: Float = 1.0f,
    val inputSampleRate: Int = 48_000,
    /** Target encoder bitrate in bps. */
    val inputQuality: Int = 40_000,
    val framesPerPacket: Int = 2,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val audioStream: Int = AudioManager.STREAM_MUSIC,
    /** Only honoured in push-to-talk. */
    val halfDuplex: Boolean = false,
    val preprocessorEnabled: Boolean = false,
    val noiseSuppressionMethod: String = "none",
    /** One of [SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB]. */
    val speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
    val androidNoiseSuppressor: Boolean = false,
    val androidAgc: Boolean = false,
    /** The user's echo-cancellation choices; a category without an entry keeps its default. */
    val echoCancellationOverrides: Map<AudioDeviceCategory, Boolean> = emptyMap(),
    /** The audio device the user saved; null routes automatically. */
    val preferredAudioDevice: PreferredAudioDevice? = null,
) {
    /** Whether going from [previous] to this config only takes effect after a reconnect. */
    fun needsReconnectAfter(previous: SessionConfig): Boolean = connectionFields() != previous.connectionFields()

    private fun connectionFields(): List<Any?> = listOf(
        server, clientName, certificate, trustStorePath, trustStorePassword, trustStoreFormat,
        forceTcp, useTor, localMuteHistory, localIgnoreHistory,
    )
}

/** A PKCS#12 client certificate; equal by content. */
class ClientCertificate(val pkcs12: ByteArray, val password: String? = null) {
    override fun equals(other: Any?): Boolean =
        other is ClientCertificate && pkcs12.contentEquals(other.pkcs12) && password == other.password

    override fun hashCode(): Int = 31 * pkcs12.contentHashCode() + password.hashCode()
}
