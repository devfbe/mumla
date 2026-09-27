package se.lublin.humla.audio

/** Sums float PCM sources and converts to 16-bit PCM, clipping naively to [-1, 1]. */
internal class BasicClippingShortMixer : IAudioMixer<FloatArray, ShortArray> {
    override fun mix(
        sources: List<IAudioMixerSource<FloatArray>>,
        buffer: ShortArray,
        bufferOffset: Int,
        bufferLength: Int,
    ) {
        // Indexed, not iterated: an iterator per sample would allocate on the playback thread.
        for (i in 0 until bufferLength) {
            var mix = 0f
            for (s in sources.indices) mix += sources[s].samples[i]
            buffer[i + bufferOffset] = (mix.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
