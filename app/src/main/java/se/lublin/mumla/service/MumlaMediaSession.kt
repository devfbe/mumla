package se.lublin.mumla.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.MediaButtonAction
import se.lublin.mumla.Settings

/**
 * Owns a [MediaSessionCompat] that is active exactly while Mumla is connected and the headset
 * button action is not [MediaButtonAction.NONE], so headset and Bluetooth (AVRCP) media buttons
 * reach [MediaKeyHandler] even with the screen off. With NONE no session is held, because an
 * active PLAYING session takes play/pause away from every other app.
 *
 * Call [attach] in the service's onCreate and [detach] in onDestroy. Main thread only; the Humla
 * observer posts to the main looper if needed.
 */
class MumlaMediaSession @JvmOverloads constructor(
    private val context: Context,
    private val target: MediaKeyTarget,
    private val settings: Settings,
    /** Test seam: a dropped reference is indistinguishable from a released session otherwise. */
    private val sessionFactory: (Context, String) -> MediaSessionCompat = ::MediaSessionCompat,
) {
    private val handler = MediaKeyHandler(settings, target)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null
    private var connected = false

    /** Identifies this instance's posts on [mainHandler] so [detach] can drop just those. */
    private val observerPosts = Any()

    /** Public for tests; the framework calls it on [mainHandler]. */
    val callback: MediaSessionCompat.Callback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(
                mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java,
            ) ?: return false
            // No super fallback: the compat base returns false from SDK 27 on; on false androidx
            // runs the framework default itself.
            return handler.onKeyEvent(event)
        }
    }

    private val observer = object : HumlaObserver() {
        override fun onConnected() = onMain { activate() }

        override fun onDisconnected(e: HumlaException?) = onMain { deactivate() }
    }

    /** Strong reference: SharedPreferences keeps listeners weakly. Unfiltered; [applyState] is idempotent. */
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyState()
    }

    val isActive: Boolean
        get() = session?.isActive == true

    val sessionToken: MediaSessionCompat.Token?
        get() = session?.sessionToken

    /** The state the session advertises to the system (null while no session is held). */
    val playbackState: PlaybackStateCompat?
        get() = session?.controller?.playbackState

    fun attach(service: IHumlaService) {
        service.registerObserver(observer)
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun detach(service: IHumlaService) {
        service.unregisterObserver(observer)
        PreferenceManager.getDefaultSharedPreferences(context)
            .unregisterOnSharedPreferenceChangeListener(preferenceListener)
        // onDisconnected can arrive from the socket thread, so [onMain] may have queued a
        // callback; drop those by token (not everything: the framework dispatches media buttons on
        // [mainHandler] too).
        mainHandler.removeCallbacksAndMessages(observerPosts)
        deactivate()
    }

    /** We are connected: hold an active session unless the action is NONE. */
    fun activate() {
        connected = true
        applyState()
    }

    /** We are disconnected (or shutting down): release the session. */
    fun deactivate() {
        connected = false
        applyState()
    }

    private fun applyState() {
        val wanted = connected && settings.getMediaButtonAction() != MediaButtonAction.NONE
        if (wanted) ensureSession() else releaseSession()
    }

    private fun ensureSession() {
        if (session != null) return
        session = sessionFactory(context, TAG).apply {
            // Explicit handler: ensureSession runs on main today, but don't depend on it.
            setCallback(callback, mainHandler)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE,
                    )
                    .setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build(),
            )
            isActive = true
        }
    }

    /**
     * Releases the session and stops talking: the media key may have started transmission, and
     * nothing else would stop it. Only a session we actually held is unwound. [MediaKeyTarget.stopTalking]
     * is a no-op once disconnected, so this only matters when switching the action to NONE or on
     * onDestroy with a live connection.
     */
    private fun releaseSession() {
        val released = session ?: return
        // No `isActive = false` first: release() removes the record from the system's priority
        // stack anyway, and no active-change listener is registered.
        released.release()
        session = null
        target.stopTalking()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.postAtTime(block, observerPosts, SystemClock.uptimeMillis())
        }
    }

    private companion object {
        const val TAG = "MumlaMediaSession"
    }
}
