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
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.mumla.R
import kotlin.math.roundToInt

/**
 * The live input level under the threshold slider, with the marks the tracker moves. The
 * preference, not the recycled view, keeps the last reading so a rebind does not show an empty bar.
 */
class InputLevelMeterPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var meter: LevelMeterView? = null
    private var caption: TextView? = null
    private var reading: MeterReading? = null
    private var hysteresisDb: Float = VadConfig.DEFAULT_HYSTERESIS_DB

    /** The sentence shown instead of a reading, if any. */
    var message: String? = null
        private set

    init {
        layoutResource = R.layout.preference_input_level_meter
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        meter = holder.findViewById(R.id.level_meter) as? LevelMeterView
        caption = holder.findViewById(R.id.level_meter_caption) as? TextView
        apply()
    }

    /** How far below the start threshold the gate closes again, so the middle zone has a width. */
    fun setHysteresisDb(db: Float) {
        hysteresisDb = db
        apply()
    }

    fun setReading(reading: MeterReading?) {
        this.reading = reading
        this.message = null
        apply()
    }

    /** Replaces the reading with a sentence -- no permission, microphone busy, nothing running. */
    fun setMessage(message: String) {
        this.reading = null
        this.message = message
        apply()
    }

    private fun apply() {
        val view = meter ?: return
        val current = reading
        view.show(current, hysteresisDb)
        caption?.text = if (current == null) message.orEmpty() else MeterScaleText.caption(context, current)
    }
}

/** Formats the numbers under the bar. Separate so the rounding is testable without a view. */
object MeterScaleText {
    const val NONE = "—"

    fun db(dbfs: Float): String = "${dbfs.roundToInt()}"

    fun dbOrDash(dbfs: Float?): String = dbfs?.let { db(it) } ?: NONE

    /** The line under the bar: the levels, or the warning that the voice is too close to the room. */
    fun caption(context: Context, reading: MeterReading): String {
        if (reading.tooClose) return context.getString(R.string.inputLevelMeterTooClose)
        return context.getString(
            R.string.inputLevelMeterReading,
            db(reading.levelDbfs),
            dbOrDash(reading.floorDbfs),
            dbOrDash(reading.thresholdDbfs),
            dbOrDash(reading.speechDbfs),
        )
    }
}
