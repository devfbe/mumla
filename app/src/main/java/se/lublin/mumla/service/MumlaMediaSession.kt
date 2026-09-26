package se.lublin.mumla.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaService
import se.lublin.humla.session.SessionState
import se.lublin.mumla.MediaButtonAction
import se.lublin.mumla.Settings
import se.lublin.mumla.util.changes

private const val TAG = "MumlaMediaSession"

/**
 * Owns a [MediaSessionCompat] that is active exactly while Mumla is connected and the headset
 * button action is not [MediaButtonAction.NONE], so headset and Bluetooth (AVRCP) media buttons
 * reach [MediaKeyHandler] even with the screen off. With NONE no session is held, because an
 * active PLAYING session takes play/pause away from every other app.
 *
 * Call [attach] in the service's onCreate and [detach] in onDestroy. Main thread only.
 */
class MumlaMediaSession(
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

    /** Follows the service's session state between [attach] and [detach]. */
    private var stateUpdates: Job? = null

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

    val isActive: Boolean
        get() = session?.isActive == true

    val sessionToken: MediaSessionCompat.Token?
        get() = session?.sessionToken

    /** The state the session advertises to the system (null while no session is held). */
    val playbackState: PlaybackStateCompat?
        get() = session?.controller?.playbackState

    fun attach(service: IHumlaService) {
        stateUpdates?.cancel()
        stateUpdates = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                PreferenceManager.getDefaultSharedPreferences(context).changes(Settings.PREF_MEDIA_BUTTON_ACTION)
                    .collect { applyState() }
            }
            service.sessionState
                .map { it == SessionState.Connected }
                .distinctUntilChanged()
                .collect { connected -> if (connected) activate() else deactivate() }
        }
    }

    fun detach() {
        stateUpdates?.cancel()
        stateUpdates = null
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
        val wanted = connected && settings.mediaButtonAction != MediaButtonAction.NONE
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
}
