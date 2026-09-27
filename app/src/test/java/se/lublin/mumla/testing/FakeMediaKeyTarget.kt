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

package se.lublin.mumla.testing

import android.content.Context
import androidx.preference.PreferenceManager
import se.lublin.humla.audio.TransmitMode
import se.lublin.mumla.Settings
import se.lublin.mumla.service.MediaKeyTarget

/** Records what the media keys did; stops talking only while connected, as the session does. */
class FakeMediaKeyTarget(
    override var isConnected: Boolean = true,
    override var transmitMode: TransmitMode = TransmitMode.PUSH_TO_TALK,
) : MediaKeyTarget {
    // Backed by a private field: `override var isTalking` would generate a JVM setTalking(Z)V that
    // clashes with the interface's own setTalking.
    private var talking = false
    override val isTalking: Boolean get() = talking
    var muteToggles = 0
        private set
    var stopTalkingCalls = 0
        private set

    override fun setTalking(talking: Boolean) {
        this.talking = talking
    }

    override fun stopTalking() {
        stopTalkingCalls++
        if (isConnected) talking = false
    }

    override fun toggleSelfMute() {
        muteToggles++
    }
}

/** What the headset button does: the preference value of `Settings.MEDIA_BUTTON_ACTION`. */
fun setMediaButtonAction(context: Context, value: String) {
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit().putString(Settings.MEDIA_BUTTON_ACTION.key, value).commit()
}
