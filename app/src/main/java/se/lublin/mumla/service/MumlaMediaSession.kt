package se.lublin.mumla.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
class MumlaMediaSession(
    private val context: Context,
    private val target: MediaKeyTarget,
    private val settings: Settings,
) {
    private val handler = MediaKeyHandler(settings, target)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null
    private var connected = false

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
        session = MediaSessionCompat(context, TAG).apply {
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
     * This covers "the session went away" and "the user switched media keys off". It does *not*
     * cover the reconnect: [MediaKeyTarget.stopTalking] is a no-op once the connection state has
     * flipped, which it has before any observer runs. That hole is closed in HumlaService itself
     * (stream A, spec 4.1), not here.
     */
    private fun releaseSession() {
        val released = session ?: return
        released.isActive = false
        released.release()
        session = null
        target.stopTalking()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "MumlaMediaSession"
    }
}
