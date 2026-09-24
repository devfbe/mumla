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

/** A talk state engine, providing information regarding when it is appropriate to send audio. */
interface IInputMode {
    /**
     * Called for every frame after preprocessing, never gated on the talking state.
     *
     * @param pcm the preprocessed PCM frame.
     * @param length the number of valid shorts in [pcm].
     * @param vadProbability the preprocessor chain's voice probability, or null when no stage
     *   provides one; null means "no information", not "no voice".
     * @return true if the input should be transmitted.
     */
    fun shouldTransmit(pcm: ShortArray, length: Int, vadProbability: Float?): Boolean
}
