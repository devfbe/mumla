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

import android.media.AudioDeviceInfo
import android.media.AudioManager
import se.lublin.humla.protocol.AudioHandler

/**
 * Spec B11: capture at the SCO link rate (8 kHz narrowband, 16 kHz wideband) and let the capture
 * pipeline resample to 48 kHz, rather than asking the HAL to upsample the link for us.
 */
object ScoSampleRatePolicy {
    /** What a headset that advertises no rates gets: HFP wideband, the better of the two links. */
    const val DEFAULT_SCO_RATE = 16000

    /**
     * @param supportedRates `AudioDeviceInfo.getSampleRates()`, which documents an **empty** array
     *   as "any rate is supported" rather than as "none".
     *
     * Above the link rate the plan's listing said "the lowest rate", and that is wrong on the one
     * device it can apply to: for `{48000, 44100}` it buys a resampling stage for nothing.
     * `AudioHandler:267` builds a `ResamplingEncoder` only when the input rate differs from
     * [AudioHandler.SAMPLE_RATE], and `CapturePipeline` takes a null resampler for the same reason,
     * so 48 kHz is free and 44.1 kHz is not. Below that, lowest is cheapest to resample from.
     */
    fun choose(supportedRates: IntArray): Int {
        if (supportedRates.isEmpty()) return DEFAULT_SCO_RATE
        val atOrBelowLink = supportedRates.filter { it <= DEFAULT_SCO_RATE }
        if (atOrBelowLink.isNotEmpty()) return atOrBelowLink.max()
        if (supportedRates.contains(AudioHandler.SAMPLE_RATE)) return AudioHandler.SAMPLE_RATE
        return supportedRates.min()
    }

    /**
     * The SCO headset as a **capture** device. It has to come from the input list: a headset is
     * enumerated on both sides, and the output entry cannot be handed to `AudioRecord`.
     */
    fun findScoInput(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
}
