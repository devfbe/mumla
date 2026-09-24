/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.audio.inputmode

import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.capture.VoiceActivityDetector

/** Voice-activity input mode: delegates to a [VoiceActivityDetector]. */
class ActivityInputMode(private val detector: VoiceActivityDetector) : IInputMode {
    /** Amplitude mode with a single start threshold. */
    constructor(detectionThreshold: Float) : this(VoiceActivityDetector(VadConfig.amplitude(detectionThreshold)))

    val vadConfig: VadConfig get() = detector.config

    /**
     * Sets the amplitude **start** threshold, keeping the hold time; the stop threshold follows at
     * `start - 0.15`. Ignored in [VadMode.PROBABILITY]: an amplitude value means nothing against
     * a speech probability.
     */
    fun setThreshold(threshold: Float) {
        val current = detector.config
        if (current.mode == VadMode.AMPLITUDE) {
            detector.config = VadConfig.amplitude(threshold, current.holdTimeMs)
        }
    }

    fun setVadConfig(config: VadConfig) {
        detector.config = config
    }

    override fun shouldTransmit(pcm: ShortArray, length: Int, vadProbability: Float?): Boolean =
        detector.isVoice(pcm, length, vadProbability)

    override fun waitForInput() = Unit
}
