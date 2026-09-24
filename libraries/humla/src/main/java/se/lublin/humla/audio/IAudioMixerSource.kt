package se.lublin.humla.audio

/** A source for an [IAudioMixer], holding its samples in a buffer of type [T]. */
interface IAudioMixerSource<T> {
    val samples: T
    val numSamples: Int
}
