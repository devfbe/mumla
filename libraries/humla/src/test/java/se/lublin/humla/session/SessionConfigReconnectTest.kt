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
import se.lublin.humla.Constants
import se.lublin.humla.audio.capture.VadConfig
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

        /** Complete: [SessionConfigTest] checks it against the declared fields. */
        internal val CASES: Map<String, Pair<Boolean, (SessionConfig) -> SessionConfig>> = mapOf(
            "server" to (true to { c -> c.copy(server = SERVER) }),
            "clientName" to (true to { c -> c.copy(clientName = "client") }),
            "certificate" to (true to { c -> c.copy(certificate = ClientCertificate(byteArrayOf(1, 2, 3))) }),
            "trustStorePath" to (true to { c -> c.copy(trustStorePath = "/store") }),
            "trustStorePassword" to (true to { c -> c.copy(trustStorePassword = "pw") }),
            "trustStoreFormat" to (true to { c -> c.copy(trustStoreFormat = "BKS") }),
            "forceTcp" to (true to { c -> c.copy(forceTcp = true) }),
            "useTor" to (true to { c -> c.copy(useTor = true) }),
            "localMuteHistory" to (true to { c -> c.copy(localMuteHistory = listOf(7)) }),
            "localIgnoreHistory" to (true to { c -> c.copy(localIgnoreHistory = listOf(8)) }),
            "localVolumes" to (false to { c -> c.copy(localVolumes = mapOf("cert:abc" to 0.5f)) }),
            "autoReconnect" to (false to { c -> c.copy(autoReconnect = true) }),
            "accessTokens" to (false to { c -> c.copy(accessTokens = listOf("token")) }),
            "transmitMode" to (false to { c -> c.copy(transmitMode = Constants.TRANSMIT_CONTINUOUS) }),
            "vadConfig" to (false to { c -> c.copy(vadConfig = VadConfig.amplitude(0.25f)) }),
            "amplitudeBoost" to (false to { c -> c.copy(amplitudeBoost = 1.5f) }),
            "inputSampleRate" to (false to { c -> c.copy(inputSampleRate = 16_000) }),
            "inputQuality" to (false to { c -> c.copy(inputQuality = 24_000) }),
            "framesPerPacket" to (false to { c -> c.copy(framesPerPacket = 4) }),
            "audioSource" to (false to { c -> c.copy(audioSource = 7) }),
            "audioStream" to (false to { c -> c.copy(audioStream = 0) }),
            "halfDuplex" to (false to { c -> c.copy(halfDuplex = true) }),
            "preprocessorEnabled" to (false to { c -> c.copy(preprocessorEnabled = true) }),
            "noiseSuppressionMethod" to (false to { c -> c.copy(noiseSuppressionMethod = "rnnoise") }),
            "speexNoiseSuppressDb" to (false to { c -> c.copy(speexNoiseSuppressDb = -40) }),
            "androidNoiseSuppressor" to (false to { c -> c.copy(androidNoiseSuppressor = true) }),
            "androidAgc" to (false to { c -> c.copy(androidAgc = true) }),
            "echoCancellationOverrides" to (false to { c ->
                c.copy(echoCancellationOverrides = mapOf(AudioDeviceCategory.SPEAKER to false))
            }),
            "preferredAudioDevice" to (false to { c ->
                c.copy(preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
            }),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> reconnect {1}")
        fun cases(): List<Array<Any>> = CASES.map { (field, case) -> arrayOf(field, case.first, case.second) }
    }
}

class SessionConfigTest {
    @Test
    fun everyFieldIsClassifiedForReconnect() {
        val fields = SessionConfig::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }

        assertThat(fields).containsExactlyElementsIn(SessionConfigReconnectTest.CASES.keys)
    }

    @Test
    fun certificatesAreComparedByContent() {
        val a = SessionConfig(certificate = ClientCertificate(byteArrayOf(1, 2, 3), "pw"))
        val b = SessionConfig(certificate = ClientCertificate(byteArrayOf(1, 2, 3), "pw"))

        assertThat(b).isEqualTo(a)
        assertThat(b.needsReconnectAfter(a)).isFalse()
    }
}
