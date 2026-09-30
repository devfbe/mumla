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
 * Eases RNNoise's attenuation limit while the user talks over the far end (double talk).
 *
 * In double talk on speakerphone RNNoise takes the user down by up to its whole limit: in a real
 * room (`RoomAcousticsDeviceTest`'s recordings, VOICE_COMMUNICATION, replayed offline) the user's
 * voice came out 5 / 13 / 4 dB below the same words spoken alone (loud / mid / quiet talker), 8 dB
 * of the mid talker's loss in RNNoise. The remote side hears that as the user fading out whenever
 * they speak themselves. So while the far end talks ([FarEndActivity]) and the signal in front of
 * RNNoise shows the user speaking, the limit moves to [reliefLimitDb]; otherwise it is the user's.
 *
 * The user speaks when three frames in a row (the one RNNoise is about to output and the two it
 * still holds, so the relief starts with the first word) each open an adaptive level gate
 * ([VoiceActivityDetector], no onset, no hold) and reach [NEAR_FLOOR_DBFS]. That rejects the short
 * bursts of echo the platform lets through its half duplex, which would otherwise pass unattenuated
 * (measured: a 20 ms leak held the voice gate open for 250 ms). The relief then holds while any of
 * the three frames still qualifies; it fades in within about 20 ms and out with a 60 ms time
 * constant, so it neither clips the first syllable nor pumps between words.
 *
 * Nothing changes while the far end is silent, so talking alone and noise alone go through exactly
 * the user's limit. Capture thread only; allocates nothing per frame.
 */
internal class DoubleTalkRelief(
    private val farEnd: FarEndActivity,
    private val reliefLimitDb: Float = RELIEF_LIMIT_DB,
    clock: NanoClock = NanoClock(System::nanoTime),
) {
    private val nearEnd = VoiceActivityDetector(VadConfig.adaptive(onsetFrames = 1, holdTimeMs = 0L), clock = clock)

    /** The last [EVIDENCE_FRAMES] input frames' verdicts, newest in bit 0. */
    private var evidence = 0

    private var speaking = false

    /** How far the limit has moved from the user's toward [reliefLimitDb], 0 to 1. */
    var amount: Float = 0f
        private set

    /** Whether the relief is engaged for the frame now being output. */
    var engaged: Boolean = false
        private set

    /**
     * Takes the next input frame (before RNNoise) and returns the limit, in dB, for the output frame
     * due now. [userLimitDb] may be [Float.POSITIVE_INFINITY]; without relief it comes back as is.
     */
    fun limitFor(input: ShortArray, length: Int, userLimitDb: Float): Float {
        val voiced = nearEnd.isVoice(input, length, null) && nearEnd.lastLevelDbfs >= NEAR_FLOOR_DBFS
        evidence = ((evidence shl 1) or (if (voiced) 1 else 0)) and ALL_EVIDENCE
        speaking = when (evidence) {
            ALL_EVIDENCE -> true
            0 -> false
            else -> speaking
        }
        engaged = speaking && farEnd.isActive()
        val target = if (engaged) 1f else 0f
        amount += (if (target > amount) RELAX_PER_FRAME else RESTORE_PER_FRAME) * (target - amount)
        if (amount < SNAP) amount = 0f
        if (amount > 1f - SNAP) amount = 1f
        if (amount == 0f || userLimitDb <= reliefLimitDb) return userLimitDb
        val from = if (userLimitDb.isInfinite()) UNLIMITED_AS_DB else userLimitDb
        return from + amount * (reliefLimitDb - from)
    }

    companion object {
        /** RNNoise stands aside entirely while the user talks over the far end. */
        const val RELIEF_LIMIT_DB = 0f

        /**
         * The user's frames must reach this level in front of RNNoise (after AGC2). The echo bursts
         * the platform leaks came up to about -53 dBFS there, the user in double talk mostly above.
         */
        const val NEAR_FLOOR_DBFS = -60f

        /** RNNoise's latency in frames, plus the frame it is given now. */
        const val EVIDENCE_FRAMES = 3
        private const val ALL_EVIDENCE = (1 shl EVIDENCE_FRAMES) - 1

        /** Share of the remaining way covered per 10 ms frame, toward the relief and back. */
        const val RELAX_PER_FRAME = 0.6f
        const val RESTORE_PER_FRAME = 0.15f

        /** Close enough to count as there. */
        private const val SNAP = 1e-3f

        /** Stands in for "no limit" while fading, where RNNoise's own attenuation rarely goes deeper. */
        private const val UNLIMITED_AS_DB = 60f
    }
}
