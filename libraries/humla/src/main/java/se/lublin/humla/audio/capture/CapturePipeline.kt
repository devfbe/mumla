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

package se.lublin.humla.audio.capture


/**
 * Result of one pipeline pass. Owned and reused by the pipeline (as is [samples]), so every field is
 * valid only until the next [CapturePipeline.process]; this avoids a per-frame allocation.
 *
 * [length] is always `samples.size`: short frames are zero-padded, and the encoder sees the padding.
 */
class CaptureFrame internal constructor(val samples: ShortArray) {
    var length: Int = 0
        internal set

    var transmit: Boolean = false
        internal set

    var probability: Float? = null
        internal set
}

/**
 * Input -> resample to 48 kHz (if needed) -> preprocess every frame -> VAD -> amplitude boost.
 * Single-threaded: call [process] only from the capture thread.
 *
 * - Preprocessing runs before the detector so the VAD doesn't trigger on noise the denoiser removes.
 * - The boost (an output gain) runs after the detector so it doesn't shift the VAD threshold.
 *
 * The frame buffer is reused: short frames are zero-padded so the encoder never resends a stale
 * tail, and the detector gets the resampler's actual count (0 is legal and yields
 * [VoiceActivityDetector.NO_SIGNAL]).
 */
class CapturePipeline(
    resampler: Resampler?,
    private val preprocessor: CapturePreprocessor,
    private val inputMode: IInputMode,
    @Volatile var amplitudeBoost: Float = 1f,
    frameSize: Int = 480,
    private val log: (String) -> Unit = {},
) {
    private val frame = ShortArray(frameSize)
    private val result = CaptureFrame(frame)

    /** Written by [setResampler] from another thread. */
    @Volatile
    private var resampler: Resampler? = resampler

    private var shortFrameLogged = false

    fun process(input: ShortArray, inputLength: Int): CaptureFrame {
        val r = resampler
        val produced = if (r != null) {
            r.resample(input, inputLength, frame)
        } else {
            val n = minOf(inputLength, frame.size)
            System.arraycopy(input, 0, frame, 0, n)
            n
        }
        if (produced < frame.size) {
            frame.fill(0, produced, frame.size)
            if (!shortFrameLogged) {
                shortFrameLogged = true
                log("capture produced only $produced of ${frame.size} samples; padding with silence")
            }
        }

        val probability = preprocessor.process(frame)
        val transmit = inputMode.shouldTransmit(frame, produced, probability)
        if (transmit && amplitudeBoost != 1f) boost(frame, amplitudeBoost)
        result.length = frame.size
        result.transmit = transmit
        result.probability = probability
        return result
    }

    /**
     * Swaps in a new resampler (after the capture source reopened at another rate) and releases the
     * old one; passing the installed one is a no-op. Re-arms the one-time short-frame log.
     */
    fun setResampler(resampler: Resampler?) {
        val old = this.resampler
        this.resampler = resampler
        shortFrameLogged = false
        if (old !== resampler) old?.release()
    }

    fun release() {
        resampler?.release()
        resampler = null
        preprocessor.release()
    }

    /** Scales and clamps in place. (The `!= 1f` check in [process] only saves work.) */
    private fun boost(samples: ShortArray, factor: Float) {
        for (i in samples.indices) {
            // Clamp on the float: float -> int -> short narrowing doesn't saturate.
            val v = samples[i] * factor
            samples[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toInt().toShort()
            }
        }
    }
}
