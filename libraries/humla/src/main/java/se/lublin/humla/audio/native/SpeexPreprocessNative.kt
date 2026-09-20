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
 * libspeexdsp preprocessor.
 *
 * State handles are opaque. They are not `SpeexPreprocessState*`: `speex_preprocess_run` writes
 * the frame size the state was CREATED with and libspeexdsp has no ctl to ask a state for it, so
 * the bridge hands out a small struct holding the state and that frame size, and compares it
 * against the array's real length in [run].
 */
interface SpeexPreprocessApi {
    /** A new state, or 0 if [frameSize] is not positive or speex could not allocate one. */
    fun init(frameSize: Int, sampleRate: Int): Long

    /**
     * Runs the preprocessor in place; returns the speex VAD decision (1 = speech, 0 = not), or -1
     * if the frame could not be processed.
     *
     * [frame] must hold at least the [frameSize] the state was created with. speex writes that
     * many samples whatever the array's real length is, so a shorter array is refused with -1
     * rather than overrun -- this used to be a live crash at ultra-wideband.
     */
    fun run(state: Long, frame: ShortArray): Int

    /**
     * Runs `speex_preprocess_ctl` with `value[0]` as the in/out int argument; returns 0, or -1.
     *
     * [request] has to be one of the `SPEEX_PREPROCESS_*` constants on [SpeexPreprocessNative] --
     * those are the requests the bridge allows through, and any other number is refused with -1
     * without reaching speex. That is not tidiness: the bridge hands speex the address of a
     * four-byte int on its own stack frame, and several requests treat that address as something
     * else entirely. `GET_ECHO_STATE` writes a pointer through it, `SET_ECHO_STATE` keeps it as
     * one and dereferences it later, and `GET_PSD` writes a whole frame of ints into it.
     *
     * A -1 can also mean that libspeexdsp does not implement the request in this build: its AGC
     * controls (`SPEEX_PREPROCESS_SET_AGC`, `SPEEX_PREPROCESS_SET_AGC_TARGET`) are compiled out of
     * a fixed-point build, which is what this library is.
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
