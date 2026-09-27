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

package se.lublin.humla.audio

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.net.MessageHandlerRegistry

/**
 * Owns the audio pipeline's lifecycle on the [THREAD_NAME] HandlerThread.
 *
 * Every public method posts and returns; creation and teardown (which joins the capture and
 * playback threads) run on the control thread. [onFailed] is posted to [mainHandler], with the
 * message of a pipeline that could not be built.
 * [session] is confined to the control thread; [running] is volatile because [isRunning] and
 * [currentBandwidth] may be read from any thread.
 */
class AudioController(
    private val host: AudioHost,
    private val factory: AudioHandlerFactory,
    private val onFailed: (String) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    /** What a pipeline would be built from right now. */
    private class Session(
        val config: AudioConfig,
        val params: AudioSessionParams,
        val registry: MessageHandlerRegistry,
    )

    /**
     * A pipeline that is up, with the registry its handlers were added to, so teardown unregisters
     * from that registry whatever [session] has become since.
     */
    private class Running(val audio: ManagedAudio, val registry: MessageHandlerRegistry)

    /** Not private so tests can assert on the thread. Started eagerly; lives until [quit]. */
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
     * Applies new settings, and rebuilds the pipeline if one is up and the settings really differ
     * (a rebuild is an audible gap). The config is compared by value, the input mode by identity
     * (it carries per-connection state). A pipeline that never came up is not rebuilt.
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
     * Retargets voice. The id is stored in [session] too, so the next rebuild keeps targeting it.
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
            // Drop the reference so the session (connection, user, input mode) can be collected.
            session = null
        }
    }

    /** Tears the pipeline down and stops the control thread (session close). */
    fun quit() {
        shutdown()
        // quitSafely, after the post: quitting first would drop that message and leave the capture
        // and playback threads running.
        thread.quitSafely()
    }

    private fun create(s: Session) {
        try {
            val audio = factory.create(host, s.config, s.params)
            s.registry.addTcpHandler(audio.tcpHandler)
            s.registry.addVoiceHandler(audio.voiceHandler)
            running = Running(audio, s.registry)
        } catch (e: Exception) {
            // Exception, not AudioException: AudioTrack/AudioRecord construction can throw
            // unchecked, which would kill the control thread and silently drop all later messages.
            Log.e(TAG, "Audio initialization failed", e)
            mainHandler.post { onFailed(e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun stopRunning() {
        val r = running ?: return
        running = null
        r.registry.removeTcpHandler(r.audio.tcpHandler)
        r.registry.removeVoiceHandler(r.audio.voiceHandler)
        r.audio.shutdown()
    }

    companion object {
        internal val TAG: String = AudioController::class.java.name
        const val THREAD_NAME = "humla-audio-control"
    }
}
