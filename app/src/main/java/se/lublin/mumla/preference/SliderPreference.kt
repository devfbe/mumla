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
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.withStyledAttributes
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.slider.Slider
import se.lublin.mumla.R
import kotlin.math.roundToInt

private const val DEFAULT_MAX = 100

/**
 * An int preference set on an inline slider from `min` to `max` in steps of `valueStep`, stored as
 * is. The value beside the slider follows the thumb; it is stored when the thumb is let go.
 *
 * Unlike androidx's `SeekBarPreference`, it snaps to steps and shows its value with a unit from
 * `valueFormat`, a string resource that gets the stored value divided by `valueDivisor`.
 * `maxValueLabel` names the maximum instead of a number, for a slider whose end means "no limit".
 */
class SliderPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    var min = 0
        private set
    var max = DEFAULT_MAX
        private set
    var step = 1
        private set

    @StringRes
    private var format = 0

    @StringRes
    private var maxLabel = 0
    private var divisor = 1
    private var tracking = false

    /** The stored value, or the default while nothing is stored. */
    var value = 0
        private set

    init {
        context.withStyledAttributes(attrs, R.styleable.SliderPreference) {
            min = getInt(R.styleable.SliderPreference_min, 0)
            max = getInt(R.styleable.SliderPreference_max, DEFAULT_MAX)
            step = getInt(R.styleable.SliderPreference_valueStep, 1)
            format = getResourceId(R.styleable.SliderPreference_valueFormat, 0)
            maxLabel = getResourceId(R.styleable.SliderPreference_maxValueLabel, 0)
            divisor = getInt(R.styleable.SliderPreference_valueDivisor, 1)
        }
        layoutResource = R.layout.preference_slider
        isSelectable = false
    }

    /** [value] with its unit, as shown beside the slider. */
    fun formatted(value: Int): String = when {
        maxLabel != 0 && value >= max -> context.getString(maxLabel)
        format == 0 -> (value / divisor).toString()
        else -> context.getString(format, value / divisor)
    }

    /**
     * Re-reads the stored value, after something other than this row wrote it (a reset button, or
     * another screen), and redraws the row.
     */
    fun reloadValue(default: Int) {
        val stored = getPersistedInt(default)
        if (stored == value) return
        value = stored
        notifyChanged()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getInt(index, min)

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedInt(defaultValue as? Int ?: min)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val label = holder.findViewById(R.id.slider_value) as TextView
        val slider = holder.findViewById(R.id.slider) as Slider
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.stepSize = step.toFloat()
        // What older versions stored may lie off the steps or outside the range; the slider would
        // throw on it, so it shows the nearest position and the stored value stays until moved.
        slider.value = onStep(value).toFloat()
        slider.contentDescription = title
        slider.setLabelFormatter { formatted(it.roundToInt()) }
        label.text = formatted(value)
        slider.addOnChangeListener { _, position, fromUser ->
            label.text = formatted(position.roundToInt())
            // Keys and accessibility actions change the value without a touch to end.
            if (fromUser && !tracking) commit(position.roundToInt())
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                tracking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                tracking = false
                commit(slider.value.roundToInt())
            }
        })
    }

    private fun onStep(value: Int): Int {
        val clamped = value.coerceIn(min, max)
        return (min + ((clamped - min).toFloat() / step).roundToInt() * step).coerceAtMost(max)
    }

    private fun commit(newValue: Int) {
        if (newValue == value) return
        if (callChangeListener(newValue)) {
            value = newValue
            persistInt(newValue)
        } else {
            notifyChanged()
        }
    }
}
