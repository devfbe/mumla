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

package se.lublin.humla.model

import se.lublin.humla.protobuf.Mumble

/** Where a whisper goes; built by this library, which alone knows the wire form. */
public abstract class WhisperTarget internal constructor() {
    /** A user-readable name for the UI: a channel name or a list of users. */
    public abstract val name: String?

    internal abstract fun createTarget(): Mumble.VoiceTarget.Target
}
