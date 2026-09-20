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

import se.lublin.humla.audio.inputmode.IInputMode

/**
 * Result of one pipeline pass. **The whole object is the pipeline's, not the caller's**: [samples]
 * is the reusable frame buffer and the instance itself is reused too, so every field is valid only
 * until the next [CapturePipeline.process]. Read it, do not keep it.
 *
 * The plan declared a four-argument constructor and a fresh instance per frame. It was built that
 * way, measured, and changed: `CaptureThreadAllocationTest` read **32.013 B per frame, cold** for
 * the wrapper alone -- 3.2 KB/s on the audio thread, on top of the 16 B probability box the real
 * chain pays, which together is the whole allowance spec §4.1 granted three stages. The wrapper is
 * the `Pair`-shaped per-frame allocation that file exists to catch, and its lifetime was already
 * the buffer's, so reusing the instance states the lifetime instead of paying for hiding it.
 * Pinned twice: by identity in `CapturePipelineTest` and by the heap delta.
 *
 * [length] is always `samples.size` today and is carried anyway, because the encoder's `encode`
 * takes a count and the pipeline is the only thing that knows the frame is complete -- the padding
 * is deliberate and the encoder is meant to see it. Task 11 reads both.
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
 * Spec B1: input -> resample to 48 kHz (if needed) -> preprocess every frame -> VAD -> amplitude
 * boost. Single-threaded: call [process] only from the capture thread.
 *
 * **Two orderings here are the feature, not an implementation detail.**
 *
 * - *Preprocess before the detector.* Today `AudioHandler:430` hands the input mode the **raw**
 *   capture frame and preprocessing happens later, inside `PreprocessingEncoder.encode`, which is
 *   the user-reported "voice activation triggered by background noise" (spec §6): the detector
 *   judges the noise the denoiser is about to remove. The consequence, and it is a migration
 *   consequence rather than a bug: **the same `detection_threshold` slider position now means a
 *   different level for every existing user**, because the frame it is measured on has been
 *   denoised and, with the WebRTC APM in the chain, gain-controlled. Task 12+13 owns saying so in
 *   the settings copy; it is in the ledger.
 * - *Boost after the detector.* The amplification slider is an output gain, not a voice-activation
 *   gain. Boosting first would make one slider silently move the other's threshold -- 500 reads
 *   0.62157 and 4000 reads 0.80971, so a boost of 8 straddles a 0.7 threshold. Pinned.
 *
 * **The frame buffer is reused, and that is the third thing this class has to get right.** It is
 * allocated once, like `AudioInput.loop`'s, so the samples behind a short frame still hold
 * the previous frame's tail. Two distinct things follow, with two distinct guards, because they
 * are two observables rather than one:
 *
 * - the encoder is handed the **whole** buffer, so a tail left standing is audio the microphone
 *   already sent going out a second time -- closed by padding from [Resampler.resample]'s count to
 *   the end;
 * - the amplitude detector is handed a **count**, and that count is the resampler's, never the
 *   buffer's -- 300 samples of 300 read 0.57535 on their own, 0.55409 over the zeroed buffer and
 *   **0.91097** over the previous frame's 20 000s. Silence against shouting, same threshold.
 *
 * A count of zero is passed down as zero: [VoiceActivityDetector.amplitudeScore] answers
 * [VoiceActivityDetector.NO_SIGNAL] for it, which is below every threshold [VadConfig] can hold and
 * is finite so B10's level meter can draw it. That is the honest reading for a frame that does not
 * exist, and it is reachable in production -- [SpeexResampler] answers 0 for every speex error.
 */
class CapturePipeline @JvmOverloads constructor(
    resampler: Resampler?,
    private val preprocessor: CapturePreprocessor,
    private val inputMode: IInputMode,
    @Volatile var amplitudeBoost: Float = 1f,
    frameSize: Int = 480,
    private val log: (String) -> Unit = {},
) {
    private val frame = ShortArray(frameSize)
    private val result = CaptureFrame(frame)

    /**
     * `@Volatile` because [setResampler] is the one field another thread writes, and spec §4.1's
     * ruling on the capture chain is that a plain `var` there fails *quietly* -- the capture thread
     * keeps reading the old, just-released reference. It cannot be pinned by a test in this module
     * (a data race has no deterministic observable), so it is written down rather than claimed: the
     * mutation that would distinguish it is a two-thread stress run, and nobody has run one.
     */
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
     * Swaps the resampler after the capture source was re-opened at another rate (spec B7 retry)
     * and releases the old one. Passing the resampler that is already installed is a no-op rather
     * than a release of the running one.
     *
     * It also re-arms the short-frame log, because the rate limit is "once per reason" and a new
     * resampler is a new reason. The B7 retry is the only caller, so without this the one case the
     * log exists for -- the re-opened source producing short frames too -- is the case it stays
     * silent about.
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

    /**
     * The `!= 1f` fast path above is a cost guard, not a correctness guard: removing it alone
     * leaves every test in `CapturePipelineTest` green, because a factor of one is the identity on
     * every `Short` including both ends of the range. That premise is what makes skipping legal and
     * it is pinned as its own test (`a boost of one changes no sample`) rather than asserted here.
     */
    private fun boost(samples: ShortArray, factor: Float) {
        for (i in samples.indices) {
            // Java only guarantees the bounded preservation of sign in a narrowing conversion from
            // float -> int, not float -> int -> short, so the clamp is on the float.
            val v = samples[i] * factor
            samples[i] = when {
                v > Short.MAX_VALUE -> Short.MAX_VALUE
                v < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> v.toInt().toShort()
            }
        }
    }
}
