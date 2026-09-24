/*
 * Copyright (C) 2014 Andrew Comminos
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

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser

/** Where the next chat message goes. */
sealed interface ChatTarget {
    val name: String?

    data class Channel(val channel: IChannel) : ChatTarget {
        override val name: String? get() = channel.name
    }

    data class User(val user: IUser) : ChatTarget {
        override val name: String? get() = user.name
    }
}

/**
 * The chat target a [ChannelFragment] shares with its list and chat; null means the user's
 * current channel.
 */
class ChatTargetViewModel : ViewModel() {
    private val selected = MutableStateFlow<ChatTarget?>(null)

    val target: StateFlow<ChatTarget?> = selected.asStateFlow()

    fun select(target: ChatTarget?) {
        selected.value = target
    }
}

/** The [ChatTargetViewModel] of this fragment's parent. */
fun Fragment.parentChatTargets(): Lazy<ChatTargetViewModel> = viewModels(ownerProducer = { requireParentFragment() })
