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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.audio.capture.fakes.FakeSpeexResamplerApi
import se.lublin.humla.audio.capture.fakes.RefusingSpeexResamplerApi

/**
 * The adapter is tested against [se.lublin.humla.audio.native.SpeexResamplerApi], since the native
 * object loads its library in `init`. Beyond forwarding, it checks speex's error code before
 * trusting the output count.
 */
class SpeexResamplerTest {
    @Test
    fun `it creates one mono state at the rates it was given`() {
        val api = FakeSpeexResamplerApi()

        SpeexResampler(16000, 48000, api = api)

        assertThat(api.initCalls).hasSize(1)
        assertThat(api.initCalls.single().asList())
            .containsExactly(1, 16000, 48000, SpeexResampler.DEFAULT_QUALITY).inOrder()
    }

    @Test
    fun `a quality other than the default reaches speex`() {
        val api = FakeSpeexResamplerApi()

        SpeexResampler(48000, 16000, quality = 7, api = api)

        assertThat(api.initCalls.single().asList()).containsExactly(1, 48000, 16000, 7).inOrder()
    }

    @Test
    fun `a state speex could not create is refused at construction`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            SpeexResampler(16000, 48000, api = RefusingSpeexResamplerApi())
        }
        assertThat(failure).hasMessageThat().contains("16000")
        assertThat(failure).hasMessageThat().contains("48000")
    }

    /**
     * The input array is longer than the count, as in production, so `inputLength` and
     * `input.size` cannot be confused unnoticed.
     */
    @Test
    fun `it passes the input length and the output capacity and returns what speex produced`() {
        val api = FakeSpeexResamplerApi(produced = 300, fill = 9)
        val resampler = SpeexResampler(16000, 48000, api = api)
        val out = ShortArray(480)

        val produced = resampler.resample(ShortArray(480), 160, out)

        assertThat(produced).isEqualTo(300)
        assertThat(api.lastInLength).isEqualTo(160)
        assertThat(api.lastOutCapacity).isEqualTo(480)
        assertThat(api.lastChannelIndex).isEqualTo(0)
        assertThat(out[0]).isEqualTo(9.toShort())
    }

    /**
     * On error the bridge returns before writing `outLen`, so the requested capacity is still
     * there; trusting it would report a full frame that still holds the previous frame's samples.
     */
    @Test
    fun `an error code produces no samples, not the capacity it was asked for`() {
        for (code in intArrayOf(FakeSpeexResamplerApi.ERR_INVALID_ARG, FakeSpeexResamplerApi.ERR_ALLOC_FAILED)) {
            val api = FakeSpeexResamplerApi(errorCode = code)
            val resampler = SpeexResampler(16000, 48000, api = api)

            val produced = resampler.resample(ShortArray(160), 160, ShortArray(480))

            assertWithMessage("speex error %s must not read as a full frame", code).that(produced).isEqualTo(0)
            assertWithMessage("the call still has to reach speex").that(api.processCalls).isEqualTo(1)
        }
    }

    @Test
    fun `release destroys the state once`() {
        val api = FakeSpeexResamplerApi(handle = 42L)
        val resampler = SpeexResampler(16000, 48000, api = api)

        resampler.close()
        resampler.close()

        assertThat(api.destroyed).containsExactly(42L)
    }

    @Test
    fun `resampling after release produces no samples`() {
        val api = FakeSpeexResamplerApi(produced = 480, fill = 5)
        val resampler = SpeexResampler(16000, 48000, api = api)
        val out = ShortArray(480)
        resampler.close()

        val produced = resampler.resample(ShortArray(160), 160, out)

        assertThat(produced).isEqualTo(0)
        assertThat(out[0]).isEqualTo(0.toShort())
    }
}
