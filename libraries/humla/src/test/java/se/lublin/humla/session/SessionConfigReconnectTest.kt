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

package se.lublin.humla.session

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import se.lublin.humla.audio.AudioSettings
import se.lublin.humla.audio.PipelineSettings
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.model.Server
import java.lang.reflect.Modifier

/** Whether changing one field of a [SessionConfig] needs a reconnect to take effect. */
@RunWith(Parameterized::class)
class SessionConfigReconnectTest(
    private val field: String,
    private val reconnect: Boolean,
    private val change: (SessionConfig) -> SessionConfig,
) {
    @Test
    fun aChangeReportsWhetherItNeedsAReconnect() {
        val changed = change(BASE)

        assertThat(changed).isNotEqualTo(BASE)
        assertThat(changed.needsReconnectAfter(BASE)).isEqualTo(reconnect)
    }

    @Test
    fun anUnchangedConfigNeverNeedsAReconnect() {
        assertThat(change(BASE).needsReconnectAfter(change(BASE))).isFalse()
    }

    companion object {
        internal val BASE = SessionConfig()
        private val SERVER = Server(-1, "test", "127.0.0.1", 64738, "me", "")

        private fun connection(change: ConnectionConfig.() -> ConnectionConfig) =
            true to { c: SessionConfig -> c.copy(connection = c.connection.change()) }

        private fun audio(change: AudioSettings.() -> AudioSettings) =
            false to { c: SessionConfig -> c.copy(audio = c.audio.change()) }

        private fun pipeline(change: PipelineSettings.() -> PipelineSettings) =
            audio { copy(pipeline = pipeline.change()) }

        /** Complete: [SessionConfigTest] checks it against the declared fields, nested ones by path. */
        internal val CASES: Map<String, Pair<Boolean, (SessionConfig) -> SessionConfig>> = mapOf(
            "connection.server" to connection { copy(server = SERVER) },
            "connection.clientName" to connection { copy(clientName = "client") },
            "connection.certificate" to connection { copy(certificate = ClientCertificate(byteArrayOf(1, 2, 3))) },
            "connection.trustStorePath" to connection { copy(trustStorePath = "/store") },
            "connection.trustStorePassword" to connection { copy(trustStorePassword = "pw") },
            "connection.trustStoreFormat" to connection { copy(trustStoreFormat = "BKS") },
            "connection.forceTcp" to connection { copy(forceTcp = true) },
            "connection.useTor" to connection { copy(useTor = true) },
            "connection.localMuteHistory" to connection { copy(localMuteHistory = listOf(7)) },
            "connection.localIgnoreHistory" to connection { copy(localIgnoreHistory = listOf(8)) },
            "localVolumes" to (false to { c -> c.copy(localVolumes = mapOf("cert:abc" to 0.5f)) }),
            "autoReconnect" to (false to { c -> c.copy(autoReconnect = true) }),
            "accessTokens" to (false to { c -> c.copy(accessTokens = listOf("token")) }),
            "audio.transmitMode" to audio { copy(transmitMode = TransmitMode.CONTINUOUS) },
            "audio.vad" to audio { copy(vad = VadConfig.amplitude(0.25f)) },
            "audio.halfDuplex" to audio { copy(halfDuplex = true) },
            "audio.echoCancellationOverrides" to audio {
                copy(echoCancellationOverrides = mapOf(AudioDeviceCategory.SPEAKER to false))
            },
            "audio.preferredDevice" to audio {
                copy(preferredDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
            },
            "audio.pipeline.audioStream" to pipeline { copy(audioStream = 0) },
            "audio.pipeline.audioSource" to pipeline { copy(audioSource = 7) },
            "audio.pipeline.inputSampleRate" to pipeline { copy(inputSampleRate = 16_000) },
            "audio.pipeline.bitrate" to pipeline { copy(bitrate = 24_000) },
            "audio.pipeline.framesPerPacket" to pipeline { copy(framesPerPacket = 4) },
            "audio.pipeline.amplitudeBoost" to pipeline { copy(amplitudeBoost = 1.5f) },
            "audio.pipeline.noiseSuppression" to pipeline { copy(noiseSuppression = NoiseSuppressionMode.RNNOISE) },
            "audio.pipeline.speexNoiseSuppressDb" to pipeline { copy(speexNoiseSuppressDb = -40) },
            "audio.pipeline.androidEffects" to pipeline { copy(androidEffects = AndroidAudioEffects(true, true)) },
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> reconnect {1}")
        fun cases(): List<Array<Any>> = CASES.map { (field, case) -> arrayOf(field, case.first, case.second) }
    }
}

class SessionConfigTest {
    /** The fields of [type], nested config groups descended into and named by path. */
    private fun fieldPaths(type: Class<*>, prefix: String = ""): List<String> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.flatMap { field ->
            val path = prefix + field.name
            if (field.type in GROUPS) fieldPaths(field.type, "$path.") else listOf(path)
        }

    @Test
    fun everyFieldIsClassifiedForReconnect() {
        assertThat(fieldPaths(SessionConfig::class.java))
            .containsExactlyElementsIn(SessionConfigReconnectTest.CASES.keys)
    }

    @Test
    fun certificatesAreComparedByContent() {
        val a = SessionConfig(ConnectionConfig(certificate = ClientCertificate(byteArrayOf(1, 2, 3), "pw")))
        val b = SessionConfig(ConnectionConfig(certificate = ClientCertificate(byteArrayOf(1, 2, 3), "pw")))

        assertThat(b).isEqualTo(a)
        assertThat(b.needsReconnectAfter(a)).isFalse()
    }

    private companion object {
        val GROUPS = setOf(ConnectionConfig::class.java, AudioSettings::class.java, PipelineSettings::class.java)
    }
}
