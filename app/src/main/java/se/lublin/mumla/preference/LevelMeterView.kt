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
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Maps a level in dBFS onto the meter's horizontal position.
 *
 * The span is fixed rather than auto-ranging: an auto-ranging bar moves both the reading and the
 * marks at once, so nothing on it stays still long enough to be read, and "the threshold is here
 * and my voice is there" -- the only question this bar exists to answer -- stops being visible.
 */
object MeterScale {
    /**
     * Quieter than a phone microphone reports in a silent room. [AdaptiveVadTracker] clamps its
     * floor estimate at -90 dBFS, but everything below about -70 is the same picture: nothing.
     */
    const val BOTTOM_DBFS = -70f
    const val TOP_DBFS = 0f

    /** @return [dbfs] as a fraction of the bar's width, clamped into it. */
    fun position(dbfs: Float): Float = ((dbfs - BOTTOM_DBFS) / (TOP_DBFS - BOTTOM_DBFS)).coerceIn(0f, 1f)
}

/**
 * The bar under the threshold slider: the live level, the three zones the gate divides the range
 * into, and the two marks the tracker moves.
 *
 * The zones are what the user asked to see -- *"the levels split into zones (here is speech, here
 * speech stays active, here is none), which change adaptively"* -- so they are painted as bands
 * behind the level rather than as a single line, and they move with [startThreshold] and
 * [stopThreshold] as the tracker learns.
 *
 * Every setter clamps and invalidates. The clamp is not decoration: [MeterScale] hands over a
 * fraction, but a caller that computed one itself and got it wrong would paint outside the view
 * instead of failing, and nothing downstream would notice.
 */
class LevelMeterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Where the gate opens. Everything above it is the "speech" zone. */
    var startThreshold: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Where the gate closes again. Between it and [startThreshold] is the "stays open" zone. */
    var stopThreshold: Float = 0.4f
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

    private val silentZonePaint = Paint().apply { color = Color.argb(48, 128, 128, 128) }
    private val holdZonePaint = Paint().apply { color = Color.argb(64, 255, 152, 0) }
    private val voiceZonePaint = Paint().apply { color = Color.argb(64, 76, 175, 80) }
    private val idleLevelPaint = Paint().apply { color = Color.rgb(96, 125, 139) }
    private val holdLevelPaint = Paint().apply { color = Color.rgb(255, 152, 0) }
    private val voiceLevelPaint = Paint().apply { color = Color.rgb(76, 175, 80) }
    private val floorPaint = Paint().apply {
        color = Color.rgb(120, 144, 156)
        strokeWidth = MARK_WIDTH_PX
    }
    private val speechPaint = Paint().apply {
        color = Color.rgb(3, 155, 229)
        strokeWidth = MARK_WIDTH_PX
    }
    private val thresholdPaint = Paint().apply {
        color = Color.rgb(46, 125, 50)
        strokeWidth = MARK_WIDTH_PX
    }

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

    private companion object {
        const val MARK_WIDTH_PX = 4f

        /** The level bar is drawn inside the zones so both stay readable. */
        const val BAR_INSET = 0.25f
    }
}
