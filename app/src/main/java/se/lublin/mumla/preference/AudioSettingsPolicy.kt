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

package se.lublin.mumla.preference

import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadMode

/** Which group of voice-gate controls the screen shows. Exactly one is true. */
data class VadDependents(val adaptive: Boolean, val amplitude: Boolean, val probability: Boolean)

/**
 * The decisions behind `AudioSettingsFragment`, separated from it so they can be tested without a
 * Fragment, an Activity or a window.
 */
object AudioSettingsPolicy {
    val ECHO_NONE = EchoCancellationMode.NONE.preferenceValue
    val ECHO_ANDROID = EchoCancellationMode.ANDROID.preferenceValue
    val ECHO_WEBRTC = EchoCancellationMode.WEBRTC.preferenceValue

    /**
     * Hidden, not disabled. The three modes compare three quantities that are not comparable -- a
     * level in dBFS, a speech model's probability, and a fraction of a measured gap -- so a control
     * left visible in the wrong mode invites the user to calibrate the one that is not in use. The
     * `when` is exhaustive on purpose: a fourth mode has to be given an answer here.
     */
    fun vadDependents(mode: VadMode): VadDependents = when (mode) {
        VadMode.ADAPTIVE -> VadDependents(adaptive = true, amplitude = false, probability = false)
        VadMode.AMPLITUDE -> VadDependents(adaptive = false, amplitude = true, probability = false)
        VadMode.PROBABILITY -> VadDependents(adaptive = false, amplitude = false, probability = true)
    }

    /** The hand-set background level only means anything while the tracking is switched off. */
    fun manualFloorVisible(mode: VadMode, adaptiveFloor: Boolean): Boolean =
        vadDependents(mode).adaptive && !adaptiveFloor

    /** Spec B9: the Speex depth reaches nothing unless Speex is the denoiser in the chain. */
    fun speexDepthVisible(mode: NoiseSuppressionMode): Boolean = mode == NoiseSuppressionMode.SPEEX

    fun echoCancellationValues(androidAecAvailable: Boolean): List<String> =
        if (androidAecAvailable) listOf(ECHO_NONE, ECHO_ANDROID, ECHO_WEBRTC)
        else listOf(ECHO_NONE, ECHO_WEBRTC)

    fun fallbackEchoValue(current: String?, available: List<String>): String =
        if (current != null && current in available) current else ECHO_NONE
}
