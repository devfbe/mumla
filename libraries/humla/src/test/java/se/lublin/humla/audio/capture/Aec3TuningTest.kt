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
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test
import se.lublin.humla.audio.capture.fakes.FakeWebRtcApmApi

/** The AEC3 tuning as it crosses into `humla_apm_create`. */
class Aec3TuningTest {

    /**
     * The array is indexed by ordinal on this side and by the `HUMLA_AEC3_*` enum on the native
     * side; a missing, extra or reordered name would silently set the wrong field.
     */
    @Test
    fun `the parameters are the HUMLA_AEC3 enum of humla_apm_h, name for name and in order`() {
        val header = File("src/main/cpp/webrtc_apm/humla_apm.h").readText()
        val names = Regex("""^\s*HUMLA_AEC3_(\w+)\s*,""", RegexOption.MULTILINE)
            .findAll(header).map { it.groupValues[1] }.toList()

        assertThat(header).contains("HUMLA_AEC3_PARAM_COUNT\n")
        assertThat(Aec3Param.entries.map { it.name }).containsExactlyElementsIn(names).inOrder()
    }

    @Test
    fun `the default tuning is webrtc's default config`() {
        val defaults = Aec3Tuning.DEFAULT.toArray()

        assertThat(defaults.size).isEqualTo(Aec3Param.entries.size)
        // A few values written out from api/audio/echo_canceller3_config.h.
        assertThat(defaults[Aec3Param.NORMAL_HF_ENR_TRANSPARENT.ordinal]).isEqualTo(0.07f)
        assertThat(defaults[Aec3Param.NEAREND_LF_ENR_SUPPRESS.ordinal]).isEqualTo(1.1f)
        assertThat(defaults[Aec3Param.DNE_HOLD_DURATION.ordinal]).isEqualTo(50f)
        assertThat(defaults[Aec3Param.FILTER_REFINED_LENGTH_BLOCKS.ordinal]).isEqualTo(13f)
        assertThat(Aec3Tuning.DEFAULT.toString()).isEqualTo("Aec3Tuning()")
    }

    @Test
    fun `with changes only the named parameters and leaves the original alone`() {
        val tuned = Aec3Tuning.DEFAULT.with(Aec3Param.DNE_ENR_THRESHOLD to 0.5f, Aec3Param.DNE_HOLD_DURATION to 25f)

        assertThat(tuned[Aec3Param.DNE_ENR_THRESHOLD]).isEqualTo(0.5f)
        assertThat(tuned[Aec3Param.DNE_HOLD_DURATION]).isEqualTo(25f)
        assertThat(tuned[Aec3Param.DNE_SNR_THRESHOLD]).isEqualTo(Aec3Param.DNE_SNR_THRESHOLD.default)
        assertThat(Aec3Tuning.DEFAULT[Aec3Param.DNE_ENR_THRESHOLD]).isEqualTo(0.25f)
        assertThat(tuned.toString()).isEqualTo("Aec3Tuning(DNE_ENR_THRESHOLD=0.5, DNE_HOLD_DURATION=25.0)")
    }

    @Test
    fun `equality is by value and survives the round trip through an array`() {
        val tuned = Aec3Tuning.DEFAULT.with(Aec3Param.EP_BOUNDED_ERL to 1f)
        val array = tuned.toArray()
        array[0] = 99f // a copy: writing to it must not change the tuning

        assertThat(Aec3Tuning.fromArray(tuned.toArray())).isEqualTo(tuned)
        assertThat(Aec3Tuning.fromArray(tuned.toArray()).hashCode()).isEqualTo(tuned.hashCode())
        assertThat(tuned).isNotEqualTo(Aec3Tuning.DEFAULT)
        assertThat(tuned[Aec3Param.entries[0]]).isEqualTo(Aec3Param.entries[0].default)
    }

    @Test
    fun `an array of the wrong size is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            Aec3Tuning.fromArray(FloatArray(Aec3Param.entries.size - 1))
        }
    }

    @Test
    fun `no tuning reaches the bridge as null, a tuning as its values in parameter order`() {
        val tuned = Aec3Tuning.DEFAULT.with(Aec3Param.NEAREND_HF_ENR_SUPPRESS to 0.9f)
        val plain = FakeWebRtcApmApi()
        val withTuning = FakeWebRtcApmApi()

        WebRtcApmPreprocessor(plain, WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(aec3 = null))
        WebRtcApmPreprocessor(withTuning, WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(aec3 = tuned))

        assertThat(plain.createdWith!!.second.aec3).isNull()
        assertThat(withTuning.createdWith!!.second.aec3).isEqualTo(tuned)
    }
}
