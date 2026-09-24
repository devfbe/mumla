package se.lublin.mumla.preference

import android.view.View
import android.widget.SeekBar
import se.lublin.mumla.databinding.DialogSeekbarPreferenceBinding

/** The slider of a [SeekBarDialogPreference]. */
class SeekBarPreferenceDialogFragment : PreferenceValueDialogFragment<SeekBarDialogPreference>() {
    private var value = 0

    override fun onBindDialogView(view: View) {
        val binding = DialogSeekbarPreferenceBinding.bind(view)
        val preference = preference
        val multiplier = preference.multiplier
        val min = preference.min
        // The stored value is multiplied, but the XML default, min and max are not.
        value = storedValue(preference.defaultValue * multiplier)
        fun showValue() {
            binding.seekBarValueView.text = "$value${preference.suffix.orEmpty()}"
        }
        showValue()
        binding.seekBar.max = preference.max - min
        binding.seekBar.progress = value / multiplier - min
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value = (min + progress) * multiplier
                showValue()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    override fun currentValue(): Int = value
}
