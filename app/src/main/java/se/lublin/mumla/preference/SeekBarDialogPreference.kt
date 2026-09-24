package se.lublin.mumla.preference

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.withStyledAttributes
import androidx.preference.DialogPreference
import se.lublin.mumla.R

/**
 * An int preference picked on a slider from `min` to `max`. The stored value is the slider's
 * multiplied by `multiplier`; `android:text` is a suffix shown after it.
 */
class SeekBarDialogPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {
    var max = DEFAULT_MAX
        private set
    var min = 0
        private set
    var multiplier = 1
        private set
    var suffix: String? = null
        private set

    /** The XML default, not multiplied. */
    var defaultValue = 0
        private set

    init {
        context.withStyledAttributes(attrs, R.styleable.SeekBarDialogPreference) {
            max = getInt(R.styleable.SeekBarDialogPreference_max, DEFAULT_MAX)
            min = getInt(R.styleable.SeekBarDialogPreference_min, 0)
            multiplier = getInt(R.styleable.SeekBarDialogPreference_multiplier, 1)
            suffix = getString(R.styleable.SeekBarDialogPreference_android_text)
            defaultValue = getInt(R.styleable.SeekBarDialogPreference_android_defaultValue, 0)
        }
        dialogLayoutResource = R.layout.dialog_seekbar_preference
    }

    private companion object {
        const val DEFAULT_MAX = 100
    }
}
