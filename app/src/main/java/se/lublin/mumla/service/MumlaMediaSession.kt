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
 * button action is not [MediaButtonAction.NONE], so that headset and Bluetooth (AVRCP) media
 * buttons reach [MediaKeyHandler] even with the screen off (spec P1). With NONE no session is
 * held at all, because an active PLAYING session takes play/pause away from every other app.
 *
 * Create it in the service's onCreate and call [attach]; call [detach] in onDestroy.
 * All state is confined to the main thread: [attach], [detach], [activate], [deactivate] and the
 * properties must be called there; the Humla observer posts to the main looper if needed.
 */
class MumlaMediaSession @JvmOverloads constructor(
    private val context: Context,
    private val target: MediaKeyTarget,
    private val settings: Settings,
    /**
     * The seam that makes the held session observable. [MediaSessionCompat] is a handle on a
     * session registered with the system and [MediaSessionCompat.release] is the only thing that
     * gives it back, but nothing above this class can see whether that happened: [isActive] and
     * [sessionToken] both read off the reference, so dropping the reference looks exactly like
     * releasing it. A test that wants to assert the release has to be handed the session.
     */
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
            // Deliberately no `|| super.onMediaButtonEvent(...)`: the compat base implementation
            // returns false unconditionally from SDK 27 on, and this app's minSdk is 31, so the
            // call is dead code that only reads like a fallback. What does run when we return
            // false is the *framework* default, which androidx calls for us afterwards.
            return handler.onKeyEvent(event)
        }
    }

    private val observer = object : HumlaObserver() {
        override fun onConnected() = onMain { activate() }

        override fun onDisconnected(e: HumlaException?) = onMain { deactivate() }
    }

    /**
     * Strong reference: SharedPreferences keeps listeners weakly.
     *
     * Deliberately not filtered on [Settings.PREF_MEDIA_BUTTON_ACTION]. [applyState] is
     * idempotent -- it re-reads the setting and then does nothing unless the answer changed -- so
     * a key check here would be a second guard over an observable that the checks in
     * [ensureSession] and [releaseSession] already own, and nothing could tell it apart from its
     * absence. (It also swallows the null key that a `clear()` delivers, for no reason.)
     */
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
        // The main looper can be holding a callback of ours: onDisconnected arrives raw from the
        // socket thread -- HumlaConnection calls onConnectionDisconnected where it stands, in
        // handleFatalException, onTCPConnectionDisconnect and onTLSHandshakeFailed -- so [onMain]
        // posts it, and unregistering stops new ones but not queued ones. They are dropped here
        // by the token they were posted under. Scoped to that token on purpose: [mainHandler] is
        // also the handler the framework dispatches media buttons on, and those are not ours to
        // cancel.
        //
        // Measured, so nobody overrates this line: it survives mutation, and that is the map of a
        // hole rather than a loose end. What it drops is a `deactivate`, and the next line runs
        // one anyway, so it has no observable of its own today. The callback that would matter --
        // a queued onConnected building a session after the only code that could release it has
        // finished -- cannot be queued at all: HumlaService.onConnectionSynchronized is its only
        // caller and already runs inside a Runnable posted to the main looper by HumlaConnection,
        // HumlaCallbacks dispatches synchronously, and [detach] runs on main as well. That
        // serialization, not this sweep, is what guarantees the observer is unregistered before
        // any connect could be delivered. The sweep stays because it is what closes the hole the
        // moment either of those two facts moves.
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
            // Not mutation-tested, and it cannot be: dropping the handler makes
            // MediaSessionCompat build its own from the calling thread's looper, which under
            // Robolectric is the main looper -- a *different* Handler on the same Looper, and two
            // handlers on one looper are indistinguishable in delivery. Nor is the explicit
            // handler a thread necessity: [ensureSession] only ever runs on main, both through
            // [onMain] and from the preference listener, which SharedPreferences also delivers
            // there. It is belt and braces against that stopping being true, not a fix for a
            // thread this code is on.
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
     * Release the session, and take the talking state with it.
     *
     * The media key is the only way to switch transmission on without touching the screen, and
     * nothing else ever switches it off again: ToggleInputMode.mInputOn is created once in
     * HumlaService.onCreate and outlives every connection. So whenever this class stops being able
     * to turn transmission off -- the session is released, or the user sets the action to NONE --
     * it turns it off now. Only a session we were actually holding is unwound; a deactivate that
     * releases nothing must not reach into a talking state this class never set, and the pair
     * onDisconnected/onDestroy makes that the common case rather than a corner one.
     *
     * Scope, because the bare sentence would promise more than it delivers:
     * [MediaKeyTarget.stopTalking] is a no-op once the connection state has flipped, and
     * `mConnectionState` is DISCONNECTED before any observer runs (spec 4.1). So this reaches the
     * talking state in exactly the two cases where we are still connected while giving the session
     * up -- the user switching the action to NONE, and onDestroy on a live connection. On the
     * disconnect itself, and on the reconnect after it, it reaches nothing; that hole is closed in
     * HumlaService (stream A, spec 4.1), not here.
     */
    private fun releaseSession() {
        val released = session ?: return
        // No `isActive = false` before this. Measured against androidx.media 1.8.0:
        // MediaSessionCompat.setActive calls MediaSession.setActive, which moves the record inside
        // the system's priority stack, while release() takes the record out of that stack
        // altogether -- and release() does not call setActive itself. The public setActive does
        // one thing more, and it is the one that could have made this observable: afterwards it
        // walks mActiveListeners and calls onActiveChanged(), which release() does not do. That
        // list is empty here -- addOnActiveChangeListener is @RestrictTo and only
        // MediaBrowserServiceCompat registers one, and this tree has no MediaBrowserService, no
        // addOnActiveChangeListener and no MediaButtonReceiver. In-process there is no reader
        // either, since the reference is dropped on the next line. So the call named no observable
        // that release() does not already cover (spec 4.04), and it is gone rather than pinned;
        // adding a session listener would put it back on the table.
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
