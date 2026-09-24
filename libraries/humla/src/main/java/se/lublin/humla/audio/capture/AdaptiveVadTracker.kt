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
 * Tracks the noise floor and recent speech peak (dBFS) and puts the transmit threshold at a fraction
 * of the gap between them: distance attenuates the talker but not the room, so the gap stays
 * meaningful as the talker moves. The floor is learned only while not transmitting; the speech peak
 * rises instantly while transmitting and decays otherwise. Sets a threshold, never gain, so it
 * cascades with AGC2. Capture thread only.
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

    /** Never less than [MIN_USABLE_GAP_DB]. */
    val gapDb: Float get() = speechDbfs - floorDbfs

    /** The speech-to-floor gap is too small for the gate to help. */
    val tooClose: Boolean get() = gapDb <= MIN_USABLE_GAP_DB

    fun marginDb(fraction: Float): Float = (fraction * gapDb).coerceIn(MIN_MARGIN_DB, MAX_MARGIN_DB)

    fun thresholdDbfs(fraction: Float): Float = floorDbfs + marginDb(fraction)

    /**
     * @param levelDbfs this frame's level, from [VoiceActivityDetector.levelDbfs].
     * @param transmitting the gate's decision for this frame, made before this call.
     * @param frameMs frame duration; all rates are per second and scaled by it.
     * @param learnFloor false while the user pins the floor by hand (the speech peak still moves).
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
        speechDbfs = speechDbfs.coerceIn(floorDbfs + MIN_USABLE_GAP_DB, MAX_SPEECH_DBFS)
    }

    /** Moves the floor (hand-set floor) without forgetting the speech peak; [reset] forgets both. */
    fun setFloor(dbfs: Float) {
        floorDbfs = dbfs.coerceIn(MIN_FLOOR_DBFS, MAX_FLOOR_DBFS)
        speechDbfs = speechDbfs.coerceIn(floorDbfs + MIN_USABLE_GAP_DB, MAX_SPEECH_DBFS)
    }

    fun reset(floorDbfs: Float) {
        this.floorDbfs = floorDbfs.coerceIn(MIN_FLOOR_DBFS, MAX_FLOOR_DBFS)
        this.speechDbfs = this.floorDbfs + DEFAULT_GAP_DB
    }

    companion object {
        /** Assumed initial speech-over-floor gap in dB; with [DEFAULT_FRACTION] gives a 13 dB margin. */
        const val DEFAULT_GAP_DB = 20f

        const val DEFAULT_FRACTION = 0.65f

        /** Typical non-speech floor of the chain with AGC2 running. */
        const val DEFAULT_FLOOR_DBFS = -45f

        const val FLOOR_RISE_DB_PER_SECOND = 3f
        const val FLOOR_FALL_DB_PER_SECOND = 24f
        const val SPEECH_FALL_DB_PER_SECOND = 6f
        /** Faster lets the gate creep open during a pause; slower keeps a quieter talker muted longer. */
        const val SPEECH_RELAX_DB_PER_SECOND = 2f

        /** Just above the floor's own spread across noise types (~4.3 dB peak to peak). */
        const val MIN_MARGIN_DB = 6f

        /** `FULL_DBFS (-23.3) - SILENCE_DBFS (-45)`: more would demand above full-scale speech. */
        const val MAX_MARGIN_DB = 21.7f

        const val MIN_USABLE_GAP_DB = 10f

        const val MIN_FLOOR_DBFS = -90f
        const val MAX_FLOOR_DBFS = -20f

        /** A frame of full-scale sine reads about -3 dBFS, so nothing above this is a real level. */
        const val MAX_SPEECH_DBFS = -1f
    }
}
