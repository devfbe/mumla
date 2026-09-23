package se.lublin.humla.audio

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLog
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protocol.AudioHandler

@RunWith(RobolectricTestRunner::class)
class AudioOutputTest {

    private val user = User(SESSION, "bob")

    private val listener = object : AudioOutput.AudioOutputListener {
        override fun onUserTalkStateUpdated(user: User) = Unit
        override fun getUser(session: Int): User? = if (session == SESSION) user else null
    }

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

    private fun startedOutput(): AudioOutput {
        val o = AudioOutput(listener, null)
        output = o
        o.startPlaying(AudioManager.STREAM_MUSIC)
        awaitTrue("the playback thread to start") { o.isPlaying() }
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
        ShadowLog.clear()

        startedOutput()

        assertThat(ShadowLog.getLogsForTag(AudioOutput::class.java.name).map { it.msg })
            .contains("Mixing 5760 samples per write into a 11520-byte track (system minimum 11520 bytes)")
    }

    @Test
    fun `an unusable system minimum is an initialization error`() {
        ShadowAudioTrack.setMinBufferSize(0) // getMinBufferSize answers ERROR for it

        val o = AudioOutput(listener, null)
        val thrown = runCatching { o.startPlaying(AudioManager.STREAM_MUSIC) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(se.lublin.humla.exception.AudioInitializationException::class.java)
        assertThat(o.isPlaying()).isFalse()
    }

    // --- the packet lock ------------------------------------------------------------------------

    @Test
    fun `a speech that cannot be built does not leave the packet lock held`() {
        val o = startedOutput()

        // UDPPing has no decoder: building the speech throws inside the critical section. On a
        // thread of its own, because the lock is reentrant -- the test thread would get it back
        // no matter what was leaked.
        val producer = Thread { o.queueVoiceData(voicePacket(), HumlaUDPMessageType.UDPPing) }
        producer.start()
        producer.join(TimeUnit.SECONDS.toMillis(5))
        assertThat(producer.isAlive).isFalse()

        // stopPlaying takes the packet lock after the playback thread has gone. A lock the dead
        // producer still owns parks it forever.
        runBounded("stopPlaying after the failed packet") { o.stopPlaying() }
        output = null
        assertThat(o.isPlaying()).isFalse()
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun voicePacket(): ByteArray {
        val pb = PacketBuffer.allocate(16)
        pb.append(0) // header byte: type and target
        pb.writeLong(SESSION.toLong())
        pb.writeLong(0) // sequence
        pb.append(byteArrayOf(0x01, 0x02), 2)
        val length = pb.size()
        pb.rewind()
        return pb.dataBlock(length)
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
        assertWithMessage("$what did not return within 5 s -- a lock is still held").that(t.isAlive).isFalse()
        failure?.let { throw it }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertWithMessage("timed out waiting for $what").that(System.nanoTime() < deadline).isTrue()
            Thread.sleep(5)
        }
    }

    private companion object {
        const val SESSION = 7
        const val BYTES_PER_SAMPLE = 2

        /** What the device in the bug report answers for 48 kHz mono 16-bit, in bytes. */
        const val DEVICE_MIN_BUFFER_BYTES = 11520
    }
}
