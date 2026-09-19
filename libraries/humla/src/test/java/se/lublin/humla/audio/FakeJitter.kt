package se.lublin.humla.audio

import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative

/** Records what [SpeexJitterBuffer] sends to libspeexdsp and replays a scripted `get`. */
class FakeJitter : SpeexJitterApi {
    /** One entry per `put`, as `[length, timestamp, span, sequence, userData]`. */
    val puts = mutableListOf<List<Int>>()
    var lastPutData: ByteArray? = null
    /** Recorded `ctl` requests as `request to value`. */
    val ctlCalls = mutableListOf<Pair<Int, Int>>()
    /** The value the next `ctl` writes back into its in/out argument. */
    var ctlResult = 0
    var nextStatus = SpeexJitterNative.JITTER_BUFFER_MISSING
    var nextPacket = ByteArray(0)
    /** `[length, timestamp, span, sequence, userData]` the next `get` reports. */
    var nextMeta = intArrayOf(0, 0, 0, 0, 0)
    var ticks = 0
    var updateDelayCalls = 0
    var destroyed = false

    override fun init(stepSize: Int): Long = 1L
    override fun destroy(handle: Long) {
        destroyed = true
    }
    override fun put(handle: Long, data: ByteArray, len: Int, timestamp: Int, span: Int, sequence: Int, userData: Int) {
        lastPutData = data.copyOf()
        puts += listOf(len, timestamp, span, sequence, userData)
    }
    override fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int {
        nextPacket.copyInto(out, 0, 0, minOf(nextPacket.size, out.size))
        nextMeta.copyInto(meta)
        return nextStatus
    }
    override fun pointerTimestamp(handle: Long): Int = 0
    override fun tick(handle: Long) {
        ticks++
    }
    override fun ctl(handle: Long, request: Int, value: IntArray): Int {
        ctlCalls += request to value[0]
        value[0] = ctlResult
        return 0
    }
    override fun updateDelay(handle: Long): Int {
        updateDelayCalls++
        return 0
    }
}
