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
 * Spec B6: `VOICE_COMMUNICATION` source and `MODE_IN_COMMUNICATION` whenever any android audio
 * effect or any echo canceller is active.
 *
 * The two questions have **one** decision between them: [resolve] is written in terms of
 * [needsCommunicationMode] rather than repeating the condition, because the source and the audio
 * mode have to agree -- a canceller attached to a session captured from `MIC` while the manager
 * sits in `MODE_NORMAL` is the configuration every device vendor's AEC documentation warns about,
 * and two copies of one condition drift (spec 4.04, "one bottleneck instead of N entry guards").
 *
 * [EchoCancellationMode.WEBRTC] is in the condition although the canceller is ours: AEC3 aligns a
 * far-end reference against the capture stream, and only the communication path gives the two a
 * shared clock and a fixed, low capture latency. Spec B6 spells it out ("any effect or WebRTC AEC").
 * The cost is in the ledger: `VOICE_COMMUNICATION` also switches on whatever the device's own
 * pre-processing is, which cascades with ours exactly the way spec 4.1 refused to cascade two noise
 * suppressors. Nothing in this repository can measure that without hardware.
 */
object AudioSourcePolicy {
    @JvmStatic
    fun needsCommunicationMode(effects: AndroidAudioEffects, echo: EchoCancellationMode): Boolean =
        effects.any || echo != EchoCancellationMode.NONE

    /** @return [requested] untouched, or `VOICE_COMMUNICATION` when [needsCommunicationMode]. */
    @JvmStatic
    fun resolve(requested: Int, effects: AndroidAudioEffects, echo: EchoCancellationMode): Int =
        if (needsCommunicationMode(effects, echo)) MediaRecorder.AudioSource.VOICE_COMMUNICATION else requested
}
