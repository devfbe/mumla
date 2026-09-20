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
 * Spec B7: `AudioHandler` surfaces [Silenced] and [Error] to the user (stream A8) and retries
 * capture two seconds after a [Silenced]. It is the "my chain is dead" channel spec 4.1 asked for
 * after task 4 -- a released stage and a stage with no opinion both answer `null`, so a missing
 * frame is not, by itself, tellable from a broken one.
 *
 * Every value is delivered on whichever thread produced it: [Error] on the capture thread,
 * [Silenced] and [Active] on the binder thread the platform calls its recording callback on.
 * Whoever installs a listener owns making it safe for both.
 */
sealed interface CaptureState {
    /** Capture is running and the platform is letting us hear the microphone. */
    data object Active : CaptureState

    /**
     * The OS muted our `AudioRecord` -- another app took the microphone, a privacy toggle is on, or
     * we went to the background without a microphone foreground-service type. The read keeps
     * succeeding and every sample is zero, which is why this needs a channel of its own: silence
     * is indistinguishable from a very quiet room at the sample level.
     */
    data object Silenced : CaptureState

    data class Error(val message: String) : CaptureState
}
