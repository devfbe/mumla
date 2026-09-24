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
package se.lublin.mumla.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The pinned channels of each server, kept in memory after the first read so that menus and lists
 * never query the database on the main thread. A change shows at once and is written in the
 * background.
 */
class PinnedChannels internal constructor(
    private val repository: MumlaRepository,
    private val scope: CoroutineScope,
) {
    private val servers = ConcurrentHashMap<Long, MutableStateFlow<Set<Int>?>>()

    /** Keeps the reads and writes in the order they were asked for. */
    private val order = Mutex()

    /** The pinned channel ids of [serverId], in pinning order; null until read, which this starts. */
    fun of(serverId: Long): StateFlow<Set<Int>?> = flow(serverId).asStateFlow()

    /** Whether [channelId] is pinned; false while the server's pins are not read yet. */
    fun isPinned(serverId: Long, channelId: Int): Boolean = flow(serverId).value?.contains(channelId) == true

    fun setPinned(serverId: Long, channelId: Int, pinned: Boolean) {
        val flow = flow(serverId)
        flow.update { ids -> ids?.let { if (pinned) it + channelId else it - channelId } }
        scope.launch {
            order.withLock {
                flow.value = repository.io {
                    if (pinned) addPinnedChannel(serverId, channelId) else removePinnedChannel(serverId, channelId)
                    getPinnedChannels(serverId)
                }.toSet()
            }
        }
    }

    /**
     * Calls [block] with the pinned channels of [serverId]: at once if they are read already, else
     * on the main thread once they are.
     */
    fun whenLoaded(serverId: Long, block: (Set<Int>) -> Unit) {
        val flow = flow(serverId)
        val loaded = flow.value
        if (loaded != null) {
            block(loaded)
        } else {
            scope.launch(Dispatchers.Main.immediate) { block(flow.filterNotNull().first()) }
        }
    }

    private fun flow(serverId: Long): MutableStateFlow<Set<Int>?> = servers.computeIfAbsent(serverId) {
        MutableStateFlow<Set<Int>?>(null).also { created ->
            scope.launch {
                order.withLock { created.value = repository.io { getPinnedChannels(serverId) }.toSet() }
            }
        }
    }
}
