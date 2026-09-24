package se.lublin.humla.audio

/** Mixes [IAudioMixerSource]s with buffers of type [T] into a destination buffer of type [U]. */
fun interface IAudioMixer<T, U> {
    fun mix(sources: Collection<IAudioMixerSource<T>>, buffer: U, bufferOffset: Int, bufferLength: Int)
}
