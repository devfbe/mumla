package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class TcpFrameReaderTest {
    private fun stream(vararg frames: ByteArray) =
        DataInputStream(ByteArrayInputStream(frames.reduce { a, b -> a + b }))

    /** Hands out at most one byte per read(), the way a slow TCP socket does. */
    private class Dribbling(source: InputStream) : FilterInputStream(source) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else super.read(b, off, 1)
    }

    @Test
    fun readsTypeAndPayload() {
        val input = stream(tcpFrame(7, byteArrayOf(1, 2, 3)))

        val read = HumlaTCP.readFrame(input)!!

        assertThat(read.type).isEqualTo(HumlaTCPMessageType.ChannelState)
        assertThat(read.data).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun unknownTypeIsSkippedAndTheNextFrameIsStillReadable() {
        val input = stream(tcpFrame(99, byteArrayOf(9, 9)), tcpFrame(3, byteArrayOf()))

        assertThat(HumlaTCP.readFrame(input)).isNull()
        val next = HumlaTCP.readFrame(input)!!
        assertThat(next.type).isEqualTo(HumlaTCPMessageType.Ping)
        assertThat(next.data).isEmpty()
    }

    /**
     * The stream ends halfway through the type field, inside the length field, or inside a payload
     * the header promised: none of these is delivered as a frame.
     */
    @Test
    fun endOfStreamInsideAFrameThrowsEof() {
        val full = tcpFrame(7, ByteArray(8) { 1 })
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(0, 7), full.copyOf(full.size - 5))) {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            assertThrows(EOFException::class.java) { HumlaTCP.readFrame(input) }
        }
    }

    /**
     * A socket is free to return fewer bytes than asked for. If the reader used read() instead of
     * readFully() this would silently hand a half-filled payload to the protocol layer and leave
     * the remainder in the stream, desynchronising every frame that follows.
     */
    @Test
    fun aPayloadSplitAcrossManyReadsIsReassembledAndTheStreamStaysInSync() {
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        val bytes = tcpFrame(7, payload) + tcpFrame(3, byteArrayOf(42))
        val input = DataInputStream(Dribbling(ByteArrayInputStream(bytes)))

        val first = HumlaTCP.readFrame(input)!!
        assertThat(first.type).isEqualTo(HumlaTCPMessageType.ChannelState)
        assertThat(first.data).isEqualTo(payload)

        val second = HumlaTCP.readFrame(input)!!
        assertThat(second.type).isEqualTo(HumlaTCPMessageType.Ping)
        assertThat(second.data).isEqualTo(byteArrayOf(42))
        assertThat(input.read()).isEqualTo(-1)
    }

    /** End of stream between frames: a clean server close, not a corrupt frame. */
    @Test
    fun endOfStreamAtAFrameBoundaryThrowsEof() {
        val input = stream(tcpFrame(3, byteArrayOf()))

        assertThat(HumlaTCP.readFrame(input)).isNotNull()
        assertThrows(EOFException::class.java) { HumlaTCP.readFrame(input) }
    }

    /** The last known type must not be mistaken for an unknown one by an off-by-one bound. */
    @Test
    fun theHighestKnownTypeIsAccepted() {
        val last = HumlaTCPMessageType.values().last()
        val input = stream(tcpFrame(last.ordinal, byteArrayOf(5)))

        val read = HumlaTCP.readFrame(input)!!

        assertThat(read.type).isEqualTo(last)
        assertThat(read.data).isEqualTo(byteArrayOf(5))
    }

    /**
     * A hostile or broken server can put anything in the length field. ByteArray(length) would
     * throw NegativeArraySizeException, which is not an IOException and would escape the read loop.
     * Mumble's own limit, in Connection.cpp: a server drops the connection for a packet above
     * 0x7fffff and refuses to send one, so the first length no server will ever produce must be
     * refused before it is allocated. Not an EOFException: the reader must reject the header, not
     * try to read the payload.
     */
    @Test
    fun aNegativeOrOversizedLengthIsAConnectionErrorRefusedBeforeAllocating() {
        for (length in listOf(-1, 0x7fffff + 1)) {
            val input = DataInputStream(ByteArrayInputStream(tcpFrame(3, length = length)))

            val thrown = assertThrows(IOException::class.java) { HumlaTCP.readFrame(input) }

            assertWithMessage("length $length").that(thrown).isNotInstanceOf(EOFException::class.java)
        }
    }

    /** The largest frame the protocol allows - 8 MiB minus one byte - is still a valid frame. */
    @Test
    fun aFrameOfExactlyTheProtocolMaximumIsAccepted() {
        val payload = ByteArray(0x7fffff)
        val input = stream(tcpFrame(3, payload))

        val read = HumlaTCP.readFrame(input)!!

        assertThat(read.data.size).isEqualTo(payload.size)
    }
}
