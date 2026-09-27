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
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import se.lublin.humla.audio.MeterReading
import se.lublin.mumla.R

private const val MARK_WIDTH_PX = 4f

/** The level bar is drawn inside the zones so both stay readable. */
private const val BAR_INSET = 0.25f

private const val DEFAULT_START = 0.5f
private const val DEFAULT_STOP = 0.4f

/**
 * Maps a level in dBFS onto the meter's horizontal position. Fixed span, not auto-ranging, so the
 * marks stay still enough to compare the threshold with the voice.
 */
object MeterScale {
    /** Everything below about -70 dBFS looks the same: nothing. */
    const val BOTTOM_DBFS = -70f
    const val TOP_DBFS = 0f

    /** @return [dbfs] as a fraction of the bar's width, clamped into it. */
    fun position(dbfs: Float): Float = ((dbfs - BOTTOM_DBFS) / (TOP_DBFS - BOTTOM_DBFS)).coerceIn(0f, 1f)
}

/**
 * The bar under the threshold slider: the live level, the three zones the gate divides the range
 * into (speech, stays open, none), and the two marks the tracker moves. Every setter clamps and
 * invalidates.
 */
class LevelMeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Where the gate opens. Everything above it is the "speech" zone. */
    var startThreshold: Float = DEFAULT_START
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Where the gate closes again. Between it and [startThreshold] is the "stays open" zone. */
    var stopThreshold: Float = DEFAULT_STOP
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** The tracked noise floor, or null in a mode that does not track one. */
    var floorMark: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)
            invalidate()
        }

    /** The tracked speech peak, or null in a mode that does not track one. */
    var speechMark: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)
            invalidate()
        }

    var voice: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** True while the gate is open only because of the hold; the bar then paints the middle zone. */
    var holding: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private fun paint(@ColorRes color: Int, strokeWidth: Float = 0f) = Paint().apply {
        this.color = ContextCompat.getColor(context, color)
        this.strokeWidth = strokeWidth
    }

    private val silentZonePaint = paint(R.color.level_meter_silent_zone)
    private val holdZonePaint = paint(R.color.level_meter_hold_zone)
    private val voiceZonePaint = paint(R.color.level_meter_voice_zone)
    private val idleLevelPaint = paint(R.color.level_meter_idle_level)
    private val holdLevelPaint = paint(R.color.level_meter_hold_level)
    private val voiceLevelPaint = paint(R.color.level_meter_voice_level)
    private val floorPaint = paint(R.color.level_meter_floor, MARK_WIDTH_PX)
    private val speechPaint = paint(R.color.level_meter_speech, MARK_WIDTH_PX)
    private val thresholdPaint = paint(R.color.level_meter_threshold, MARK_WIDTH_PX)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val stop = minOf(stopThreshold, startThreshold)

        canvas.drawRect(0f, 0f, w * stop, h, silentZonePaint)
        canvas.drawRect(w * stop, 0f, w * startThreshold, h, holdZonePaint)
        canvas.drawRect(w * startThreshold, 0f, w, h, voiceZonePaint)

        val levelPaint = when {
            voice && holding -> holdLevelPaint
            voice -> voiceLevelPaint
            else -> idleLevelPaint
        }
        canvas.drawRect(0f, h * BAR_INSET, w * level, h * (1f - BAR_INSET), levelPaint)

        floorMark?.let { canvas.drawLine(w * it, 0f, w * it, h, floorPaint) }
        speechMark?.let { canvas.drawLine(w * it, 0f, w * it, h, speechPaint) }
        canvas.drawLine(w * startThreshold, 0f, w * startThreshold, h, thresholdPaint)
    }

    /**
     * Shows [reading], or an empty bar for null. [hysteresisDb] is how far below the start threshold
     * the gate closes again, so the middle zone has a width.
     */
    fun show(reading: MeterReading?, hysteresisDb: Float) {
        if (reading == null) {
            level = 0f
            voice = false
            holding = false
            floorMark = null
            speechMark = null
            return
        }
        level = MeterScale.position(reading.levelDbfs)
        voice = reading.voice
        holding = reading.holding
        floorMark = reading.floorDbfs?.let { MeterScale.position(it) }
        speechMark = reading.speechDbfs?.let { MeterScale.position(it) }
        // No level threshold in this mode: zero both so the whole range paints as "speech".
        val threshold = reading.thresholdDbfs
        startThreshold = threshold?.let { MeterScale.position(it) } ?: 0f
        stopThreshold = threshold?.let { MeterScale.position(it - hysteresisDb) } ?: 0f
    }
}
