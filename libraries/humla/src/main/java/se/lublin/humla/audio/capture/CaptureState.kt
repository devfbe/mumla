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

/**
 * What the capture side is currently doing, as seen from outside the capture thread.
 *
 * Delivered on the producing thread: [Error] on the capture thread, [Silenced] and [Active] on the
 * platform's recording-callback binder thread. Listeners must be safe for both.
 */
sealed interface CaptureState {
    /** Capture is running and the platform is letting us hear the microphone. */
    data object Active : CaptureState

    /**
     * The OS silenced our `AudioRecord` (mic taken by another app, privacy toggle, background without
     * a microphone FGS type). Reads keep succeeding with all-zero samples, so this can't be detected
     * from the audio itself.
     */
    data object Silenced : CaptureState

    data class Error(val message: String) : CaptureState
}
