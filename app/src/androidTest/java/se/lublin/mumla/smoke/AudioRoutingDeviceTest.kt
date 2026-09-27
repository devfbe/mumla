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

package se.lublin.mumla.smoke

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.routing.listCommunicationDevices

/** What the audio device menu shows without a session is read from the platform, not routed. */
@RunWith(AndroidJUnit4::class)
class AudioRoutingDeviceTest {
    private val audioManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)

    @Test
    fun listingTheCommunicationDevicesChangesNeitherTheModeNorTheRoute() {
        val mode = audioManager.mode
        val route = audioManager.communicationDevice?.id

        val devices = listCommunicationDevices(audioManager)

        assertThat(devices.map { it.type })
            .containsAnyOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertThat(devices.map { it.id }).containsNoDuplicates()
        assertThat(audioManager.mode).isEqualTo(mode)
        assertThat(audioManager.communicationDevice?.id).isEqualTo(route)
    }
}
