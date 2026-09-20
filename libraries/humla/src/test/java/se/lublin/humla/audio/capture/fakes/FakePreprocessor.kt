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

package se.lublin.humla.audio.capture.fakes

import se.lublin.humla.audio.capture.CapturePreprocessor

/** Records every frame it sees (copied), applies [transform] in place and returns a fixed [probability]. */
class FakePreprocessor(
    private val probability: Float? = null,
    private val transform: (ShortArray) -> Unit = {},
    private val onProcess: () -> Unit = {},
) : CapturePreprocessor {
    val frames = mutableListOf<ShortArray>()
    var released = false
        private set

    override fun process(frame: ShortArray): Float? {
        frames += frame.copyOf()
        transform(frame)
        onProcess()
        return probability
    }

    override fun release() {
        released = true
    }
}
