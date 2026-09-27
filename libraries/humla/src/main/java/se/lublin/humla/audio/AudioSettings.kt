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

import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.PreferredAudioDevice

/** When the microphone is sent. */
public enum class TransmitMode {
    VOICE_ACTIVITY,
    PUSH_TO_TALK,
    CONTINUOUS,
}

/**
 * The user's audio settings, all applied live. A pipeline is built from [pipeline] as it is; the
 * other fields reach live objects (input mode, router) or are resolved in [toAudioConfig], so a
 * change to one of them rebuilds the pipeline only when the resolved value changes.
 */
public data class AudioSettings(
    val transmitMode: TransmitMode = TransmitMode.VOICE_ACTIVITY,
    val vad: VadConfig = VadConfig.DEFAULT,
    /** Only honoured in push-to-talk. */
    val halfDuplex: Boolean = false,
    /** The user's echo-cancellation choices; a category without an entry keeps its default. */
    val echoCancellationOverrides: Map<AudioDeviceCategory, Boolean> = emptyMap(),
    /** The audio device the user saved; null routes automatically. */
    val preferredDevice: PreferredAudioDevice? = null,
    /** Take a connected Bluetooth headset automatically, now and whenever one connects. */
    val bluetoothAutomatic: Boolean = false,
    val pipeline: PipelineSettings = PipelineSettings(),
) {
    /**
     * What a pipeline for a route to [routedDeviceType] is built from. The echo canceller is the
     * user's override for that kind of device, else the kind's default; with no route there is no
     * canceller, since nothing plays that could echo.
     */
    internal fun toAudioConfig(routedDeviceType: Int?): AudioConfig {
        val echo = routedDeviceType?.let(AudioDeviceCategory::of)
            ?.let { echoCancellationOverrides[it] ?: it.echoCancellationByDefault } == true
        return AudioConfig(
            settings = pipeline,
            halfDuplex = halfDuplex && transmitMode == TransmitMode.PUSH_TO_TALK,
            routedDeviceType = routedDeviceType,
            echoCancellation = if (echo) EchoCancellationMode.WEBRTC else EchoCancellationMode.NONE,
        )
    }
}
