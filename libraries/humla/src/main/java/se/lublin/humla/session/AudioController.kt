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

package se.lublin.humla.session

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.net.MessageHandlerRegistry
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.util.HumlaLogger

/**
 * Owns the audio pipeline's lifecycle on the [THREAD_NAME] HandlerThread (spec A2).
 *
 * Every public method posts and returns; creation and teardown - the latter joins the capture and
 * playback threads - run on the control thread, never on the caller's. [Listener] callbacks are
 * posted to [mainHandler], which is the main looper's unless a caller says otherwise.
 *
 * Confinement: [session] is touched only on the control thread. [running] is written there too and
 * is volatile because [isRunning] and [currentBandwidth] are read from whatever thread asks.
 */
class AudioController(
    private val context: Context,
    private val logger: HumlaLogger,
    private val factory: () -> AudioHandlerFactory,
    private val encodeListener: AudioHandler.AudioEncodeListener,
    private val outputListener: AudioOutput.AudioOutputListener,
    private val listener: Listener,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    interface Listener {
        fun onAudioStarted()
        fun onAudioFailed(message: String)
        fun onAudioWarning(message: String)
    }

    /** What a pipeline would be built from right now. */
    private class Session(
        val config: AudioConfig,
        val params: AudioSessionParams,
        val registry: MessageHandlerRegistry,
    )

    /**
     * A pipeline that is up, with the registry its handlers were added to. Carrying the registry
     * here rather than reading it back out of [session] is what makes teardown unregister from the
     * registry that holds the handlers, whatever [session] has become in between.
     */
    private class Running(val audio: ManagedAudio, val registry: MessageHandlerRegistry)

    /**
     * Not private, so a test can assert on the thread itself. Started eagerly: the service builds
     * one controller in onCreate and quits it in onDestroy, so there is no window in which the
     * thread would be paid for and not wanted.
     */
    internal val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)

    /** Read from the handler, not the thread: [HandlerThread.getLooper] is null once it has quit. */
    val looper: Looper get() = handler.looper

    private var session: Session? = null
    @Volatile private var running: Running? = null

    val isRunning: Boolean get() = running != null

    /** Bandwidth of the running pipeline in bps, or -1 while none runs. */
    val currentBandwidth: Int get() = running?.audio?.currentBandwidth ?: -1

    /** Builds the pipeline for a synchronized session, replacing any that is up. */
    fun start(config: AudioConfig, params: AudioSessionParams, registry: MessageHandlerRegistry) {
        handler.post {
            stopRunning()
            val next = Session(config, params, registry)
            session = next
            create(next)
        }
    }

    /**
     * Applies new settings, and rebuilds the pipeline if one is up and the settings really differ.
     *
     * Two decisions, both here rather than at the call sites, because a rebuild is an audible gap
     * and this is the one place every caller passes through (spec 4.04: one bottleneck instead of
     * N entry guards).
     *
     * - Nothing differs, nothing is rebuilt. The config is compared by value; the input mode by
     *   identity, because IInputMode carries per-connection state and has no equality of its own.
     *   Without this, a Bluetooth route that reports the state it already had - the SCO listener
     *   passes the config on unconditionally - tears the pipeline down and builds it again.
     * - A pipeline that never came up is not rebuilt, which is the pre-existing rule:
     *   `HumlaService.configureExtras` reloaded audio only `if (mAudioHandler != null &&
     *   mAudioHandler.isInitialized())`, and a failed initialize left that field null.
     */
    fun reconfigure(config: AudioConfig, inputMode: IInputMode) {
        handler.post {
            val current = session ?: return@post
            if (config == current.config && inputMode === current.params.inputMode) return@post
            val next = Session(config, current.params.copy(inputMode = inputMode), current.registry)
            session = next
            if (running != null) {
                stopRunning()
                create(next)
            }
        }
    }

    /**
     * Retargets voice. The id goes into [session] as well as into the running pipeline, so the next
     * rebuild starts out targeting it rather than transmitting to the channel until something
     * repeats the call.
     */
    fun setVoiceTargetId(id: Byte) {
        handler.post {
            session?.let { session = Session(it.config, it.params.copy(targetId = id), it.registry) }
            running?.audio?.setVoiceTargetId(id)
        }
    }

    /** Tears the pipeline down. Safe from the main thread; it does not wait for the teardown. */
    fun shutdown() {
        handler.post {
            stopRunning()
            // Measured, not argued: deleting this line alone leaves all 271 tests green, because
            // start() overwrites the session and reconfigure() needs a running pipeline. It stays
            // as a retention measure - the session holds the connection that acts as the registry,
            // the session user and the input mode - and it is the one line in this class with no
            // behavioural observable. Do not read it as protected.
            session = null
        }
    }

    /** Tears the pipeline down and stops the control thread (service teardown). */
    fun quit() {
        shutdown()
        // quitSafely, and after the post: a quit that ran first would refuse that message and leave
        // the capture and playback threads running for the life of the process.
        thread.quitSafely()
    }

    private fun create(s: Session) {
        try {
            val audio = factory().create(context, logger, s.config, s.params, encodeListener, outputListener)
            audio.setWarningListener { message -> mainHandler.post { listener.onAudioWarning(message) } }
            s.registry.addTCPMessageHandlers(audio.tcpListener)
            s.registry.addUDPMessageHandlers(audio.udpListener)
            running = Running(audio, s.registry)
            mainHandler.post { listener.onAudioStarted() }
        } catch (e: Exception) {
            // Exception, not AudioException: AudioTrack and AudioRecord construction throw
            // unchecked on a device, and an unchecked throw here would kill the control thread -
            // after which every start, reconfigure and shutdown is dropped in silence, which is
            // the failure spec A8 exists to prevent.
            Log.e(TAG, "Audio initialization failed", e)
            mainHandler.post { listener.onAudioFailed(e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun stopRunning() {
        val r = running ?: return
        running = null
        r.registry.removeTCPMessageHandler(r.audio.tcpListener)
        r.registry.removeUDPMessageHandler(r.audio.udpListener)
        // Before shutdown(), not after: a pipeline that reports a warning on its way down would
        // otherwise post a message about a session the user has already left.
        r.audio.setWarningListener(null)
        r.audio.shutdown()
    }

    companion object {
        internal val TAG: String = AudioController::class.java.name
        const val THREAD_NAME = "humla-audio-control"
    }
}
