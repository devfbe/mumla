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
 * Tracks the two levels a distance-robust voice gate needs -- the noise floor and the recent
 * speech peak, both in dBFS -- and puts the transmit threshold between them.
 *
 * ### Why two estimates and not one
 *
 * A fixed threshold, and a threshold that follows only the floor, both answer the wrong question
 * for the reported case: *"when I walk around the room and am sometimes further from the phone and
 * sometimes closer, it would be nice if that worked."* Distance costs about **6 dB per doubling**,
 * and it costs it to the *talker*, not to the room. A floor-following gate leaves the threshold
 * where it is and the talker drops below it. The quantity that stays meaningful across distance is
 * the **gap** between the two, which is why the threshold here is a fraction of that gap rather
 * than a fixed number of dB over the floor.
 *
 * ### What it cannot do, stated rather than implied
 *
 * With distance the talker loses level **and** signal-to-noise: the microphone hears proportionally
 * more room and less person. No control loop invents signal-to-noise. This one moves the point at
 * which the gate stops working; it does not remove it. That point is [MIN_USABLE_GAP_DB] -- below
 * a 10 dB speech-to-floor gap the margin is clamped at 6.5 dB, which is only 2.2 dB above the
 * floor's own measured spread (spec 4.1 measured the non-speech floor at -43.5 ... -47.8 dBFS
 * across three noise characters, i.e. **4.3 dB peak to peak**). [tooClose] says so, and the level
 * meter draws it.
 *
 * ### The four locks, each with the failure it prevents
 *
 * 1. **The floor is learned only while nothing is transmitted.** A floor estimator that runs during
 *    speech learns the speech as noise and closes the microphone on the talker.
 * 2. **The speech peak rises only while transmitting**, instantly, and it is a *peak with decay*
 *    rather than a mean: a mean over speech includes the gaps between the words and understates
 *    the talker by several dB.
 * 3. **Asymmetric rates, all four named.** Floor up [FLOOR_RISE_DB_PER_SECOND] = 3 dB/s (a fan
 *    switching on and adding 15 dB is fully learned in 5 s; a single speech frame that slipped past
 *    the onset guard moves the floor by 0.03 dB), floor down [FLOOR_FALL_DB_PER_SECOND] = 24 dB/s
 *    (a room going quiet by 15 dB is followed in 0.63 s), speech down while talking
 *    [SPEECH_FALL_DB_PER_SECOND] = 6 dB/s (one doubling of distance per second), speech relaxation
 *    while silent [SPEECH_RELAX_DB_PER_SECOND] = 2 dB/s.
 * 4. **The margin is clamped at both ends** -- see [MIN_MARGIN_DB] and [MAX_MARGIN_DB] -- and the
 *    relaxation in silence stops at [MIN_USABLE_GAP_DB] rather than running down to the floor.
 *
 * ### Why the relaxation in silence exists at all, and why it is the slowest rate here
 *
 * Lock 2 alone latches. A loud close talker leaves the speech peak high; he walks away; the gate
 * never opens; and because the peak is only fed while transmitting it can never learn that he got
 * quieter. The relaxation is the way out, and it is deliberately the slowest constant in the class:
 * from a shouting-distance gap of 27 dB down to [MIN_USABLE_GAP_DB] takes 8.5 s of silence. What
 * keeps a single transient from walking through the relaxed gate is not this class but
 * `VadConfig.onsetFrames`, which demands consecutive frames.
 *
 * ### Interaction with AGC2, because two loops on one quantity is the failure class this project removes
 *
 * These are not two controllers on one actuator. AGC2 (inside the WebRTC APM, on whenever echo
 * cancellation is `webrtc`) sets a **gain**; this class sets a **threshold** and never touches gain.
 * One reads what the other writes, which is a cascade. What AGC2 does do is compress the very gap
 * this class measures -- spec 4.1 measured its non-speech output pinned at -45 dBFS whatever the
 * input -- so in that one configuration part of the distance compensation is already done and this
 * tracker converges to a near-constant floor and contributes little. In every other configuration
 * (`none`, `system`) AGC2 is not running and this is the only compensation there is.
 *
 * Single-threaded: the capture thread owns it, like the [VoiceActivityDetector] that holds it.
 */
class AdaptiveVadTracker(
    initialFloorDbfs: Float = DEFAULT_FLOOR_DBFS,
    private val floorRiseDbPerSecond: Float = FLOOR_RISE_DB_PER_SECOND,
    private val floorFallDbPerSecond: Float = FLOOR_FALL_DB_PER_SECOND,
    private val speechFallDbPerSecond: Float = SPEECH_FALL_DB_PER_SECOND,
    private val speechRelaxDbPerSecond: Float = SPEECH_RELAX_DB_PER_SECOND,
) {
    var floorDbfs: Float = initialFloorDbfs.coerceIn(MIN_FLOOR_DBFS, MAX_FLOOR_DBFS)
        private set

    var speechDbfs: Float = floorDbfs + DEFAULT_GAP_DB
        private set

    /** What the threshold is a fraction of. Never less than [MIN_USABLE_GAP_DB]. */
    val gapDb: Float get() = speechDbfs - floorDbfs

    /**
     * True while the talker and the room are close enough together that this design has run out of
     * room to help. The honest answer at that point is "too far away", and the meter shows it.
     */
    val tooClose: Boolean get() = gapDb <= MIN_USABLE_GAP_DB

    /** How far over [floorDbfs] a frame has to be to open the gate, in dB. */
    fun marginDb(fraction: Float): Float = (fraction * gapDb).coerceIn(MIN_MARGIN_DB, MAX_MARGIN_DB)

    fun thresholdDbfs(fraction: Float): Float = floorDbfs + marginDb(fraction)

    /**
     * @param levelDbfs this frame's level, from [VoiceActivityDetector.levelDbfs].
     * @param transmitting the gate's decision for this frame -- taken *before* this call, because
     *   the threshold this frame was judged against is the one the previous frames produced.
     * @param frameMs how long the frame is. Every rate in this class is per second and every step
     *   is scaled by it, so a resampled 20 ms frame moves an estimate exactly twice as far.
     * @param learnFloor false while the user pins the floor by hand. The speech peak keeps moving
     *   -- the threshold is still a fraction of the gap -- but the floor stays where it was put.
     */
    fun update(levelDbfs: Float, transmitting: Boolean, frameMs: Float, learnFloor: Boolean = true) {
        val seconds = frameMs / 1000f
        if (!transmitting && learnFloor) {
            val step = if (levelDbfs > floorDbfs) floorRiseDbPerSecond else floorFallDbPerSecond
            val room = levelDbfs - floorDbfs
            val moved = room.coerceIn(-step * seconds, step * seconds)
            floorDbfs = (floorDbfs + moved).coerceIn(MIN_FLOOR_DBFS, MAX_FLOOR_DBFS)
        }
        speechDbfs = if (transmitting && levelDbfs > speechDbfs) {
            levelDbfs
        } else {
            val rate = if (transmitting) speechFallDbPerSecond else speechRelaxDbPerSecond
            speechDbfs - rate * seconds
        }
        // Both ends: the peak may never sit below the smallest gap the design works in, and it may
        // never claim a level a 16-bit sample cannot carry.
        speechDbfs = speechDbfs.coerceIn(floorDbfs + MIN_USABLE_GAP_DB, MAX_SPEECH_DBFS)
    }

    /**
     * Moves the floor without touching what has been learned about the talker. This is what a
     * hand-set floor uses; [reset] is the user's "measure again" and forgets both.
     */
    fun setFloor(dbfs: Float) {
        floorDbfs = dbfs.coerceIn(MIN_FLOOR_DBFS, MAX_FLOOR_DBFS)
        speechDbfs = speechDbfs.coerceIn(floorDbfs + MIN_USABLE_GAP_DB, MAX_SPEECH_DBFS)
    }

    /** Puts both estimates back where a fresh tracker has them -- the user's "measure again". */
    fun reset(floorDbfs: Float) {
        this.floorDbfs = floorDbfs.coerceIn(MIN_FLOOR_DBFS, MAX_FLOOR_DBFS)
        this.speechDbfs = this.floorDbfs + DEFAULT_GAP_DB
    }

    companion object {
        /**
         * Where a fresh tracker assumes the talker sits, in dB over the floor. Chosen so that the
         * seeded demand is exactly the one spec 4.1 adopted for the fixed window:
         * `DEFAULT_FRACTION * DEFAULT_GAP_DB` = 0.65 * 20 = **13.0 dB**.
         */
        const val DEFAULT_GAP_DB = 20f

        /**
         * The slider's default. See [DEFAULT_GAP_DB]: it is the number that reproduces today's
         * contract at the assumed gap, not a taste.
         */
        const val DEFAULT_FRACTION = 0.65f

        /** Where the non-speech floor of the chain sits with AGC2 running (spec 4.1, measured). */
        const val DEFAULT_FLOOR_DBFS = -45f

        const val FLOOR_RISE_DB_PER_SECOND = 3f
        const val FLOOR_FALL_DB_PER_SECOND = 24f
        const val SPEECH_FALL_DB_PER_SECOND = 6f
        /**
         * The way out of the latch, and the one rate that is a compromise rather than a
         * measurement. From a shouting-distance gap of 27 dB down to [MIN_USABLE_GAP_DB] takes
         * **8.5 s** of silence; a talker who changed position by 12 dB while he was not talking is
         * re-acquired **3.9 s** after he starts again. Faster makes the gate creep open during a
         * pause, slower leaves him inaudible for longer than he will tolerate. What keeps a
         * transient from walking through the relaxed gate is `VadConfig.onsetFrames`, not this.
         */
        const val SPEECH_RELAX_DB_PER_SECOND = 2f

        /**
         * The floor's own measured spread across noise characters is 4.3 dB peak to peak
         * (spec 4.1: -43.5 ... -47.8 dBFS). A margin inside that spread is inside the floor's own
         * noise, so 6 dB is the smallest round number that is still outside it.
         */
        const val MIN_MARGIN_DB = 6f

        /**
         * The width of the window spec 4.1 adopted: `FULL_DBFS (-23.3) - SILENCE_DBFS (-45)`. A
         * margin above it demands more than the chain's own curve calls full-scale speech.
         */
        const val MAX_MARGIN_DB = 21.7f

        /**
         * Where this design stops being able to help, in dB of speech over floor. At the default
         * fraction the margin is already clamped to 6.5 dB here, 2.2 dB above the floor's own
         * 4.3 dB spread. It is also where the relaxation in silence stops.
         */
        const val MIN_USABLE_GAP_DB = 10f

        const val MIN_FLOOR_DBFS = -90f
        const val MAX_FLOOR_DBFS = -20f

        /** A frame of full-scale sine reads about -3 dBFS, so nothing above this is a real level. */
        const val MAX_SPEECH_DBFS = -1f
    }
}
