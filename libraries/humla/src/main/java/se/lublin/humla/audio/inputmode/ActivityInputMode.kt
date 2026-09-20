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

/**
 * Voice-activity input mode: delegates to a [VoiceActivityDetector] (spec B5).
 *
 * All the logic is the detector's. What lives here is the seam to the two Java call sites in
 * `HumlaService` -- `new ActivityInputMode(0)` at `:270` and `setThreshold(float)` at `:547` --
 * which have to keep compiling and keep meaning what they meant.
 */
class ActivityInputMode(private val detector: VoiceActivityDetector) : IInputMode {
    /** Legacy constructor kept for HumlaService: a single amplitude slider. */
    constructor(detectionThreshold: Float) : this(VoiceActivityDetector(VadConfig.amplitude(detectionThreshold)))

    val vadConfig: VadConfig get() = detector.config

    /**
     * The legacy slider. It sets the **start** threshold and leaves the hold time alone; the stop
     * threshold follows it at `start - 0.15` (spec B5).
     *
     * **In probability mode it does nothing**, and that is a decision rather than an oversight: a
     * value calibrated against [VoiceActivityDetector.amplitudeScore] has no meaning against a
     * speech model's probability, so applying it would be worse than ignoring it. But it is still
     * a control that silently does nothing while the user drags it, which is a shape this project
     * removes rather than adds -- task 12 owns the settings screen and has to hide or relabel the
     * slider when the mode is [VadMode.PROBABILITY]. It is in the ledger.
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
