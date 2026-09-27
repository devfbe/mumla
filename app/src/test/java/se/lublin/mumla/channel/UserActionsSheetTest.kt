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

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.SessionActions
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.UserState
import se.lublin.humla.net.Permissions
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.addUnderChatParent
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.userRow

/** The user actions sheet [ChannelListFragment.onUserMore] opens: its header, slider and rows. */
@RunWith(RobolectricTestRunner::class)
class UserActionsSheetTest {

    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelListFragment
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(SERVER, "Home", "example.org", 64738, "me", null)
    }
    private lateinit var model: MutableStateFlow<ServerState?>
    private lateinit var actions: SessionActions

    /** Root(0), MUTE_DEAFEN for us: us (1, Me) and Ann (2), muted, with a comment loaded. */
    private fun tree(ann: UserState = UserState(2, "Ann", 0, isMuted = true, comment = "brb")) =
        serverState(self = 1, permissions = Permissions.MUTE_DEAFEN) {
            channel(0, "Root")
            user(1, "Me", channel = 0)
            user(ann)
        }

    @Before
    fun setUp() {
        model = session.stubModel(tree())
        actions = session.stubActions()
        session.stubConnected()
        controller = hostWith(session)
        fragment = ChannelListFragment.newInstance(pinned = false)
        controller.get().addUnderChatParent(fragment, "list")
        drainMainUntil { fragment.view != null }
    }

    private fun show(sessionId: Int): UserActionsSheet {
        fragment.onUserMore(View(controller.get()), userRow(sessionId))
        drainMainUntil { fragment.childFragmentManager.findFragmentByTag(TAG) != null }
        val sheet = fragment.childFragmentManager.findFragmentByTag(TAG) as UserActionsSheet
        drainMainUntil { sheet.view != null }
        return sheet
    }

    private fun rows(sheet: UserActionsSheet): LinearLayout = sheet.requireView().findViewById(R.id.user_actions_rows)

    private fun rowWithTitle(sheet: UserActionsSheet, titleRes: Int): View {
        val title = controller.get().getString(titleRes)
        val rows = rows(sheet)
        return (0 until rows.childCount).map(rows::getChildAt)
            .first { it.findViewById<TextView>(R.id.user_action_title).text == title }
    }

    /** Lays [sheet]'s slider out with a real width, so touch and key input compute a real value. */
    private fun layOutSlider(sheet: UserActionsSheet): Slider {
        val slider = sheet.requireView().findViewById<Slider>(R.id.user_actions_volume_slider)
        slider.measure(
            View.MeasureSpec.makeMeasureSpec(SLIDER_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(SLIDER_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        slider.layout(0, 0, SLIDER_WIDTH, SLIDER_HEIGHT)
        return slider
    }

    private fun motionEvent(action: Int, x: Float) = MotionEvent.obtain(0, 0, action, x, SLIDER_HEIGHT / 2f, 0)

    @Test
    fun theSheetOpensFromTheRowsMoreButtonAndShowsTheName() {
        val sheet = show(2)

        assertThat(sheet.requireView().findViewById<TextView>(R.id.user_actions_name).text.toString())
            .isEqualTo("Ann")
    }

    @Test
    fun theStatusAndCommentPreviewAreShown() {
        val sheet = show(2)

        assertThat(sheet.requireView().findViewById<TextView>(R.id.user_actions_status).text.toString())
            .isEqualTo(controller.get().getString(R.string.a11y_state_server_muted))
        assertThat(sheet.requireView().findViewById<TextView>(R.id.user_actions_comment).text.toString())
            .isEqualTo("brb")
    }

    @Test
    fun theVolumeSectionIsHiddenForOurselves() {
        val sheet = show(1)

        assertThat(sheet.requireView().findViewById<View>(R.id.user_actions_volume_section).isVisible).isFalse()
    }

    /**
     * A preview and a stored volume both go through the same [SessionActions.setLocalVolume], so
     * only the database write tells them apart (see `ChannelTreeViewModel.setLocalVolume`).
     */
    private fun recordedVolumeWrites(): MutableList<Float> {
        val writes = mutableListOf<Float>()
        every { controller.get().database.setLocalVolume(any(), any()) } answers { writes += secondArg<Float>() }
        return writes
    }

    @Test
    fun draggingTheSliderPreviewsLiveWithoutPersistingUntilReleased() {
        val sheet = show(2)
        assertThat(sheet.requireView().findViewById<View>(R.id.user_actions_volume_section).isVisible).isTrue()
        val slider = layOutSlider(sheet)
        val writes = recordedVolumeWrites()

        slider.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, 10f))
        slider.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, 150f))
        idleMainLooper()

        verify(atLeast = 1) { actions.setLocalVolume(2, any()) } // the live preview, not yet stored
        assertThat(writes).isEmpty()

        slider.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP, 150f))
        drainMainUntil { writes.isNotEmpty() }

        assertThat(writes).hasSize(1)
    }

    @Test
    fun aKeyboardMoveOfTheSliderPersistsDirectly() {
        val sheet = show(2)
        val slider = layOutSlider(sheet)
        val writes = recordedVolumeWrites()

        slider.requestFocus()
        slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        drainMainUntil { writes.isNotEmpty() }

        assertThat(writes).hasSize(1)
    }

    @Test
    fun resettingTheVolumeGoesBackToUnchanged() {
        val sheet = show(2)

        sheet.requireView().findViewById<View>(R.id.user_actions_volume_reset).performClick()
        idleMainLooper()

        verify { actions.setLocalVolume(2, 1f) }
    }

    @Test
    fun tappingTheMuteRowCallsTheMatchingAction() {
        val sheet = show(2)

        rowWithTitle(sheet, R.string.user_menu_mute).performClick()
        idleMainLooper()

        verify { actions.setMuteDeafState(2, false, false) }
    }

    @Test
    fun tappingTheIgnoreMessagesRowCallsTheMatchingAction() {
        val sheet = show(2)

        rowWithTitle(sheet, R.string.user_menu_ignore_messages).performClick()
        idleMainLooper()

        verify { actions.setLocalIgnored(2, true) }
    }

    /**
     * A state change that leaves the visible actions the same (only a switch's state changes)
     * updates the existing row views rather than rebuilding them, so TalkBack keeps its focus.
     */
    @Test
    fun rowsUpdateInPlaceWhenOnlyTheirCheckedStateChanges() {
        val sheet = show(2)
        val deafenRow = rowWithTitle(sheet, R.string.user_menu_deafen)
        val switch = deafenRow.findViewById<MaterialSwitch>(R.id.user_action_switch)
        assertThat(switch.isChecked).isFalse()

        model.value = tree(ann = UserState(2, "Ann", 0, isMuted = true, isDeafened = true, comment = "brb"))
        idleMainLooper()

        assertThat(rowWithTitle(sheet, R.string.user_menu_deafen)).isSameInstanceAs(deafenRow)
        assertThat(switch.isChecked).isTrue()
    }

    @Test
    fun aCheckableRowExposesSwitchSemanticsToTalkBack() {
        val sheet = show(2)
        // Ann (session 2) is muted in the fixture, so the mute row starts checked.
        val muteRow = rowWithTitle(sheet, R.string.user_menu_mute)

        val info = AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(muteRow, info)

        assertThat(info.className).isEqualTo("android.widget.Switch")
        assertThat(info.isCheckable).isTrue()
        assertThat(info.isChecked).isTrue()
    }

    private companion object {
        const val SERVER = 42L
        const val TAG = "UserActions"
        const val SLIDER_WIDTH = 600
        const val SLIDER_HEIGHT = 50
    }
}
