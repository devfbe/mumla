/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio.native

/**
 * libspeexdsp preprocessor. State handles are opaque: the bridge wraps the state together with its
 * frame size (libspeexdsp cannot report it) so [run] can check the array length.
 */
interface SpeexPreprocessApi {
    /** A new state, or 0 if [frameSize] is not positive or speex could not allocate one. */
    fun init(frameSize: Int, sampleRate: Int): Long

    /**
     * Runs the preprocessor in place; returns the speex VAD decision (1 = speech, 0 = not), or -1
     * if the frame could not be processed.
     *
     * [frame] must hold at least the frame size the state was created with; a shorter array is
     * refused with -1 rather than overrun.
     *
     * **Do not map this to a `Boolean` with `!= 0`**: -1 would read as speech and key the
     * microphone open. Branch on `< 0` first.
     */
    fun run(state: Long, frame: ShortArray): Int

    /**
     * Runs `speex_preprocess_ctl` with `value[0]` as the in/out int argument; returns 0, or -1.
     *
     * Only the `SPEEX_PREPROCESS_*` constants on [SpeexPreprocessNative] are let through; others
     * return -1 without reaching speex, because several requests (e.g. `GET_ECHO_STATE`,
     * `GET_PSD`) would treat the bridge's int argument as a pointer or a larger buffer.
     *
     * -1 also means the request is not implemented in this build: the AGC controls are compiled
     * out of fixed-point libspeexdsp. The two cases are indistinguishable.
     */
    fun ctlInt(state: Long, request: Int, value: IntArray): Int
    fun destroy(state: Long)
}

object SpeexPreprocessNative : SpeexPreprocessApi {
    const val SPEEX_PREPROCESS_SET_DENOISE = 0
    const val SPEEX_PREPROCESS_SET_AGC = 2
    const val SPEEX_PREPROCESS_SET_VAD = 4
    const val SPEEX_PREPROCESS_SET_DEREVERB = 8
    const val SPEEX_PREPROCESS_SET_PROB_START = 14
    const val SPEEX_PREPROCESS_GET_PROB_START = 15
    const val SPEEX_PREPROCESS_SET_NOISE_SUPPRESS = 18
    const val SPEEX_PREPROCESS_GET_PROB = 45
    const val SPEEX_PREPROCESS_SET_AGC_TARGET = 46

    init {
        System.loadLibrary("humla_speexdsp")
    }

    external override fun init(frameSize: Int, sampleRate: Int): Long
    external override fun run(state: Long, frame: ShortArray): Int
    external override fun ctlInt(state: Long, request: Int, value: IntArray): Int
    external override fun destroy(state: Long)
}
