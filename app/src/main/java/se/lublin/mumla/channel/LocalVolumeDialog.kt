/*
 * Copyright (C) 2026 The Mumla authors
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
package se.lublin.mumla.channel

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.R
import se.lublin.mumla.databinding.DialogLocalVolumeBinding
import kotlin.math.roundToInt

/** The slider's range in percent; 100 is unchanged. */
internal const val LOCAL_VOLUME_MAX_PERCENT = 200
private const val PERCENT = 100f
private const val STEP_PERCENT = 5

/**
 * Asks for the playback volume on this device of the user called [name], which is [before] now.
 * Every move of the slider goes to [preview]; OK and Reset go to [keep], Cancel restores [before].
 */
fun showLocalVolumeDialog(
    context: Context,
    name: String?,
    before: Float,
    preview: (Float) -> Unit,
    keep: (Float) -> Unit,
): AlertDialog {
    val binding = DialogLocalVolumeBinding.inflate(LayoutInflater.from(context))
    fun show(percent: Int) {
        binding.localVolumeValue.text = context.getString(R.string.local_volume_percent, percent)
    }
    val start = (before * PERCENT / STEP_PERCENT).roundToInt() * STEP_PERCENT
    binding.localVolumeSlider.value = start.coerceIn(0, LOCAL_VOLUME_MAX_PERCENT).toFloat()
    show(binding.localVolumeSlider.value.roundToInt())
    binding.localVolumeSlider.addOnChangeListener { _, value, _ ->
        show(value.roundToInt())
        preview(value / PERCENT)
    }
    return MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.local_volume_title, name))
        .setView(binding.root)
        .setPositiveButton(android.R.string.ok) { _, _ -> keep(binding.localVolumeSlider.value / PERCENT) }
        .setNeutralButton(R.string.local_volume_reset) { _, _ -> keep(1f) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> preview(before) }
        .setOnCancelListener { preview(before) }
        .show()
}
