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
import kotlin.math.max

/**
 * How loud the user's words come out of a chain in double talk, against the same words spoken
 * alone through the same chain: the level the far end hears drop when it talks itself. The gate
 * share of the older metrics cannot see this; a user 15 dB down still opens the gate.
 *
 * The chain's output for the same words twice, once in double talk and once alone, is compared
 * [BLOCK_FRAMES] frames (100 ms) at a time: the near-end part of the double-talk block is its
 * projection onto the alone block, `g = <dt, alone> / <alone, alone>` (the far end's echo is not
 * correlated with the user's words, so it drops out), and the block keeps `g²` of its power.
 */
internal object NearEndRetention {
    const val BLOCK_FRAMES = 10

    /** A frame counts as dropped when it comes out this much below the same frame alone. */
    const val DROPOUT_DB = 20.0

    private const val FRAMES_PER_SECOND = 100.0

    /** Frames alone quieter than this (mean square, 16-bit units, about -100 dBFS) say nothing. */
    private const val SILENT_POWER = 0.1

    private const val DB_PER_DECADE = 10.0

    /** [DROPOUT_DB] as a power ratio. */
    private val DROPOUT_RATIO = Math.pow(DB_PER_DECADE, -DROPOUT_DB / DB_PER_DECADE)

    /** Below every real result, for an empty or silent comparison. */
    const val NOTHING_DB = -99.0

    class Result(
        /** Near-end power kept in double talk, power-weighted over all blocks, dB. */
        val retentionDb: Double,
        /** The median block's retention, dB: what a typical 100 ms sounds like. */
        val medianBlockDb: Double,
        /** Share of the compared frames more than [DROPOUT_DB] below the same frame alone. */
        val droppedShare: Double,
        /** Runs of dropped frames per second of compared frames. */
        val dropoutsPerSecond: Double,
        val frames: Int,
    )

    /**
     * @param doubleTalk the chain's output in double talk.
     * @param alone the same chain's output for the same words alone.
     * @param doubleTalkStarts where each compared frame starts in [doubleTalk], in time order.
     * @param aloneStarts where the same frame (the same words) starts in [alone].
     */
    fun measure(
        doubleTalk: ShortArray,
        alone: ShortArray,
        doubleTalkStarts: IntArray,
        aloneStarts: IntArray,
        frameSize: Int,
    ): Result {
        require(doubleTalkStarts.size == aloneStarts.size) { "one start in each signal per frame" }
        val n = doubleTalkStarts.size
        if (n == 0) return Result(NOTHING_DB, NOTHING_DB, 0.0, 0.0, 0)
        var kept = 0.0
        var total = 0.0
        val blocks = ArrayList<Double>()
        var dropped = 0
        var dropouts = 0
        var inDropout = false
        var block = 0
        while (block < n) {
            val end = minOf(block + BLOCK_FRAMES, n)
            var cross = 0.0
            var power = 0.0
            for (i in block until end) {
                val d = doubleTalkStarts[i]
                val a = aloneStarts[i]
                var frameDt = 0.0
                var frameAlone = 0.0
                for (k in 0 until frameSize) {
                    val x = doubleTalk[d + k].toDouble()
                    val y = alone[a + k].toDouble()
                    cross += x * y
                    frameDt += x * x
                    frameAlone += y * y
                }
                power += frameAlone
                val down = frameAlone / frameSize > SILENT_POWER &&
                    frameDt < frameAlone * DROPOUT_RATIO
                if (down) dropped++
                if (down && !inDropout) dropouts++
                inDropout = down
            }
            if (power / ((end - block) * frameSize) > SILENT_POWER) {
                val g = max(cross / power, 0.0)
                kept += g * g * power
                total += power
                blocks += db(g * g)
            }
            block = end
        }
        blocks.sort()
        return Result(
            retentionDb = if (total > 0) db(kept / total) else NOTHING_DB,
            medianBlockDb = if (blocks.isEmpty()) NOTHING_DB else blocks[blocks.size / 2],
            droppedShare = dropped.toDouble() / n,
            dropoutsPerSecond = dropouts / (n / FRAMES_PER_SECOND),
            frames = n,
        )
    }

    private fun db(ratio: Double): Double = if (ratio > 0) max(DB_PER_DECADE * log10(ratio), NOTHING_DB) else NOTHING_DB
}
