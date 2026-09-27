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
package se.lublin.mumla.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import se.lublin.humla.util.HumlaLog

/**
 * Android's battery optimisation, which lets some vendors stop the connection service in the
 * background, and the system screens that exempt Mumla from it.
 */
object BatteryOptimization {
    private const val TAG = "BatteryOptimization"

    /** Whether Mumla is exempt, i.e. "Unrestricted" in the system's battery settings. */
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** Asks the system to exempt Mumla; without that dialog, opens the list of all apps. */
    // A VoIP client that must keep its connection is an acceptable use under the Play policy.
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (!start(context, request)) openSettings(context)
    }

    /** Opens the system list where each app's battery optimisation is changed. */
    fun openSettings(context: Context) {
        start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        HumlaLog.w(TAG, "No activity for ${intent.action}", e)
        false
    }
}
