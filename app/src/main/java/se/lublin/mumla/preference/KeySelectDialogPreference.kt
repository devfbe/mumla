package se.lublin.mumla.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference
import se.lublin.mumla.R

/** A key code preference, picked by pressing the key; 0 is none. */
class KeySelectDialogPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {
    init {
        dialogLayoutResource = R.layout.dialog_keyselect_preference
    }
}
