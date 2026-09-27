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
import se.lublin.humla.audio.native.OpusDecoderApi
import se.lublin.humla.audio.native.SpeexJitterApi
import se.lublin.humla.audio.native.SpeexJitterNative
import java.lang.management.ManagementFactory

/**
 * The playback thread decodes and mixes every talker once per mix, so a per-mix allocation risks a
 * GC pause mid-buffer. Same method as the capture-side allocation test: bytes per call over a cold
 * and a hot window, after checking the counter against a known allocation.
 */
class PlaybackAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

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

    private class SilentOpus : OpusDecoderApi {
        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long = 1L
        override fun decodeFloat(
            state: Long,
            data: ByteArray?,
            offset: Int,
            len: Int,
            out: FloatArray,
            frameSize: Int,
            decodeFec: Int,
        ): Int {
            out[0] = 0.1f
            return AudioHandler.FRAME_SIZE
        }
        override fun destroy(state: Long) = Unit
        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = 1
        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = AudioHandler.FRAME_SIZE
    }

    private fun speech(session: Int) =
        AudioOutputSpeech(session, MIX_SAMPLES, { _, _ -> }, SilentOpus(), SilentJitter())

    @Test
    fun `decoding one talker allocates under half an object per mix`() {
        checkInstrument()
        val speech = speech(1)

        val cold = cold { speech.decode() }
        val hot = hot { speech.decode() }
        println(
            "allocation per mix of $MIX_SAMPLES samples (cold / hot): " +
                "AudioOutputSpeech ${"%.3f".format(cold)} / ${"%.3f".format(hot)} B",
        )

        assertWithMessage("AudioOutputSpeech allocates on the playback thread")
            .that(maxOf(cold, hot)).isLessThan(HALF_AN_OBJECT)
    }

    /** The whole mix: three talkers decoded inline, then summed. */
    @Test
    fun `decoding and mixing three talkers allocates under half an object per mix`() {
        checkInstrument()
        val mix = PlaybackMix()
        repeat(3) { mix.add(speech(it)) }
        val out = ShortArray(MIX_SAMPLES)
        val onEnded: (AudioOutputSpeech) -> Unit = { throw AssertionError("no talker ends here") }

        val cold = cold { mix.mixInto(out, 0, MIX_SAMPLES, onEnded) }
        val hot = hot { mix.mixInto(out, 0, MIX_SAMPLES, onEnded) }
        println(
            "allocation per mix of $MIX_SAMPLES samples (cold / hot): " +
                "PlaybackMix of 3 ${"%.3f".format(cold)} / ${"%.3f".format(hot)} B",
        )

        assertWithMessage("the mix allocates on the playback thread")
            .that(maxOf(cold, hot)).isLessThan(HALF_AN_OBJECT)
        assertThat(mix.size).isEqualTo(3)
        assertWithMessage("the talkers must actually have been mixed").that(out[0]).isNotEqualTo(0.toShort())
    }

    private fun checkInstrument() {
        assertThat(threads.isThreadAllocatedMemorySupported).isTrue()
        assertThat(threads.isThreadAllocatedMemoryEnabled).isTrue()
        val sink = arrayOfNulls<Any>(1)
        val instrument = hot { sink[0] = ShortArray(MIX_SAMPLES) }
        assertWithMessage("the allocation counter is not counting; every result would be a false green")
            .that(instrument).isAtLeast(MIX_SAMPLES.toDouble())
    }

    private fun cold(body: () -> Unit) = bytesPerCall(warmups = 500, iterations = 4_000, body = body)

    private fun hot(body: () -> Unit) = bytesPerCall(warmups = 50_000, iterations = 50_000, body = body)

    private fun bytesPerCall(warmups: Int, iterations: Int, body: () -> Unit): Double {
        repeat(warmups) { body() }
        val self = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(self)
        repeat(iterations) { body() }
        val after = threads.getThreadAllocatedBytes(self)
        return (after - before).toDouble() / iterations
    }

    private companion object {
        /** Two frames, what a typical device's minimum track buffer gives a mix. */
        const val MIX_SAMPLES = AudioHandler.FRAME_SIZE * 2

        /** See CaptureThreadAllocationTest: separates a per-call allocation from a one-off. */
        const val HALF_AN_OBJECT = 8.0
    }
}
