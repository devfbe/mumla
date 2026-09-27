package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

/** Opus is the only codec: a server without it leaves voice off and says so once. */
class HumlaConnectionCodecTest {
    private val h = ConnectionHarness(forceTcp = true)

    @After
    fun tearDown() = h.close()

    private fun codecVersion(opus: Boolean) = h.receive(
        HumlaTCPMessageType.CodecVersion,
        Mumble.CodecVersion.newBuilder()
            .setAlpha(0x8000000b.toInt()).setBeta(0).setPreferAlpha(true).setOpus(opus)
            .build(),
    )

    @Test
    fun aServerWithOpusSelectsOpusAndWarnsNobody() {
        h.establish()

        codecVersion(opus = true)
        h.synchronize()

        assertThat(h.connection.serverInfo!!.opus).isTrue()
        assertThat(h.listener.warnings).isEmpty()
    }

    @Test
    fun aServerWithoutOpusSelectsNoCodecAndWarnsOnce() {
        h.establish()
        h.synchronize()

        codecVersion(opus = false)
        codecVersion(opus = false)

        assertThat(h.connection.serverInfo!!.opus).isFalse()
        assertThat(h.listener.warnings).containsExactly(ConnectionWarning.NO_OPUS)
    }

    /** The server info is replaced, not changed: a snapshot read before keeps what it said. */
    @Test
    fun aServerThatGainsOpusSelectsItAgain() {
        h.establish()
        codecVersion(opus = false)
        h.synchronize()
        val before = h.connection.serverInfo!!

        codecVersion(opus = true)

        assertThat(h.connection.serverInfo!!.opus).isTrue()
        assertThat(before.opus).isFalse()
    }
}
