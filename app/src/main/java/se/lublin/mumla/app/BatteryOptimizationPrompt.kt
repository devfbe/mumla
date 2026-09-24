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
package se.lublin.mumla.app

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.util.BatteryOptimization

/**
 * Offers once to exempt Mumla from battery optimisation, so the connection survives in the
 * background. Only an answer counts as asked: a prompt dismissed with the activity comes back.
 * Main thread only.
 */
class BatteryOptimizationPrompt(
    private val activity: AppCompatActivity,
    private val settings: Settings,
) {
    private var dialog: AlertDialog? = null

    /** Shows the offer unless it was answered before, is showing, or Mumla is exempt already. */
    fun offerIfNeeded() {
        if (settings.isBatteryOptimizationAsked || dialog?.isShowing == true) return
        if (BatteryOptimization.isExempt(activity)) return
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.battery_optimization_prompt_title)
            .setMessage(R.string.battery_optimization_prompt_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                settings.isBatteryOptimizationAsked = true
                BatteryOptimization.requestExemption(activity)
            }
            .setNegativeButton(R.string.battery_optimization_prompt_decline) { _, _ ->
                settings.isBatteryOptimizationAsked = true
            }
            .setOnCancelListener { settings.isBatteryOptimizationAsked = true }
            .show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
