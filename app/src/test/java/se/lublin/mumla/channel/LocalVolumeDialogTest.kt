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
package se.lublin.mumla.channel

import android.content.DialogInterface
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper

@RunWith(RobolectricTestRunner::class)
class LocalVolumeDialogTest {
    private val context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val ann = FakeUser(7, "Ann").apply { localVolume = 0.5f }
    private val changed = mutableListOf<IUser>()

    private fun show() = showLocalVolumeDialog(context, session, ann) { changed += it }.also { idleMainLooper() }

    @Test
    fun theSliderStartsAtTheCurrentVolumeAndAppliesChangesLive() {
        val dialog = show()
        val slider = dialog.findViewById<Slider>(R.id.local_volume_slider)!!

        assertThat(slider.value).isEqualTo(50f)
        assertThat(dialog.findViewById<TextView>(R.id.local_volume_value)!!.text.toString()).isEqualTo("50%")

        slider.value = 150f
        verify { session.setLocalVolume(7, 1.5f) }
        assertThat(dialog.findViewById<TextView>(R.id.local_volume_value)!!.text.toString()).isEqualTo("150%")

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()
        assertThat(changed).containsExactly(ann)
    }

    @Test
    fun cancelRestoresTheVolumeTheDialogStartedWith() {
        val dialog = show()
        dialog.findViewById<Slider>(R.id.local_volume_slider)!!.value = 200f

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMainLooper()

        verify { session.setLocalVolume(7, 0.5f) }
        assertThat(changed).isEmpty()
    }

    @Test
    fun resetGoesBackToUnchangedAndStoresIt() {
        val dialog = show()

        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idleMainLooper()

        verify { session.setLocalVolume(7, 1f) }
        assertThat(changed).containsExactly(ann)
    }
}
