package se.lublin.humla.audio

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioTrack
import se.lublin.humla.audio.capture.CaptureState
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.fakes.FakeCaptureSource
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.model.UserState
import se.lublin.humla.testutil.NoopEncodeListener
import se.lublin.humla.testutil.NoopOutputListener
import se.lublin.humla.testutil.SilentLogger
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a session's capture reports reaches the pipeline's owner. The microphone is a
 * [FakeCaptureSource]; no codec (so no native Opus encoder) and no noise suppression or echo
 * cancellation (so no native preprocessor), which leaves a pipeline the JVM can run.
 */
@RunWith(RobolectricTestRunner::class)
class AudioHandlerCaptureStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val states = CopyOnWriteArrayList<CaptureState>()
    private var handler: AudioHandler? = null

    private val params = AudioSessionParams(UserState(1, "me", 0), -1, null, 0, ContinuousInputMode())
    private val config = AudioConfig(
        PipelineSettings(noiseSuppression = NoiseSuppressionMode.NONE),
        echoCancellation = EchoCancellationMode.NONE,
    )

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioTrack.setMinBufferSize(MIN_TRACK_BUFFER_BYTES)
    }

    @After
    fun tearDown() {
        handler?.shutdown()
    }

    private fun startedHandler(source: FakeCaptureSource): AudioHandler =
        AudioHandler(
            AudioHost(app, SilentLogger, NoopEncodeListener, NoopOutputListener),
            config,
            params,
            onCaptureState = { states += it },
            captureFactory = { source },
        ).also {
            handler = it
            it.start()
        }

    @Test
    fun thePlatformSilencingTheMicrophoneAndReleasingItReachesTheOwner() {
        val source = FakeCaptureSource()
        startedHandler(source)
        val platform = checkNotNull(source.silenceListener) { "capture registered no silence listener" }

        platform(true)
        platform(false)

        assertThat(states).containsExactly(CaptureState.Silenced, CaptureState.Active).inOrder()
    }

    @Test
    fun aCaptureThatCannotStartReachesTheOwnerAsAnError() {
        startedHandler(FakeCaptureSource(failStart = true))

        awaitUntil(description = "the capture error") { states.isNotEmpty() }
        assertThat(states.single()).isInstanceOf(CaptureState.Error::class.java)
    }

    private companion object {
        const val MIN_TRACK_BUFFER_BYTES = 3840
    }
}
