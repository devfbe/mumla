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

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Decides per frame whether the user is talking (spec B5): start/stop hysteresis over a score, plus
 * a hold that keeps the transmission open across the gaps inside a word.
 *
 * Stateful, and the state is plain: call [isVoice] from one thread, the capture thread. Only
 * [config] is `@Volatile`, because that is the one field another thread writes -- the settings
 * thread, through `ActivityInputMode`.
 *
 * [clock] returns nanoseconds and is injectable for tests. It must be monotonic; it may start
 * anywhere, including near `Long.MAX_VALUE`, which `System.nanoTime` documents as permitted
 * ("some fixed but arbitrary origin time") and which is why the hold is a deadline compared with
 * `now - deadline < 0` rather than an elapsed time compared with the hold.
 *
 * **Its frame path is not measured yet.** Read out of the source, [isVoice] constructs nothing:
 * the `Float?` it receives is already boxed by the stage that produced it, `?:` unboxes rather
 * than reboxing, and the `when` is over an enum. But that is a claim about the text, and the only
 * instrument in this module that can separate it from zero is `CaptureThreadAllocationTest`'s
 * heap delta -- which measures the stages and not this class. Task 8 opens that file to add
 * `CapturePipeline`; the detector belongs in the same pass. Until then nobody has a number here,
 * and "allocates nothing" is not something this file gets to say.
 */
class VoiceActivityDetector(
    config: VadConfig,
    private val clock: () -> Long = System::nanoTime,
) {
    @Volatile
    var config: VadConfig = config

    private var talking = false

    /**
     * When the hold expires, not when voice was last heard. Seeded from the clock so that the
     * first frame reads "the hold is over" whatever the clock's origin is, without a second field
     * to say whether voice has ever been heard.
     */
    private var holdUntilNanos: Long = clock()

    /**
     * @param pcm the (already preprocessed) frame
     * @param probability the preprocessor chain's probability for this frame, or null if none.
     *   `null` falls back to the frame's level rather than to zero -- a chain whose stages are all
     *   released, or that has no stage with an opinion, hands over `null` on every frame, and
     *   reading that as "certainly not speech" is the capture path muting the user with no log
     *   line. Same ruling as `RnnoisePreprocessor`'s refusal sentinel.
     */
    fun isVoice(pcm: ShortArray, length: Int, probability: Float?): Boolean {
        val c = config
        val score = when (c.mode) {
            VadMode.AMPLITUDE -> amplitudeScore(pcm, length)
            VadMode.PROBABILITY -> probability ?: amplitudeScore(pcm, length)
        }
        val now = clock()
        val threshold = if (talking) c.stopThreshold else c.startThreshold
        val detected = score >= threshold
        if (detected) holdUntilNanos = now + c.holdTimeMs * 1_000_000L
        talking = detected || now - holdUntilNanos < 0L
        return talking
    }

    companion object {
        /**
         * The legacy detector's curve, unchanged: `1 + 20*log10(rms/32768)/96`. Full scale is
         * 1.0, -96 dBFS is 0.0, and digital silence is slightly negative (the `+1` inside the sum
         * keeps the logarithm finite). One halving of the amplitude is 0.0627 of the score.
         *
         * It is kept bit-for-bit in shape because the user's `detection_threshold` slider is
         * calibrated against it and nothing migrates that value. The one thing that did change is
         * the accumulator: `Float` in the Java original, `Double` here, which costs nothing and
         * removes the precision loss at 480 samples of full-scale audio.
         *
         * **What did change is the frame it is handed.** Today `AudioHandler:430` calls the input
         * mode with the *raw* capture frame and preprocessing happens later, inside
         * `PreprocessingEncoder.encode`. Spec B1 puts the preprocessor **before** the detector, so
         * whoever builds `CapturePipeline` (task 8) hands this function a denoised and possibly
         * gain-controlled frame, and the same slider position then means a different level. That
         * is a migration consequence of B1, not of this function, and it belongs in the settings
         * copy; it is in the ledger.
         *
         * **The empty frame is the one case the legacy formula got backwards.** `AudioRecord.read`
         * can return 0, and `AudioHandler:430` passes that count straight through. With no
         * samples, `sqrt(1.0 / 0)` is positive infinity and the score comes out **above every
         * threshold**, so a read that returned nothing was transmitted as loud speech. Both the
         * Java original and the plan's listing do this. It returns [NO_SIGNAL] instead.
         */
        fun amplitudeScore(pcm: ShortArray, length: Int): Float {
            if (length <= 0) return NO_SIGNAL
            var sum = 1.0
            for (i in 0 until length) sum += pcm[i].toDouble() * pcm[i].toDouble()
            val rms = sqrt(sum / length)
            return (1.0 + 20.0 * log10(rms / 32768.0) / 96.0).toFloat()
        }

        /**
         * What [amplitudeScore] answers for a frame with no samples in it: below every threshold
         * [VadConfig] can hold, since those are in [0, 1], and finite -- spec B10's input level
         * meter has to draw this value, and a negative infinity is not a pixel.
         */
        const val NO_SIGNAL = -1f
    }
}
