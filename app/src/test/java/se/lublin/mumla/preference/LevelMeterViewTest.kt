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

package se.lublin.mumla.preference

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.AdaptiveVadTracker
import se.lublin.humla.audio.capture.VoiceActivityDetector

@RunWith(RobolectricTestRunner::class)
class LevelMeterViewTest {
    private val view = LevelMeterView(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `the scale maps its two ends onto the two ends of the bar`() {
        assertThat(MeterScale.position(MeterScale.BOTTOM_DBFS)).isEqualTo(0f)
        assertThat(MeterScale.position(MeterScale.TOP_DBFS)).isEqualTo(1f)
        assertThat(MeterScale.position(-35f)).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `the scale clamps rather than painting outside the bar`() {
        assertThat(MeterScale.position(-200f)).isEqualTo(0f)
        assertThat(MeterScale.position(20f)).isEqualTo(1f)
        // The two levels the pipeline can really produce at the extremes both land inside.
        assertThat(MeterScale.position(VoiceActivityDetector.NO_SIGNAL_DBFS)).isEqualTo(0f)
        assertThat(MeterScale.position(AdaptiveVadTracker.MIN_FLOOR_DBFS)).isEqualTo(0f)
        assertThat(MeterScale.position(AdaptiveVadTracker.MAX_FLOOR_DBFS)).isGreaterThan(0f)
    }

    /**
     * The interesting range has to be visible, not squeezed into a corner: the measured floor and a
     * normal talker must sit apart from each other and away from both ends.
     */
    @Test
    fun `the default floor and a normal talker are far apart in the middle of the bar`() {
        val floor = MeterScale.position(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS)
        val talker = MeterScale.position(-20f)
        assertThat(floor).isIn(com.google.common.collect.Range.closed(0.2f, 0.5f))
        assertThat(talker).isIn(com.google.common.collect.Range.closed(0.6f, 0.9f))
        assertThat(talker - floor).isGreaterThan(0.25f)
    }

    @Test
    fun `level and thresholds are clamped to the unit range`() {
        view.level = 1.7f
        view.startThreshold = -0.2f
        view.stopThreshold = 2f
        assertThat(view.level).isEqualTo(1f)
        assertThat(view.startThreshold).isEqualTo(0f)
        assertThat(view.stopThreshold).isEqualTo(1f)
    }

    @Test
    fun `the two tracked marks clamp too, and null stays null`() {
        view.floorMark = -3f
        view.speechMark = 9f
        assertThat(view.floorMark).isEqualTo(0f)
        assertThat(view.speechMark).isEqualTo(1f)
        view.floorMark = null
        view.speechMark = null
        assertThat(view.floorMark).isNull()
        assertThat(view.speechMark).isNull()
    }

    /** Every setter has to redraw, or the bar stops moving without anything failing. */
    @Test
    fun `every property invalidates the view`() {
        val writes: List<Pair<String, () -> Unit>> = listOf(
            "level" to { view.level = 0.3f },
            "startThreshold" to { view.startThreshold = 0.31f },
            "stopThreshold" to { view.stopThreshold = 0.32f },
            "floorMark" to { view.floorMark = 0.33f },
            "speechMark" to { view.speechMark = 0.34f },
            "voice" to { view.voice = !view.voice },
            "holding" to { view.holding = !view.holding },
        )
        val shadow = org.robolectric.Shadows.shadowOf(view)
        for ((name, write) in writes) {
            shadow.clearWasInvalidated()
            assertThat(shadow.wasInvalidated()).isFalse()
            write()
            assertThat(shadow.wasInvalidated()).isTrue()
        }
        assertThat(writes.map { it.first }).hasSize(7)
    }

    @Test
    fun `a level and its marks survive a draw`() {
        view.level = 0.5f
        view.startThreshold = 0.7f
        view.stopThreshold = 0.6f
        view.floorMark = 0.3f
        view.speechMark = 0.9f
        view.voice = true
        view.holding = true
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(20, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 200, 20)
        view.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(200, 20, android.graphics.Bitmap.Config.ARGB_8888)))
        assertThat(view.level).isEqualTo(0.5f)
        assertThat(view.floorMark).isEqualTo(0.3f)
    }

    @Test
    fun `the numbers under the bar round to whole dB and say so when there is none`() {
        assertThat(MeterScaleText.db(-32.4f)).isEqualTo("-32")
        assertThat(MeterScaleText.db(-32.6f)).isEqualTo("-33")
        assertThat(MeterScaleText.dbOrDash(null)).isEqualTo(MeterScaleText.NONE)
        assertThat(MeterScaleText.dbOrDash(-45f)).isEqualTo("-45")
    }
}
