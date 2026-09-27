package se.lublin.humla.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.model.UserState
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.testutil.NoopEncodeListener
import se.lublin.humla.testutil.NoopOutputListener
import se.lublin.humla.testutil.SilentLogger

/**
 * A real pipeline needs a microphone, so what a JVM test can reach is the part of AudioHandler's
 * constructor that runs before the RECORD_AUDIO check.
 */
@RunWith(RobolectricTestRunner::class)
class AudioHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val params = AudioSessionParams(
        UserState(1, "me", 0), 72_000, HumlaUDPMessageType.UDPVoiceOpus, 0, ContinuousInputMode(),
    )
    private val host = AudioHost(
        context,
        SilentLogger,
        NoopEncodeListener,
        NoopOutputListener,
    )

    private fun create(config: AudioConfig) = DefaultAudioHandlerFactory.create(host, config, params)

    @Test
    fun withoutTheRecordAudioPermissionCreationFailsWithAnAudioException() {
        val e = assertThrows(AudioException::class.java) { create(AudioConfig()) }
        assertThat(e).hasMessageThat().contains("RECORD_AUDIO")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    /** The WebRTC canceller puts the manager into communication mode before the permission check. */
    @Test
    fun echoCancellationSwitchesToCommunicationMode() {
        assertThrows(AudioException::class.java) {
            create(AudioConfig(echoCancellation = EchoCancellationMode.WEBRTC))
        }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    @Test
    fun anAndroidEffectSwitchesToCommunicationMode() {
        val settings = PipelineSettings(androidEffects = AndroidAudioEffects(automaticGainControl = true))
        assertThrows(AudioException::class.java) { create(AudioConfig(settings)) }
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }
}
