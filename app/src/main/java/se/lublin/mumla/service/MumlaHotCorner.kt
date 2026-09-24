/*
 * Copyright (C) 2014 Andrew Comminos
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
package se.lublin.mumla.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import se.lublin.mumla.R

/** A push-to-talk area drawn over other apps in one corner of the screen. */
@SuppressLint("InflateParams") // An overlay window has no parent to inflate against.
class MumlaHotCorner(
    private val context: Context,
    gravity: Int,
    private val listener: MumlaHotCornerListener,
) : View.OnTouchListener {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view: View = LayoutInflater.from(context).inflate(R.layout.ptt_corner, null, false)
    private val highlightColour = ContextCompat.getColor(context, R.color.hot_corner_highlight)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).also { it.gravity = gravity }

    init {
        view.setOnTouchListener(this)
    }

    /**
     * Whether the corner is on screen. Showing it without the overlay permission opens the
     * permission's settings instead and leaves it hidden.
     */
    var isShown: Boolean = false
        set(shown) {
            if (shown == field) return
            if (shown) {
                if (!Settings.canDrawOverlays(context)) {
                    requestOverlayPermission()
                    return
                }
                windowManager.addView(view, params)
            } else {
                windowManager.removeView(view)
            }
            field = shown
        }

    /** The corner, as a `Gravity` value; applied at once if shown. */
    var gravity: Int
        get() = params.gravity
        set(gravity) {
            params.gravity = gravity
            if (isShown) windowManager.updateViewLayout(view, params)
        }

    @SuppressLint("ClickableViewAccessibility") // A press-and-hold area; there is no click.
    override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            view.setBackgroundColor(highlightColour)
            listener.onHotCornerDown()
            true
        }
        MotionEvent.ACTION_UP -> {
            view.setBackgroundColor(0)
            listener.onHotCornerUp()
            true
        }
        else -> false
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Toast.makeText(context, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show()
    }

    interface MumlaHotCornerListener {
        fun onHotCornerDown()
        fun onHotCornerUp()
    }
}
