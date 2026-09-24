package se.lublin.mumla.preference

import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import se.lublin.mumla.R
import se.lublin.mumla.databinding.DialogKeyselectPreferenceBinding

/** Waits for a key press and offers it as the value of a [KeySelectDialogPreference]. */
class KeySelectPreferenceDialogFragment : PreferenceValueDialogFragment<KeySelectDialogPreference>() {
    private var keyCode = 0
    private var binding: DialogKeyselectPreferenceBinding? = null

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        builder.setNeutralButton(R.string.reset_key) { _, _ ->
            keyCode = 0
            // Not the positive button, so stored here rather than on dismissal.
            persist(keyCode)
        }
    }

    override fun onBindDialogView(view: View) {
        binding = DialogKeyselectPreferenceBinding.bind(view)
        view.setOnKeyListener { _, code, event -> onKey(code, event) }
        view.isFocusableInTouchMode = true
        view.requestFocus()
        keyCode = storedValue(0)
        showKey()
    }

    private fun onKey(code: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            dismiss()
        } else {
            keyCode = code
            showKey()
        }
        return true
    }

    private fun showKey() {
        val view = binding?.keySelectValueView ?: return
        if (keyCode == 0) {
            view.setText(R.string.no_ptt_key)
        } else {
            view.text = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }

    override fun currentValue(): Int = keyCode
}
