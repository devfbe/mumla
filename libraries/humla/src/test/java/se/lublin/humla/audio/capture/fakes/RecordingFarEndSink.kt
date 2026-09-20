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

import se.lublin.humla.audio.capture.FarEndSink

/**
 * Records the far-end frames it is handed.
 *
 * [frames] holds copies, because the real sink -- `WebRtcApmPreprocessor` -> `processRender` --
 * **may modify the frame in place**, and because a chunker is free to reuse one buffer. [buffers]
 * holds the array references themselves, which is the only way to see whether a chunker handed
 * over the caller's playback buffer instead of its own.
 */
class RecordingFarEndSink : FarEndSink {
    val frames = mutableListOf<ShortArray>()
    val buffers = mutableListOf<ShortArray>()

    override fun analyzeReverseStream(frame: ShortArray) {
        buffers += frame
        frames += frame.copyOf()
    }
}
