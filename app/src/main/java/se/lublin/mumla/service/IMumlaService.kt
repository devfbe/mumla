package se.lublin.mumla.service

import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.IHumlaService
import se.lublin.mumla.chat.IChatMessage

/** Mumla's additions to [IHumlaService]. */
interface IMumlaService : IHumlaService {
    val isOverlayShown: Boolean

    /** Whether the current connection error has been shown to the user; see [markErrorShown]. */
    val isErrorShown: Boolean

    /** The chat history, newest last; a new list per change. */
    val messageLog: StateFlow<List<IChatMessage>>

    fun clearChatNotifications()

    fun markErrorShown()

    fun onTalkKeyDown()

    fun onTalkKeyUp()

    fun clearMessageLog()

    fun setSuppressNotifications(suppressNotifications: Boolean)
}
