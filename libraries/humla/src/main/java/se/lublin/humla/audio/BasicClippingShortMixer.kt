package se.lublin.humla.audio

/** Sums float PCM sources and converts to 16-bit PCM, clipping naively to [-1, 1]. */
class BasicClippingShortMixer : IAudioMixer<FloatArray, ShortArray> {
    override fun mix(
        sources: Collection<IAudioMixerSource<FloatArray>>,
        buffer: ShortArray,
        bufferOffset: Int,
        bufferLength: Int,
    ) {
        for (i in 0 until bufferLength) {
            var mix = 0f
            for (source in sources) mix += source.samples[i]
            buffer[i + bufferOffset] = (mix.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
