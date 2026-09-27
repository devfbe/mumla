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

package se.lublin.humla.audio.capture

import android.media.MediaRecorder

/**
 * Uses the `VOICE_COMMUNICATION` source and `MODE_IN_COMMUNICATION` whenever any Android audio effect
 * or echo canceller is active. Source and audio mode must agree (an AEC on a `MIC` session in
 * `MODE_NORMAL` misbehaves on many devices), so [resolve] is defined via [needsCommunicationMode].
 *
 * WebRTC AEC3 counts too: only the communication path gives capture and playback a shared clock and
 * fixed low capture latency. Downside: it also enables the device's own pre-processing, which then
 * runs in front of ours.
 */
internal object AudioSourcePolicy {
    fun needsCommunicationMode(effects: AndroidAudioEffects, echo: EchoCancellationMode): Boolean =
        effects.any || echo != EchoCancellationMode.NONE

    /** @return [requested] untouched, or `VOICE_COMMUNICATION` when [needsCommunicationMode]. */
    fun resolve(requested: Int, effects: AndroidAudioEffects, echo: EchoCancellationMode): Int =
        if (needsCommunicationMode(effects, echo)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else requested
}
