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

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.content.edit
import androidx.preference.DialogPreference
import androidx.preference.PreferenceFragmentCompat

/**
 * The dialog of a [DialogPreference] with an int value, shown by its [PreferenceFragmentCompat]
 * as a child fragment, which is where [preference] is looked up by the [ARG_KEY] argument.
 */
abstract class PreferenceValueDialogFragment<P : DialogPreference> : AppCompatDialogFragment() {

    private var positiveClicked = false

    @Suppress("UNCHECKED_CAST")
    protected val preference: P
        get() = requireNotNull(
            (requireParentFragment() as PreferenceFragmentCompat)
                .findPreference<DialogPreference>(requireArguments().getString(ARG_KEY).orEmpty()),
        ) as P

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val preference = preference
        val view = layoutInflater.inflate(preference.dialogLayoutResource, null)
        onBindDialogView(view)
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(preference.dialogTitle)
            .setIcon(preference.dialogIcon)
            .setView(view)
            .setPositiveButton(preference.positiveButtonText) { _, _ -> positiveClicked = true }
            .setNegativeButton(preference.negativeButtonText, null)
        onPrepareDialogBuilder(builder)
        return builder.create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (positiveClicked) persist(currentValue())
    }

    /** Stores [value] if the preference's change listener accepts it. */
    protected fun persist(value: Int) {
        val preference = preference
        if (preference.callChangeListener(value)) {
            requireNotNull(preference.sharedPreferences).edit { putInt(preference.key, value) }
        }
    }

    /** The stored value, or [default]. */
    protected fun storedValue(default: Int): Int =
        requireNotNull(preference.sharedPreferences).getInt(preference.key, default)

    protected abstract fun onBindDialogView(view: View)

    /** The value the dialog shows, stored when it is closed with the positive button. */
    protected abstract fun currentValue(): Int

    protected open fun onPrepareDialogBuilder(builder: AlertDialog.Builder) = Unit

    companion object {
        const val ARG_KEY = "key"
    }
}
