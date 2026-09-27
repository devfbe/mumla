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

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.chat.OutgoingImagePreparer
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val state = session.stubState(SessionState.Connected)
    private val actions = session.stubActions()
    private val preparer = mockk<OutgoingImagePreparer>()
    private val chat = ChatViewModel(app, SessionManager.get(app), preparer, Dispatchers.Unconfined)

    init {
        session.stubModel(
            serverState(self = 7) {
                channel(0, "Root")
                channel(3, "Lounge")
                user(7, "Me", channel = 3)
            },
        )
        installSession(session)
    }

    private fun destinations(): List<ChatTarget?> {
        val seen = mutableListOf<ChatTarget?>()
        CoroutineScope(UnconfinedTestDispatcher()).launch { chat.destination.collect { seen += it } }
        idleMainLooper()
        return seen
    }

    @Test
    fun theDestinationIsTheTargetElseOurChannelAndNothingWhileDisconnected() {
        val seen = destinations()
        chat.select(ChatTarget.User(42, "Ann"))
        state.value = SessionState.Disconnected()
        chat.select(null)
        idleMainLooper()

        assertThat(seen).containsExactly(ChatTarget.Channel(3, "Lounge"), ChatTarget.User(42, "Ann")).inOrder()
    }

    @Test
    fun textGoesToTheTargetOrOurChannel() {
        chat.send("a")
        chat.select(ChatTarget.User(42, "Ann"))
        chat.send("b")
        chat.select(ChatTarget.Channel(9, "Other"))
        chat.send("c")

        verify { actions.sendChannelTextMessage(3, "a", false) }
        verify { actions.sendUserTextMessage(42, "b") }
        verify { actions.sendChannelTextMessage(9, "c", false) }
    }

    @Test
    fun withoutAConnectionNothingIsSentAndNoPictureIsRead() {
        state.value = SessionState.Disconnected()

        assertThat(chat.send("a")).isFalse()
        chat.prepareImage(Uri.parse("content://x"))

        verify(exactly = 0) { actions.sendChannelTextMessage(any(), any(), any()) }
        coVerify(exactly = 0) { preparer.prepare(any()) }
    }

    @Test
    fun aPictureIsOfferedForConfirmationOrReportedUnreadable() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val events = mutableListOf<ChatViewModel.ImageEvent>()
        CoroutineScope(UnconfinedTestDispatcher()).launch { chat.imageEvents.collect { events += it } }

        coEvery { preparer.prepare(any()) } returns bitmap
        chat.prepareImage(Uri.parse("content://ok"))
        idleMainLooper()
        coEvery { preparer.prepare(any()) } returns null
        chat.prepareImage(Uri.parse("content://bad"))
        idleMainLooper()

        assertThat(events)
            .containsExactly(ChatViewModel.ImageEvent.Confirm(bitmap), ChatViewModel.ImageEvent.Unreadable).inOrder()
        assertThat(chat.imageBusy.value).isFalse()
    }

    @Test
    fun ourSessionIsKnownOnlyWhileConnected() {
        assertThat(chat.selfSession).isEqualTo(7)

        state.value = SessionState.Disconnected()

        assertThat(chat.selfSession).isEqualTo(ChatViewModel.NO_SESSION)
    }
}
