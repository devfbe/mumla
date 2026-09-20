package se.lublin.humla.session

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.testutil.SilentLogger
import android.content.Context

/**
 * The real factory needs a microphone, so what a JVM test can reach is the part of
 * AudioHandler's constructor that runs before the RECORD_AUDIO check: the legacy echo-cancellation
 * method, which puts the AudioManager into communication mode when it is "system". That is enough
 * to pin *which* of [AudioConfig]'s two echo-cancellation fields the legacy builder is handed - the
 * one thing about this class that a later reader is most likely to get wrong.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultAudioHandlerFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val factory = DefaultAudioHandlerFactory()
    private val params = AudioSessionParams(
        User(1, "me"), 72_000, HumlaUDPMessageType.UDPVoiceOpus, 0, ContinuousInputMode(),
    )
    private val encodeListener = object : AudioHandler.AudioEncodeListener {
        override fun onAudioEncoded(data: ByteArray, length: Int) = Unit
        override fun onTalkingStateChanged(talking: Boolean) = Unit
    }
    private val outputListener = object : AudioOutput.AudioOutputListener {
        override fun onUserTalkStateUpdated(user: User?) = Unit
        override fun getUser(session: Int): User? = null
    }

    private fun create(config: AudioConfig) =
        factory.create(context, SilentLogger, config, params, encodeListener, outputListener)

    @Test
    fun withoutTheRecordAudioPermissionCreationFailsWithAnAudioException() {
        val e = assertThrows(AudioException::class.java) { create(AudioConfig()) }
        assertThat(e).hasMessageThat().contains("RECORD_AUDIO")
    }

    @Test
    fun theLegacyEchoCancellationMethodIsTheOneTheBuilderIsHanded() {
        assertThrows(AudioException::class.java) {
            create(AudioConfig(legacyEchoCancellationMethod = "system", echoCancellationMode = "none"))
        }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    /**
     * The other half of the same input: [AudioConfig.echoCancellationMode] is the spec 4 value
     * stream B will read, and it must not reach the legacy builder setter. With the two swapped,
     * this test sees communication mode and fails.
     */
    @Test
    fun theNewEchoCancellationModeDoesNotReachTheLegacyBuilder() {
        assertThrows(AudioException::class.java) {
            create(AudioConfig(legacyEchoCancellationMethod = "none", echoCancellationMode = "system"))
        }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }
}
