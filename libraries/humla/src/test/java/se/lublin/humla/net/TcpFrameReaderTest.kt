package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class TcpFrameReaderTest {
    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(type)
            writeInt(payload.size)
            write(payload)
        }
        return bytes.toByteArray()
    }

    private fun stream(vararg frames: ByteArray) = DataInputStream(ByteArrayInputStream(frames.reduce { a, b -> a + b }))

    /** Hands out at most one byte per read(), the way a slow TCP socket does. */
    private class Dribbling(source: InputStream) : FilterInputStream(source) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else super.read(b, off, 1)
    }

    @Test
    fun readsTypeAndPayload() {
        val input = stream(frame(7, byteArrayOf(1, 2, 3)))

        val read = HumlaTCP.readFrame(input)!!

        assertThat(read.type).isEqualTo(HumlaTCPMessageType.ChannelState)
        assertThat(read.data).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun unknownTypeIsSkippedAndTheNextFrameIsStillReadable() {
        val input = stream(frame(99, byteArrayOf(9, 9)), frame(3, byteArrayOf()))

        assertThat(HumlaTCP.readFrame(input)).isNull()
        val next = HumlaTCP.readFrame(input)!!
        assertThat(next.type).isEqualTo(HumlaTCPMessageType.Ping)
        assertThat(next.data).isEmpty()
    }

    /** Both bytes of the type are there, the stream ends inside the length field. */
    @Test
    fun endOfStreamInsideTheLengthFieldThrowsEof() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(0, 7)))

        assertThrows(EOFException::class.java) { HumlaTCP.readFrame(input) }
    }

    /** The stream ends after a single byte, halfway through the type field. */
    @Test
    fun endOfStreamInsideTheTypeFieldThrowsEof() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(0)))

        assertThrows(EOFException::class.java) { HumlaTCP.readFrame(input) }
    }

    /**
     * A socket is free to return fewer bytes than asked for. If the reader used read() instead of
     * readFully() this would silently hand a half-filled payload to the protocol layer and leave
     * the remainder in the stream, desynchronising every frame that follows.
     */
    @Test
    fun aPayloadSplitAcrossManyReadsIsReassembledAndTheStreamStaysInSync() {
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        val bytes = frame(7, payload) + frame(3, byteArrayOf(42))
        val input = DataInputStream(Dribbling(ByteArrayInputStream(bytes)))

        val first = HumlaTCP.readFrame(input)!!
        assertThat(first.type).isEqualTo(HumlaTCPMessageType.ChannelState)
        assertThat(first.data).isEqualTo(payload)

        val second = HumlaTCP.readFrame(input)!!
        assertThat(second.type).isEqualTo(HumlaTCPMessageType.Ping)
        assertThat(second.data).isEqualTo(byteArrayOf(42))
        assertThat(input.read()).isEqualTo(-1)
    }

    /** A frame whose header promises more payload than the peer ever sent must not be delivered. */
    @Test
    fun aFrameTruncatedInsideItsPayloadThrowsEof() {
        val full = frame(7, ByteArray(8) { 1 })
        val input = DataInputStream(ByteArrayInputStream(full.copyOf(full.size - 5)))

        assertThrows(EOFException::class.java) { HumlaTCP.readFrame(input) }
    }

    /** End of stream between frames: a clean server close, not a corrupt frame. */
    @Test
    fun endOfStreamAtAFrameBoundaryThrowsEof() {
        val input = stream(frame(3, byteArrayOf()))

        assertThat(HumlaTCP.readFrame(input)).isNotNull()
        assertThrows(EOFException::class.java) { HumlaTCP.readFrame(input) }
    }

    /** The last known type must not be mistaken for an unknown one by an off-by-one bound. */
    @Test
    fun theHighestKnownTypeIsAccepted() {
        val last = HumlaTCPMessageType.values().last()
        val input = stream(frame(last.ordinal, byteArrayOf(5)))

        val read = HumlaTCP.readFrame(input)!!

        assertThat(read.type).isEqualTo(last)
        assertThat(read.data).isEqualTo(byteArrayOf(5))
    }

    private fun header(type: Int, length: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeShort(type); writeInt(length) }
        return bytes.toByteArray()
    }

    /**
     * A hostile or broken server can put anything in the length field. ByteArray(length) throws
     * NegativeArraySizeException for a negative one, which is not an IOException, so it escaped the
     * read loop's catch clauses into the default handler - process death on Android, and no
     * onTCPConnectionFailed, so nothing would have reconnected either.
     */
    @Test
    fun aNegativeLengthIsReportedAsAConnectionErrorRatherThanKillingTheReader() {
        val input = DataInputStream(ByteArrayInputStream(header(3, -1)))

        val thrown = assertThrows(IOException::class.java) { HumlaTCP.readFrame(input) }

        assertThat(thrown).isNotInstanceOf(EOFException::class.java)
    }

    /**
     * Mumble's own limit, in Connection.cpp: a server drops the connection for a packet above
     * 0x7fffff and refuses to send one, so the first length no server will ever produce must be
     * refused before it is allocated.
     */
    @Test
    fun aLengthBeyondTheProtocolMaximumIsRefusedBeforeAllocating() {
        val input = DataInputStream(ByteArrayInputStream(header(3, 0x7fffff + 1)))

        val thrown = assertThrows(IOException::class.java) { HumlaTCP.readFrame(input) }

        // Not an EOFException: the reader must reject the header, not try to read the payload.
        assertThat(thrown).isNotInstanceOf(EOFException::class.java)
    }

    /** The largest frame the protocol allows - 8 MiB minus one byte - is still a valid frame. */
    @Test
    fun aFrameOfExactlyTheProtocolMaximumIsAccepted() {
        val payload = ByteArray(0x7fffff)
        val input = stream(frame(3, payload))

        val read = HumlaTCP.readFrame(input)!!

        assertThat(read.data.size).isEqualTo(payload.size)
    }
}
