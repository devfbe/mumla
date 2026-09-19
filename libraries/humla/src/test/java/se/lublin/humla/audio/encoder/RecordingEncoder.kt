package se.lublin.humla.audio.encoder

import se.lublin.humla.net.PacketBuffer

/** Test double for the encoder wrapped by PreprocessingEncoder / ResamplingEncoder. */
class RecordingEncoder : IEncoder {
    val received = mutableListOf<ShortArray>()
    val receivedSizes = mutableListOf<Int>()
    var destroyed = false

    override fun encode(input: ShortArray, inputSize: Int): Int {
        received += input.copyOf()
        receivedSizes += inputSize
        return inputSize
    }
    override fun getBufferedFrames(): Int = 0
    override fun isReady(): Boolean = false
    override fun getEncodedData(packetBuffer: PacketBuffer) {}
    override fun terminate() {}
    override fun destroy() { destroyed = true }
}
