package se.lublin.humla.audio

import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative

/**
 * The timestamp rules of libspeexdsp's `jitter_buffer_get` without its adaptive delay: the first
 * `get` after init starts at the oldest packet; a packet at, or still spanning, the playback
 * pointer is returned and moves the pointer to its end; a miss moves the pointer by the desired
 * span; packets that end before the pointer are dropped. [tick] and [updateDelay] do nothing, so
 * the model never adds buffering on its own the way libspeexdsp may after repeated misses.
 */
class SpeexJitterModel : SpeexJitterApi {
    private class Packet(val data: ByteArray, val timestamp: Int, val span: Int, val userData: Int)

    private val packets = mutableListOf<Packet>()
    private var reset = true
    private var pointer = 0

    override fun init(stepSize: Int): Long = 1L
    override fun destroy(handle: Long) = Unit

    override fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int) {
        if (!reset && timestamp + span <= pointer) return
        packets += Packet(data.copyOf(len), timestamp, span, userData)
    }

    override fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int {
        if (reset) {
            packets.minByOrNull { it.timestamp }?.let {
                pointer = it.timestamp
                reset = false
            }
        }
        val packet = if (reset) null else packetAt(desiredSpan)
        if (packet == null) {
            if (!reset) pointer += desiredSpan
            return SpeexJitterNative.JITTER_BUFFER_MISSING
        }
        packets.remove(packet)
        packet.data.copyInto(out)
        intArrayOf(packet.data.size, packet.timestamp, packet.span, 0, packet.userData).copyInto(meta)
        pointer = packet.timestamp + packet.span
        return SpeexJitterNative.JITTER_BUFFER_OK
    }

    private fun packetAt(desiredSpan: Int): Packet? {
        packets.removeAll { it.timestamp + it.span <= pointer }
        return packets.firstOrNull { it.timestamp <= pointer && it.timestamp + it.span > pointer }
            ?: packets.filter { it.timestamp in pointer until pointer + desiredSpan }.minByOrNull { it.timestamp }
    }

    override fun pointerTimestamp(handle: Long): Int = if (reset) 0 else pointer
    override fun tick(handle: Long) = Unit

    override fun ctl(handle: Long, request: Int, value: IntArray): Int {
        if (request == SpeexJitterNative.JITTER_BUFFER_GET_AVAILABLE_COUNT) {
            value[0] = packets.count { reset || it.timestamp >= pointer }
        }
        return 0
    }

    override fun updateDelay(handle: Long): Int = 0
}
