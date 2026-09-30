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

import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.audio.AudioConfig
import se.lublin.humla.audio.AudioController
import se.lublin.humla.audio.AudioHandlerFactory
import se.lublin.humla.audio.AudioHost
import se.lublin.humla.audio.AudioSessionParams
import se.lublin.humla.audio.AudioSettings
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.audio.routing.AudioRouter
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.audio.routing.CommunicationDevices
import se.lublin.humla.net.MessageHandlerRegistry

/**
 * The audio half of a session: the input modes, the router and the pipeline controller, and the
 * [AudioConfig] they resolve from [AudioSettings] and the current route. Main thread; the pipeline
 * itself is built and torn down on the controller's thread.
 */
internal class AudioSession(
    host: AudioHost,
    factory: AudioHandlerFactory,
    devices: CommunicationDevices,
    mainHandler: Handler,
    settings: AudioSettings,
    private val listener: Listener,
) {
    interface Listener {
        /** The platform refused a route the router asked for. */
        fun onRouteRefused()

        /** A pipeline could not start; [message] says why. */
        fun onAudioFailed(message: String)
    }

    var settings: AudioSettings = settings
        private set

    private val mutableRoute = MutableStateFlow<Int?>(null)

    /** The `AudioDeviceInfo` type voice is routed to; null while the platform decides. */
    val route: StateFlow<Int?> = mutableRoute.asStateFlow()

    val config: AudioConfig get() = settings.toAudioConfig(mutableRoute.value)

    private val toggleInputMode = ToggleInputMode()
    val activityInputMode = ActivityInputMode(VoiceActivityDetector(settings.vad))
    private val continuousInputMode = ContinuousInputMode()

    /** Held by identity: the audio thread and [isTalking] must see the same toggle object. */
    var inputMode: IInputMode = inputModeFor(settings.transmitMode)
        private set

    /** Engaged only while a session is synchronized. */
    val router = AudioRouter(
        devices,
        object : AudioRouter.Listener {
            override fun onRouteChanged(type: Int?) = setRoute(type)

            override fun onRouteRefused() = listener.onRouteRefused()
        },
    ).apply {
        preferred = settings.preferredDevice
        bluetoothAutomatic = settings.bluetoothAutomatic
    }

    /** One controller and one thread for the life of this object; [release] quits it. */
    val controller = AudioController(host, factory, listener::onAudioFailed, mainHandler)

    val isTalking: Boolean get() = toggleInputMode.isTalkingOn

    val isEchoCancellationEnabled: Boolean get() = config.echoCancellation != EchoCancellationMode.NONE

    /** The running pipeline's bandwidth in bps, or -1 while none runs. */
    val currentBandwidth: Int get() = controller.currentBandwidth

    val devices: List<CommunicationDevice> get() = router.availableDevices()

    val activeDevice: CommunicationDevice? get() = router.activeDevice()

    fun setTalking(talking: Boolean) = toggleInputMode.setTalkingOn(talking)

    /**
     * Applies [next] live: input mode, voice activity and routing at once, the pipeline through a
     * rebuild that [AudioController.reconfigure] skips when nothing it is built from changed.
     */
    fun apply(next: AudioSettings) {
        val previous = settings
        settings = next
        inputMode = inputModeFor(next.transmitMode)
        activityInputMode.setVadConfig(next.vad)
        val routing = next.preferredDevice to next.bluetoothAutomatic
        if (routing != previous.preferredDevice to previous.bluetoothAutomatic) {
            // A user's explicit choice is left standing; one the old preference seeded is not.
            router.preferred = next.preferredDevice
            router.bluetoothAutomatic = next.bluetoothAutomatic
            router.apply()
        }
        controller.reconfigure(config, inputMode)
    }

    /** The session synchronized: restore the route and build the pipeline for [params]. */
    fun start(params: AudioSessionParams, registry: MessageHandlerRegistry) {
        router.engage()
        controller.start(config, params.copy(inputMode = inputMode), registry)
    }

    /** Restores the route of a session that has no pipeline to build. */
    fun engageRoute() = router.engage()

    /**
     * A connection ended. Push-to-talk is cleared because the toggle outlives the connection, and an
     * automatic reconnect would otherwise transmit without a key press. The route is a connection
     * resource and the wish is not, so it is given back on every disconnect.
     */
    fun stop() {
        toggleInputMode.setTalkingOn(false)
        router.disengage()
        controller.shutdown()
    }

    /**
     * The session ended for good. The chooser's pick belongs to it, as a pick in the phone app
     * belongs to one call; a dropped connection keeps it, the end of the session does not.
     */
    fun endSession() = router.forgetChoice()

    fun setVoiceTargetId(id: Byte) = controller.setVoiceTargetId(id)

    /** Gives the route back and quits the controller thread, without waiting for the teardown. */
    fun release() {
        router.disengage()
        router.release()
        controller.quit()
    }

    /** A new route rebuilds the pipeline for its stream (and, for SCO, its sample rate). */
    private fun setRoute(type: Int?) {
        mutableRoute.value = type
        controller.reconfigure(config, inputMode)
    }

    private fun inputModeFor(mode: TransmitMode): IInputMode = when (mode) {
        TransmitMode.PUSH_TO_TALK -> toggleInputMode
        TransmitMode.CONTINUOUS -> continuousInputMode
        TransmitMode.VOICE_ACTIVITY -> activityInputMode
    }
}
