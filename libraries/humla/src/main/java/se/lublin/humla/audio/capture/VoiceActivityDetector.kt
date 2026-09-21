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
 * A monotonic nanosecond clock, injectable for tests.
 *
 * It is a `fun interface` rather than a `() -> Long`, and the difference is 24 B on the audio
 * thread per frame rather than a matter of style: a Kotlin function type erases its return to a
 * reference, so `Function0<Long>.invoke()` boxes. `nanoTime()` here compiles to `()J`. Every call
 * site is a lambda or a method reference and needs no change, because Kotlin converts both.
 */
fun interface NanoClock {
    fun nanoTime(): Long
}

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
 * **Its frame path is measured now, and the source-reading was wrong.** The reading was: [isVoice]
 * constructs nothing, because the `Float?` it receives is already boxed by the stage that produced
 * it, `?:` unboxes rather than reboxing, and the `when` is over an enum. All true, and it missed
 * the clock. `CaptureThreadAllocationTest` measured **24.006 B per frame, cold, in both modes** --
 * in [VadMode.PROBABILITY] too, where the level loop never runs, which is what pointed at the one
 * line both modes share. `() -> Long` is `Function0<java.lang.Long>`: its `invoke` erases to a
 * reference return, so every reading of the clock boxed a `Long` on the audio thread. Proven
 * rather than reasoned: a clock returning `0L` sits inside `Long.valueOf`'s cache and measured
 * **0.006 B**, one returning `1_000_000_000L` measured **18.135 B**, same code. Hence [NanoClock],
 * whose `nanoTime()` has JVM signature `()J`. After it: 0.006 B cold in both modes.
 *
 * That is also why the number had to be taken rather than argued. The hot window reads 0.000 B
 * either way -- C2 scalar-replaces the box -- and ART does no such elimination, so a hot-only
 * measurement would have certified an allocation the device makes (spec §4.05).
 */
class VoiceActivityDetector(
    config: VadConfig,
    /**
     * How long one frame is. Ten milliseconds, because [CapturePipeline] resamples to
     * `AudioHandler.SAMPLE_RATE` and hands down `SAMPLE_RATE / 100` samples; it is a constructor
     * parameter rather than a constant so that the settings screen's preview, which may run at a
     * source rate the resampler was not built for, can say so instead of mis-scaling every time
     * constant in [AdaptiveVadTracker].
     *
     * It sits in front of [clock] so that `VoiceActivityDetector(config) { nanos }` keeps binding
     * its trailing lambda to the clock, which four test classes rely on.
     */
    private val frameMs: Float = DEFAULT_FRAME_MS,
    private val clock: NanoClock = NanoClock(System::nanoTime),
) {
    @Volatile
    var config: VadConfig = config

    private var talking = false

    /**
     * How many consecutive frames have been over the threshold. The transient guard: see
     * [VadConfig.onsetFrames].
     */
    private var consecutive = 0

    private val tracker = AdaptiveVadTracker(
        initialFloorDbfs =
            if (config.adaptiveFloor) AdaptiveVadTracker.DEFAULT_FLOOR_DBFS else config.manualFloorDbfs,
    )

    /**
     * This frame's level in dBFS, for the level meter (spec B10). Written on every frame in every
     * mode -- it is derived from [amplitudeScore], which the detector computes anyway, so it costs
     * one subtraction and one multiplication rather than a second pass over the frame. That is also
     * what makes it *one* reading of the frame's energy rather than two: the meter and the gate
     * cannot drift apart because there is only one loop.
     */
    @Volatile
    var lastLevelDbfs: Float = NO_SIGNAL_DBFS
        private set

    /** The tracked noise floor, for the meter's lower mark. */
    val floorDbfs: Float get() = tracker.floorDbfs

    /** The tracked speech peak, for the meter's upper mark. */
    val speechDbfs: Float get() = tracker.speechDbfs

    /** Where the gate opens, for the meter's threshold mark. */
    val thresholdDbfs: Float get() = tracker.thresholdDbfs(config.snrFraction)

    /** True while the talker and the room are too close together for the gate to do its job. */
    val tooClose: Boolean get() = tracker.tooClose

    /** The user's "measure again": forget both estimates and start from the assumed gap. */
    fun recalibrate() {
        val c = config
        tracker.reset(
            if (c.adaptiveFloor) AdaptiveVadTracker.DEFAULT_FLOOR_DBFS else c.manualFloorDbfs
        )
    }

    /**
     * When the hold expires, not when voice was last heard. Seeded from the clock so that the
     * first frame reads "the hold is over" whatever the clock's origin is, without a second field
     * to say whether voice has ever been heard.
     */
    private var holdUntilNanos: Long = clock.nanoTime()

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
        val level = amplitudeScore(pcm, length)
        val levelDbfs = scoreToDbfs(level)
        lastLevelDbfs = levelDbfs
        if (!c.adaptiveFloor) tracker.setFloor(c.manualFloorDbfs)
        val detected = when (c.mode) {
            VadMode.AMPLITUDE -> level >= (if (talking) c.stopThreshold else c.startThreshold)
            VadMode.PROBABILITY ->
                (probability ?: level) >= (if (talking) c.stopThreshold else c.startThreshold)
            VadMode.ADAPTIVE -> {
                val start = tracker.thresholdDbfs(c.snrFraction)
                levelDbfs >= (if (talking) start - c.hysteresisDb else start)
            }
        }
        // The onset is demanded only while the gate is shut. Inside a word the hold is what carries
        // the gate over a gap, and charging the onset again there would clip every second syllable.
        consecutive = if (detected) consecutive + 1 else 0
        val accepted = detected && (talking || consecutive >= c.onsetFrames)
        val now = clock.nanoTime()
        if (accepted) holdUntilNanos = now + c.holdTimeMs * 1_000_000L
        talking = accepted || now - holdUntilNanos < 0L
        // After the decision, never before it: the threshold this frame was judged against has to
        // be the one the previous frames produced, or the estimator grades its own homework.
        tracker.update(levelDbfs, talking, frameMs, learnFloor = c.adaptiveFloor)
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
         * **The empty frame is the one case the legacy formula got backwards.** With no samples,
         * `sqrt(1.0 / 0)` is positive infinity and the score comes out **above every threshold**:
         * the formula answers `length == 0` with "louder than anything". Both the Java original
         * and the plan's listing do this. It returns [NO_SIGNAL] instead.
         *
         * **It is not a live defect, and the first version of this paragraph claimed it was.**
         * `AudioInput.loop` is the only call site on this path today; it skips a read of 0
         * *before* calling and hands over the whole frame size rather than the count, so no
         * zero-sample frame reaches here. What makes the repair right is where B1 and B11 take
         * it: task 8's `CapturePipeline` passes a resampler's own output length down, and
         * `AudioHandler:430` -- which does pass a read count straight through -- is task 11's to
         * replace.
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

        /**
         * The same frame energy as [amplitudeScore], on the scale [VadMode.ADAPTIVE] and the level
         * meter are written in. It is the legacy curve solved for the level rather than a second
         * measurement, so the two can never disagree -- which matters because the settings screen
         * shows both at once.
         */
        @JvmStatic
        fun levelDbfs(pcm: ShortArray, length: Int): Float = scoreToDbfs(amplitudeScore(pcm, length))

        private fun scoreToDbfs(score: Float): Float = (score - 1f) * 96f

        /**
         * What [levelDbfs] answers for a frame with no samples: [NO_SIGNAL] on the dBFS scale,
         * i.e. -192. Finite, and below [AdaptiveVadTracker.MIN_FLOOR_DBFS], so it can neither be
         * drawn as an infinity nor drag a floor estimate anywhere it is allowed to go.
         */
        const val NO_SIGNAL_DBFS = (NO_SIGNAL - 1f) * 96f

        /** Ten milliseconds: `AudioHandler.FRAME_SIZE` samples at `AudioHandler.SAMPLE_RATE`. */
        const val DEFAULT_FRAME_MS = 10f
    }
}
