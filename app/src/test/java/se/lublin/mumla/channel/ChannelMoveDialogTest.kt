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
package se.lublin.mumla.channel

import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.model.ChannelState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.drainMainUntil

/** The move dialog shows the tree indented and narrows it by name. */
@RunWith(RobolectricTestRunner::class)
class ChannelMoveDialogTest {
    /** Root > Games > (Quake, Chess); Root > Lobby, in tree order. */
    private val tree = listOf(
        ChannelState(0, "Root"),
        ChannelState(1, "Games", parent = 0),
        ChannelState(3, "Quake", parent = 1),
        ChannelState(4, "Chess", parent = 1),
        ChannelState(2, "Lobby", parent = 0),
    )

    @Test
    fun eachChannelIsIndentedByItsDepth() {
        assertThat(moveTargets(tree, "")).containsExactly(
            MoveTarget(0, "Root", 0),
            MoveTarget(1, "Games", 1),
            MoveTarget(3, "Quake", 2),
            MoveTarget(4, "Chess", 2),
            MoveTarget(2, "Lobby", 1),
        ).inOrder()
    }

    @Test
    fun aSearchKeepsTheMatchesWithTheirDepthIgnoringCaseAndSpaces() {
        assertThat(moveTargets(tree, " qua ")).containsExactly(MoveTarget(3, "Quake", 2))
        assertThat(moveTargets(tree, "o").map { it.name }).containsExactly("Root", "Lobby").inOrder()
    }

    @Test
    fun typingNarrowsTheListAndATapMovesThere() {
        val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        var picked: Int? = null
        showChannelMoveDialog(activity, tree) { picked = it }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.findViewById<RecyclerView>(R.id.channel_move_list)!!
        fun layOut() {
            idleMainLooper()
            list.measure(0, 0)
            list.layout(0, 0, 1000, 4000)
        }
        layOut()
        assertThat(list.adapter!!.itemCount).isEqualTo(5)

        dialog.findViewById<EditText>(R.id.channel_move_search)!!.setText("chess")
        drainMainUntil { list.adapter!!.itemCount == 1 }
        layOut()
        val row = list.getChildAt(0) as TextView
        assertThat(row.text.toString()).isEqualTo("Chess")
        assertThat(row.contentDescription.toString())
            .isEqualTo(activity.getString(R.string.a11y_channel_level, "Chess", 2))
        row.performClick()

        assertThat(picked).isEqualTo(4)
        assertThat(dialog.isShowing).isFalse()
    }
}
