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

package se.lublin.humla.audio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative
import se.lublin.humla.testutil.AllocationMeter
import se.lublin.humla.testutil.AllocationMeter.HALF_AN_OBJECT
import se.lublin.humla.testutil.AllocationMeter.checkInstrument
import se.lublin.humla.testutil.AllocationMeter.worstPerCall
import se.lublin.humla.testutil.FakeOpusDecoder

/** The playback thread decodes and mixes every talker once per mix; see [AllocationMeter]. */
class PlaybackAllocationTest {
    /** Always has a packet: one opus frame, then a volume adjustment of 1 and no terminator. */
    private class SilentJitter : SpeexJitterApi {
        private val packet = byteArrayOf(0x41, 0x42, 0x43, 0x3F, 0x80.toByte(), 0, 0, 0)
        override fun init(stepSize: Int): Long = 1L
        override fun destroy(handle: Long) = Unit
        override fun put(
            handle: Long,
            data: ByteArray,
            len: Int,
            timestamp: Int,
            span: Int,
            sequence: Int,
            userData: Int,
        ) = Unit
        override fun get(handle: Long, out: ByteArray, desiredSpan: Int, meta: IntArray): Int {
            packet.copyInto(out)
            meta[0] = packet.size
            meta[4] = 0
            return SpeexJitterNative.JITTER_BUFFER_OK
        }
        override fun pointerTimestamp(handle: Long): Int = 1
        override fun tick(handle: Long) = Unit
        override fun ctl(handle: Long, request: Int, value: IntArray): Int {
            value[0] = 3
            return 0
        }
        override fun updateDelay(handle: Long): Int = 0
    }

    private fun speech(session: Int) =
        AudioOutputSpeech(session, MIX_SAMPLES, { _, _ -> }, FakeOpusDecoder(fill = 0.1f), SilentJitter())

    @Test
    fun `decoding one talker allocates under half an object per mix`() {
        checkInstrument(MIX_SAMPLES)
        val speech = speech(1)

        assertWithMessage("AudioOutputSpeech allocates on the playback thread")
            .that(worstPerCall("AudioOutputSpeech", HOT_CALLS) { speech.decode() }).isLessThan(HALF_AN_OBJECT)
    }

    /** The whole mix: three talkers decoded inline, then summed. */
    @Test
    fun `decoding and mixing three talkers allocates under half an object per mix`() {
        checkInstrument(MIX_SAMPLES)
        val mix = PlaybackMix()
        repeat(3) { mix.add(speech(it)) }
        val out = ShortArray(MIX_SAMPLES)
        val onEnded: (AudioOutputSpeech) -> Unit = { throw AssertionError("no talker ends here") }

        assertWithMessage("the mix allocates on the playback thread")
            .that(worstPerCall("PlaybackMix of 3", HOT_CALLS) { mix.mixInto(out, 0, MIX_SAMPLES, onEnded) })
            .isLessThan(HALF_AN_OBJECT)
        assertThat(mix.size).isEqualTo(3)
        assertWithMessage("the talkers must actually have been mixed").that(out[0]).isNotEqualTo(0.toShort())
    }

    private companion object {
        /** Two frames, what a typical device's minimum track buffer gives a mix. */
        const val MIX_SAMPLES = AudioHandler.FRAME_SIZE * 2

        const val HOT_CALLS = 50_000
    }
}
