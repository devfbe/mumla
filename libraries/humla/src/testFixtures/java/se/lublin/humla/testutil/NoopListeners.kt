/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla.testutil

import se.lublin.humla.audio.AudioHandler
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.PlaybackParams
import se.lublin.humla.model.TalkState

internal object NoopEncodeListener : AudioHandler.AudioEncodeListener {
    override fun onAudioEncoded(data: ByteArray, length: Int) = Unit
    override fun onTalkingStateChanged(talking: Boolean) = Unit
}

internal object NoopOutputListener : AudioOutput.AudioOutputListener {
    override val playbackParams: PlaybackParams = PlaybackParams.DEFAULT
    override fun onTalkStateUpdated(session: Int, state: TalkState) = Unit
}
