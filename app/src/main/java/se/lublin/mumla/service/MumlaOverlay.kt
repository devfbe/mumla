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
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ListView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.OverlayBinding
import se.lublin.mumla.util.collectEvents
import se.lublin.mumla.util.dp

/** An onscreen interactive overlay displaying the users in the current channel. */
class MumlaOverlay(private val service: MumlaService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val binding = OverlayBinding.inflate(LayoutInflater.from(service))
    private val overlayView: View = binding.root
    private val overlayList: ListView = binding.overlayList
    private val talkButton: ImageView = binding.overlayTalk
    private val overlayParams: WindowManager.LayoutParams
    private var userAdapter: OverlayUserAdapter? = null

    /** Collects the service's events while the overlay is shown. */
    private var events: Job? = null

    var isShown = false
        private set

    init {
        setUpGestures()
        binding.overlayClose.setOnClickListener { hide() }
        setPushToTalkShown(Settings.getInstance(service).inputMethod == Settings.ARRAY_INPUT_METHOD_PTT)

        overlayParams = WindowManager.LayoutParams(
            service.resources.dp(DEFAULT_WIDTH_DP).toInt(),
            service.resources.dp(DEFAULT_HEIGHT_DP).toInt(),
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

    private fun onServiceEvent(event: HumlaEvent) {
        val adapter = userAdapter ?: return
        when (event) {
            is HumlaEvent.UserTalkStateUpdated -> adapter.notifyDataSetChanged()
            is HumlaEvent.UserStateUpdated -> {
                val channel = event.user.channel
                if (channel != null && channel == service.sessionChannel) adapter.notifyDataSetChanged()
            }
            // Unconditional: the model may no longer know which channel a removed user was in.
            is HumlaEvent.UserRemoved -> adapter.notifyDataSetChanged()
            is HumlaEvent.UserJoinedChannel -> onUserJoinedChannel(adapter, event)
            else -> Unit
        }
    }

    private fun onUserJoinedChannel(adapter: OverlayUserAdapter, event: HumlaEvent.UserJoinedChannel) {
        val selfSession = try {
            service.sessionId
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in onUserJoinedChannel: $e")
            return
        }
        val sessionChannel = service.sessionChannel ?: return
        if (event.user.session == selfSession) {
            // Session user has changed channels
            adapter.setChannel(sessionChannel)
        } else if (event.newChannel.id == sessionChannel.id || event.oldChannel?.id == sessionChannel.id) {
            adapter.notifyDataSetChanged()
        }
    }

    /** Dragging has no click equivalent; the talk button's accessibility click toggles talking. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpGestures() {
        binding.overlayTitle.setOnTouchListener(MoveListener())
        binding.overlayDrag.setOnTouchListener(ResizeListener())
        talkButton.setOnTouchListener(TalkListener())
        ViewCompat.replaceAccessibilityAction(talkButton, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            service.setTalkingState(!service.isTalking)
            true
        }
    }

    fun show() {
        if (isShown) return
        val channel = service.sessionChannel ?: return
        isShown = true
        userAdapter = OverlayUserAdapter(service, channel).also { overlayList.adapter = it }
        events = collectEvents(MainScope(), service, ::onServiceEvent)
        windowManager.addView(overlayView, overlayParams)
    }

    fun hide() {
        if (!isShown) return
        isShown = false
        events?.cancel()
        events = null
        overlayList.adapter = null
        userAdapter = null
        try {
            windowManager.removeView(overlayView)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "The overlay was not attached", e)
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
                service.setTalkingState(true)
                true
            }
            MotionEvent.ACTION_UP -> {
                service.setTalkingState(false)
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
