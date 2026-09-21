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
import android.view.LayoutInflater
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.R
import se.lublin.mumla.audio.MeterReading

/**
 * The effect pass for the meter: every field of a [MeterReading] has to arrive on the bar, and the
 * test that reads it back.
 *
 * Without this the preference is exactly the shape spec 4.04 warns about -- a file that writes into
 * an object it does not own (a `View`) and never reads the result, so every mutation in it survives
 * whatever else the suite does.
 */
@RunWith(RobolectricTestRunner::class)
class InputLevelMeterPreferenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preference: InputLevelMeterPreference
    private lateinit var meter: LevelMeterView
    private lateinit var caption: TextView

    @Before
    fun bind() {
        preference = InputLevelMeterPreference(context, null)
        val view = LayoutInflater.from(context).inflate(R.layout.preference_input_level_meter, null)
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(view))
        meter = view.findViewById(R.id.level_meter)
        caption = view.findViewById(R.id.level_meter_caption)
    }

    private fun reading(
        levelDbfs: Float = -30f,
        floorDbfs: Float? = -45f,
        speechDbfs: Float? = -20f,
        thresholdDbfs: Float? = -32f,
        voice: Boolean = true,
        holding: Boolean = false,
        tooClose: Boolean = false,
    ) = MeterReading(levelDbfs, floorDbfs, speechDbfs, thresholdDbfs, voice, holding, tooClose)

    @Test
    fun `every field of a reading reaches the bar`() {
        preference.setReading(reading())
        assertThat(meter.level).isWithin(0.001f).of(MeterScale.position(-30f))
        assertThat(meter.floorMark!!).isWithin(0.001f).of(MeterScale.position(-45f))
        assertThat(meter.speechMark!!).isWithin(0.001f).of(MeterScale.position(-20f))
        assertThat(meter.startThreshold).isWithin(0.001f).of(MeterScale.position(-32f))
        assertThat(meter.voice).isTrue()
        assertThat(meter.holding).isFalse()
    }

    @Test
    fun `the hold zone is as wide as the configured hysteresis`() {
        preference.setHysteresisDb(6f)
        preference.setReading(reading(thresholdDbfs = -32f))
        assertThat(meter.stopThreshold).isWithin(0.001f).of(MeterScale.position(-38f))

        preference.setHysteresisDb(12f)
        assertThat(meter.stopThreshold).isWithin(0.001f).of(MeterScale.position(-44f))
    }

    @Test
    fun `the two zone flags are not interchangeable`() {
        preference.setReading(reading(voice = true, holding = true))
        assertThat(meter.voice).isTrue()
        assertThat(meter.holding).isTrue()

        preference.setReading(reading(voice = true, holding = false))
        assertThat(meter.voice).isTrue()
        assertThat(meter.holding).isFalse()

        preference.setReading(reading(voice = false, holding = false))
        assertThat(meter.voice).isFalse()
        assertThat(meter.holding).isFalse()
    }

    /** A mode with no level threshold must not be drawn with one it does not use. */
    @Test
    fun `a reading with no threshold draws no zone boundary and no marks`() {
        preference.setReading(reading(floorDbfs = null, speechDbfs = null, thresholdDbfs = null))
        assertThat(meter.floorMark).isNull()
        assertThat(meter.speechMark).isNull()
        assertThat(meter.startThreshold).isEqualTo(0f)
        assertThat(meter.stopThreshold).isEqualTo(0f)
    }

    @Test
    fun `clearing the reading empties the bar instead of freezing it`() {
        preference.setReading(reading(levelDbfs = -10f))
        assertThat(meter.level).isGreaterThan(0f)
        preference.setReading(null)
        assertThat(meter.level).isEqualTo(0f)
        assertThat(meter.voice).isFalse()
        assertThat(meter.floorMark).isNull()
        assertThat(meter.speechMark).isNull()
    }

    @Test
    fun `the caption names all four levels`() {
        preference.setReading(reading(levelDbfs = -30.2f, floorDbfs = -45f, thresholdDbfs = -32f, speechDbfs = -20f))
        assertThat(caption.text.toString()).contains("-30")
        assertThat(caption.text.toString()).contains("-45")
        assertThat(caption.text.toString()).contains("-32")
        assertThat(caption.text.toString()).contains("-20")
    }

    @Test
    fun `too far away replaces the numbers with the sentence that explains it`() {
        preference.setReading(reading(tooClose = true))
        assertThat(caption.text.toString()).isEqualTo(context.getString(R.string.inputLevelMeterTooClose))
    }

    @Test
    fun `a message replaces the reading rather than sitting next to a stale one`() {
        preference.setReading(reading(levelDbfs = -10f))
        preference.setMessage("no microphone")
        assertThat(caption.text.toString()).isEqualTo("no microphone")
        assertThat(meter.level).isEqualTo(0f)
    }

    /** A reading that arrives while the row is off screen must not be lost when it comes back. */
    @Test
    fun `a reading pushed before the view exists is shown when the view is bound`() {
        val fresh = InputLevelMeterPreference(context, null)
        fresh.setReading(reading(levelDbfs = -12f))
        val view = LayoutInflater.from(context).inflate(R.layout.preference_input_level_meter, null)
        fresh.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(view))
        val bar = view.findViewById<LevelMeterView>(R.id.level_meter)
        assertThat(bar.level).isWithin(0.001f).of(MeterScale.position(-12f))
    }
}
