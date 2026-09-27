package se.lublin.humla.audio

import android.media.AudioAttributes
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAudioTrack
import se.lublin.humla.exception.NativeAudioException
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.VoicePacket
import se.lublin.humla.testutil.FakeOpusDecoder
import se.lublin.humla.testutil.LogRecorder
import se.lublin.humla.testutil.NoopOutputListener
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AudioOutputTest {
    @get:Rule
    val log = LogRecorder()

    private var output: AudioOutput? = null

    @Before
    fun setUp() {
        ShadowAudioTrack.setMinBufferSize(DEVICE_MIN_BUFFER_BYTES)
    }

    @After
    fun tearDown() {
        // Bounded: a test that failed by leaving the packet lock held must not hang the run here.
        output?.let { o -> runBounded("tearDown stopPlaying") { o.stopPlaying() } }
    }

    private fun startedOutput(
        factory: AudioOutput.SpeechFactory =
            AudioOutput.SpeechFactory { u, n, l, a -> AudioOutputSpeech(u, n, l, averageAvailable = a) },
    ): AudioOutput {
        val o = AudioOutput(NoopOutputListener, null, factory)
        output = o
        o.startPlaying(AudioManager.STREAM_MUSIC)
        awaitUntil(description = "the playback thread to start") { o.isPlaying }
        return o
    }

    // --- buffer size: AudioTrack.getMinBufferSize answers in bytes -----------------------------

    @Test
    fun `the track buffer is never below the system minimum, which is in bytes`() {
        val track = startedOutput().playbackTrack()!!

        // One mono 16-bit frame is two bytes.
        assertThat(track.bufferSizeInFrames * BYTES_PER_SAMPLE).isAtLeast(DEVICE_MIN_BUFFER_BYTES)
    }

    @Test
    fun `the mix is sized in samples and capped at twelve frames`() {
        assertThat(AudioOutput.playbackBuffer(11520))
            .isEqualTo(AudioOutput.PlaybackBuffer(mixSamples = 5760, trackBytes = 11520))
        // A small minimum mixes less than twelve frames, but never more than the minimum holds.
        assertThat(AudioOutput.playbackBuffer(3840))
            .isEqualTo(AudioOutput.PlaybackBuffer(mixSamples = 1920, trackBytes = 3840))
        // A large minimum is honoured in full; the mix stays at twelve frames.
        assertThat(AudioOutput.playbackBuffer(40000))
            .isEqualTo(AudioOutput.PlaybackBuffer(mixSamples = AudioHandler.FRAME_SIZE * 12, trackBytes = 40000))
    }

    @Test
    fun `the log line names both sizes with their units`() {
        log.clear()

        startedOutput()

        assertThat(log.messages(AudioOutput::class.java.name))
            .contains("Mixing 5760 samples per write into a 11520-byte track (system minimum 11520 bytes)")
    }

    @Test
    fun `an unusable system minimum is an initialization error`() {
        ShadowAudioTrack.setMinBufferSize(0) // getMinBufferSize answers ERROR for it

        val o = AudioOutput(NoopOutputListener, null)
        val thrown = runCatching { o.startPlaying(AudioManager.STREAM_MUSIC) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(se.lublin.humla.exception.AudioInitializationException::class.java)
        assertThat(o.isPlaying).isFalse()
    }

    // --- track attributes ------------------------------------------------------------------------

    /** The voice-call stream becomes voice communication, which follows the communication device. */
    @Test
    fun `the voice call stream plays as voice communication speech`() {
        val attributes = AudioOutput.playbackAttributes(AudioManager.STREAM_VOICE_CALL)

        assertThat(attributes.usage).isEqualTo(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        assertThat(attributes.contentType).isEqualTo(AudioAttributes.CONTENT_TYPE_SPEECH)
        assertThat(attributes.volumeControlStream).isEqualTo(AudioManager.STREAM_VOICE_CALL)
    }

    @Test
    fun `any other stream keeps its legacy stream type`() {
        val attributes = AudioOutput.playbackAttributes(AudioManager.STREAM_MUSIC)

        assertThat(attributes.usage).isEqualTo(AudioAttributes.USAGE_MEDIA)
        assertThat(attributes.volumeControlStream).isEqualTo(AudioManager.STREAM_MUSIC)
    }

    // --- start and stop -------------------------------------------------------------------------

    @Test
    fun `stopping straight after starting stops the playback thread`() {
        // AudioHandler.shutdown can follow initialize before the playback thread has run a line.
        repeat(20) { round ->
            val o = AudioOutput(NoopOutputListener, null)
            val thread = o.startPlaying(AudioManager.STREAM_MUSIC)!!

            runBounded("stopPlaying in round $round") { o.stopPlaying() }
            thread.join(TimeUnit.SECONDS.toMillis(5))

            assertWithMessage("playback thread alive after stop, round $round").that(thread.isAlive).isFalse()
            assertThat(o.isPlaying).isFalse()
            assertThat(o.playbackTrack()).isNull()
        }
    }

    // --- the packet lock ------------------------------------------------------------------------

    @Test
    fun `a speech that cannot be built does not leave the packet lock held`() {
        val o = startedOutput { _, _, _, _ -> throw NativeAudioException("no decoder") }

        // Building the speech throws inside the critical section. On a thread of its own, because
        // the lock is reentrant -- the test thread would get it back no matter what was leaked.
        val producer = Thread { o.queueVoiceData(voicePacket()) }
        producer.start()
        producer.join(TimeUnit.SECONDS.toMillis(5))
        assertThat(producer.isAlive).isFalse()

        // stopPlaying takes the packet lock after the playback thread has gone. A lock the dead
        // producer still owns parks it forever.
        runBounded("stopPlaying after the failed packet") { o.stopPlaying() }
        output = null
        assertThat(o.isPlaying).isFalse()
    }

    @Test
    fun `legacy codec packets are dropped without a decoder and logged once`() {
        var built = 0
        val o = startedOutput { u, samples, l, _ ->
            built++
            AudioOutputSpeech(u, samples, l, FakeOpusDecoder(), FakeJitter())
        }
        log.clear()

        for (type in listOf(
            HumlaUDPMessageType.UDPVoiceCELTAlpha,
            HumlaUDPMessageType.UDPVoiceSpeex,
            HumlaUDPMessageType.UDPVoiceCELTBeta,
            HumlaUDPMessageType.UDPVoiceCELTAlpha,
        )) {
            o.queueVoiceData(voicePacket(type))
        }

        assertThat(built).isEqualTo(0)
        assertThat(log.messages(AudioOutput::class.java.name).filter { it.startsWith("Dropping") })
            .hasSize(1)

        // The same talker's Opus stream still plays.
        o.queueVoiceData(voicePacket())
        assertThat(built).isEqualTo(1)
    }

    // --- helpers --------------------------------------------------------------------------------

    /** One decoded packet of [codec] from [session] with a two-byte opus frame. */
    private fun voicePacket(codec: HumlaUDPMessageType = HumlaUDPMessageType.UDPVoiceOpus, session: Int = SESSION) =
        VoicePacket().apply {
            this.codec = codec
            this.session = session
            data = byteArrayOf(0x01, 0x02)
            opusOffset = 0
            opusLength = 2
        }

    private fun runBounded(what: String, block: () -> Unit) {
        var failure: Throwable? = null
        val t = Thread {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        t.isDaemon = true
        t.start()
        t.join(TimeUnit.SECONDS.toMillis(5))
        assertWithMessage("$what did not return within 5 s").that(t.isAlive).isFalse()
        failure?.let { throw it }
    }

    private companion object {
        const val SESSION = 7
        const val BYTES_PER_SAMPLE = 2

        /** What the device in the bug report answers for 48 kHz mono 16-bit, in bytes. */
        const val DEVICE_MIN_BUFFER_BYTES = 11520
    }
}
