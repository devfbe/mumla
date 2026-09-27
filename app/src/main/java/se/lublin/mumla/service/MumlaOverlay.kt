/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ListView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.OverlayBinding
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.util.dp

/** An onscreen interactive overlay displaying the users in the current channel. */
class MumlaOverlay(private val context: Context, private val sessions: SessionManager) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val binding = OverlayBinding.inflate(LayoutInflater.from(context))
    private val overlayView: View = binding.root
    private val overlayList: ListView = binding.overlayList
    private val talkButton: ImageView = binding.overlayTalk
    private val overlayParams: WindowManager.LayoutParams

    /** The session the overlay shows, while it is shown. */
    private var session: IHumlaSession? = null

    /** Follows the users of our channel while the overlay is shown. */
    private var updates: Job? = null

    var isShown = false
        private set

    init {
        setUpGestures()
        binding.overlayClose.setOnClickListener { hide() }
        setPushToTalkShown(Settings.getInstance(context).inputMethod == Settings.ARRAY_INPUT_METHOD_PTT)

        overlayParams = WindowManager.LayoutParams(
            context.resources.dp(DEFAULT_WIDTH_DP).toInt(),
            context.resources.dp(DEFAULT_HEIGHT_DP).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            windowAnimations = android.R.style.Animation_Dialog
        }
    }

    /** Dragging has no click equivalent; the talk button's accessibility click toggles talking. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpGestures() {
        binding.overlayTitle.setOnTouchListener(MoveListener())
        binding.overlayDrag.setOnTouchListener(ResizeListener())
        talkButton.setOnTouchListener(TalkListener())
        ViewCompat.replaceAccessibilityAction(talkButton, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            session?.audio?.let { it.setTalking(!it.isTalking) }
            true
        }
    }

    /** Shows the users of our channel in the connected session; nothing without one. */
    fun show() {
        val session = sessions.connected
        if (isShown || session?.model?.value?.selfChannel == null) return
        isShown = true
        this.session = session
        val adapter = OverlayUserAdapter(context).also { overlayList.adapter = it }
        updates = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            .launch(start = CoroutineStart.UNDISPATCHED) {
                combine(session.model, session.talkStates) { model, talkStates ->
                    model?.selfChannel?.let { model.usersIn(it.id) }.orEmpty() to talkStates
                }.collect { (users, talkStates) -> adapter.submit(users, talkStates) }
            }
        windowManager.addView(overlayView, overlayParams)
    }

    fun hide() {
        if (!isShown) return
        isShown = false
        updates?.cancel()
        updates = null
        session = null
        overlayList.adapter = null
        try {
            windowManager.removeView(overlayView)
        } catch (e: IllegalArgumentException) {
            HumlaLog.w(TAG, "The overlay was not attached", e)
        }
    }

    fun setPushToTalkShown(showPtt: Boolean) {
        talkButton.visibility = if (showPtt) View.VISIBLE else View.GONE
    }

    /** Drags the overlay by its title. */
    private inner class MoveListener : View.OnTouchListener {
        private var initialX = 0f
        private var initialY = 0f

        @SuppressLint("ClickableViewAccessibility") // Dragging has no click equivalent.
        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.rawX - overlayParams.x
                initialY = event.rawY - overlayParams.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                overlayParams.x = (event.rawX - initialX).toInt()
                overlayParams.y = (event.rawY - initialY).toInt()
                windowManager.updateViewLayout(overlayView, overlayParams)
                true
            }
            else -> false
        }
    }

    /** Resizes the overlay by its corner handle. */
    private inner class ResizeListener : View.OnTouchListener {
        private var initialX = 0f
        private var initialY = 0f
        private var initialWidth = 0f
        private var initialHeight = 0f

        @SuppressLint("ClickableViewAccessibility") // Resizing has no click equivalent.
        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.rawX
                initialY = event.rawY
                initialWidth = overlayView.width.toFloat()
                initialHeight = overlayView.height.toFloat()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                overlayParams.width = (initialWidth + (event.rawX - initialX)).toInt()
                overlayParams.height = (initialHeight + (event.rawY - initialY)).toInt()
                windowManager.updateViewLayout(overlayView, overlayParams)
                true
            }
            else -> false
        }
    }

    /** Transmits while the talk button is held. */
    private inner class TalkListener : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility") // Push-to-talk is a hold, not a click.
        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                session?.audio?.setTalking(true)
                true
            }
            MotionEvent.ACTION_UP -> {
                session?.audio?.setTalking(false)
                true
            }
            else -> false
        }
    }

    companion object {
        private val TAG: String = MumlaOverlay::class.java.name
        private const val DEFAULT_WIDTH_DP = 200f
        private const val DEFAULT_HEIGHT_DP = 240f
    }
}
