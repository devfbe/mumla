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

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionState
import se.lublin.mumla.Settings
import se.lublin.mumla.chat.IChatMessage
import se.lublin.mumla.chat.OutgoingImageEncoder
import se.lublin.mumla.chat.OutgoingImagePreparer
import se.lublin.mumla.chat.outgoingMessageHtml
import se.lublin.mumla.session.SessionManager

/**
 * The chat of the channel screen, shared by its list, which picks the target, and its chat tab:
 * the session's log, the target, and sending text and pictures. Main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val app: Application,
    private val sessions: SessionManager,
    private val preparer: OutgoingImagePreparer = OutgoingImagePreparer(app),
    private val encodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** What sending a picture needs the user to see. */
    sealed interface ImageEvent {
        /** The picture is ready; the user confirms it before it is sent. */
        data class Confirm(val bitmap: Bitmap) : ImageEvent

        data object Unreadable : ImageEvent

        /** No quality fits it under the server's limit, or the server set none. */
        data object TooLarge : ImageEvent
    }

    val messages: StateFlow<List<IChatMessage>> get() = sessions.chat.messages

    /** Messages received since the chat tab was last shown. */
    val unread: StateFlow<Int> get() = sessions.chat.unread

    /** Whether the chat is on screen; see [se.lublin.mumla.chat.SessionChat.setShown]. */
    fun setShown(shown: Boolean) = sessions.chat.setShown(shown)

    private val selected = MutableStateFlow<ChatTarget?>(null)

    /** The target the user picked; null means our current channel. */
    val target: StateFlow<ChatTarget?> = selected.asStateFlow()

    /**
     * Where the next message goes, while the session is synchronized: the target, else our
     * channel, null if there is neither.
     */
    val destination: Flow<ChatTarget?> =
        combine(sessions.session.flatMapLatest { it?.let(::ownChannel) ?: flowOf(null) }, selected, ::Pair)
            .transform { (own, target) -> if (own != null) emit(target ?: own.target) }

    private val busy = MutableStateFlow(false)

    /** Whether a picture is being prepared or encoded. */
    val imageBusy: StateFlow<Boolean> = busy.asStateFlow()

    private val mutableImageEvents = MutableSharedFlow<ImageEvent>(extraBufferCapacity = 1)
    val imageEvents: SharedFlow<ImageEvent> = mutableImageEvents.asSharedFlow()

    /** Our session id, or [NO_SESSION] without a synchronized session. */
    val selfSession: Int get() = sessions.connected?.model?.value?.selfSession ?: NO_SESSION

    fun select(target: ChatTarget?) {
        selected.value = target
    }

    fun clear() = sessions.chat.clear()

    /** Sends what the user typed, formatted as the settings say; false without a connection. */
    fun send(text: String): Boolean = sendHtml(outgoingMessageHtml(text, Settings.getInstance(app).isMarkdownEnabled))

    /** Reads and scales the picture at [uri]; the result arrives in [imageEvents]. */
    fun prepareImage(uri: Uri) {
        if (sessions.connected == null) return
        viewModelScope.launch {
            val bitmap = busyWhile { preparer.prepare(uri) }
            mutableImageEvents.emit(if (bitmap == null) ImageEvent.Unreadable else ImageEvent.Confirm(bitmap))
        }
    }

    /** Encodes a confirmed picture to fit the server's limit and sends it to the destination. */
    fun sendImage(bitmap: Bitmap) {
        val session = sessions.connected ?: return
        viewModelScope.launch {
            val html = busyWhile {
                val maxLength = session.model.value?.serverSettings?.imageMessageLength
                maxLength?.let { withContext(encodeDispatcher) { OutgoingImageEncoder.encode(bitmap, it) } }
            }
            if (html == null) mutableImageEvents.emit(ImageEvent.TooLarge) else sendHtml(html)
        }
    }

    private inline fun <T> busyWhile(work: () -> T): T {
        busy.value = true
        try {
            return work()
        } finally {
            busy.value = false
        }
    }

    /** The session publishes the sent message, and its chat log shows it. */
    private fun sendHtml(html: String): Boolean {
        val session = sessions.connected ?: return false
        val actions = session.actions
        when (val target = selected.value) {
            is ChatTarget.User -> actions.sendUserTextMessage(target.session, html)
            is ChatTarget.Channel -> actions.sendChannelTextMessage(target.id, html, false)
            null -> session.model.value?.selfChannel?.let { actions.sendChannelTextMessage(it.id, html, false) }
        }
        return true
    }

    /** Our channel while [session] is synchronized, as a message target; null while it is not. */
    private fun ownChannel(session: IHumlaSession): Flow<OwnChannel?> =
        combine(session.state, session.model) { state, model ->
            if (state != SessionState.Connected) return@combine null
            OwnChannel(model?.selfChannel?.let { ChatTarget.Channel(it.id, it.name) })
        }

    private class OwnChannel(val target: ChatTarget?)

    companion object {
        /**
         * What [selfSession] answers without a session. Not -1: a message without a sender has
         * that actor, which would render as our own. Mumble session ids are unsigned, so
         * `Int.MIN_VALUE` never collides.
         */
        const val NO_SESSION = Int.MIN_VALUE

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                ChatViewModel(app, SessionManager.get(app))
            }
        }
    }
}

/** The [ChatViewModel] of this fragment's parent, the channel screen. */
fun Fragment.parentChatViewModel(): Lazy<ChatViewModel> =
    viewModels(ownerProducer = { requireParentFragment() }, factoryProducer = { ChatViewModel.Factory })
