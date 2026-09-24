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

package se.lublin.humla.session

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What kind of device a route is, as far as the user can tell - and so the unit the echo
 * canceller's default and the user's override for it are kept per.
 */
class AudioDeviceCategoryTest {
    @Test
    fun everyDeviceTheChooserOffersHasItsCategory() {
        assertThat(AudioDeviceCategory.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isEqualTo(AudioDeviceCategory.SPEAKER)
        assertThat(AudioDeviceCategory.of(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isEqualTo(AudioDeviceCategory.EARPIECE)
        for (type in AudioRouter.BLUETOOTH) {
            assertThat(AudioDeviceCategory.of(type)).isEqualTo(AudioDeviceCategory.BLUETOOTH)
        }
        for (type in AudioRouter.WIRED) {
            assertThat(AudioDeviceCategory.of(type)).isEqualTo(AudioDeviceCategory.WIRED)
        }
        assertThat(AudioDeviceCategory.of(AudioDeviceInfo.TYPE_HDMI)).isEqualTo(AudioDeviceCategory.OTHER)
    }

    /**
     * The user's rule: the canceller is on where the phone's own speaker can feed the microphone
     * - the loudspeaker and the earpiece - and off on a headset, where it only costs quality.
     */
    @Test
    fun echoCancellationIsOnByDefaultOnlyWhereThePhonePlaysOutLoud() {
        assertThat(AudioDeviceCategory.SPEAKER.echoCancellationByDefault).isTrue()
        assertThat(AudioDeviceCategory.EARPIECE.echoCancellationByDefault).isTrue()
        assertThat(AudioDeviceCategory.BLUETOOTH.echoCancellationByDefault).isFalse()
        assertThat(AudioDeviceCategory.WIRED.echoCancellationByDefault).isFalse()
        assertThat(AudioDeviceCategory.OTHER.echoCancellationByDefault).isFalse()
    }
}
