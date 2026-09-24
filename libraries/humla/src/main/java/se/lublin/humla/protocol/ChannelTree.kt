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

package se.lublin.humla.protocol

import android.util.Log
import se.lublin.humla.model.Channel
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler.Companion.MAX_CHANNEL_DEPTH
import se.lublin.humla.protocol.ModelHandler.Companion.ROOT_CHANNEL_ID
import se.lublin.humla.session.HumlaEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The channels of [ModelHandler]'s model, keyed by id. Written on the protocol thread only; read
 * from any thread.
 */
internal class ChannelTree {

    // ConcurrentHashMap: lookups come from the main thread while the protocol thread writes; a
    // HashMap read during a rehash can miss a present key.
    private val channels = ConcurrentHashMap<Int, Channel>()

    operator fun get(id: Int): Channel? = channels[id]

    val all: Collection<Channel> get() = channels.values

    /**
     * The channel with [id], or a new stub for it: a user or channel may name a channel before its
     * ChannelState arrived, which then fills in the same object.
     */
    fun getOrStub(id: Int): Channel = channels[id] ?: Channel(id, false).also { channels[id] = it }

    /** Applies [msg]; returns what changed, or null for a frame without an id. */
    fun apply(msg: Mumble.ChannelState): HumlaEvent? {
        if (!msg.hasChannelId()) return null
        val existing = channels[msg.channelId]
        val channel = existing ?: Channel(msg.channelId, msg.temporary).also { channels[msg.channelId] = it }

        if (msg.hasName()) channel.setName(msg.name)
        if (msg.hasPosition()) channel.setPosition(msg.position)
        // Looked up only after the channel itself exists, so a frame whose channel id is its own
        // parent id finds that channel.
        if (msg.hasParent()) hang(channel, getOrStub(msg.parent))
        applyDescription(channel, msg)
        applyLinks(channel, msg)
        return if (existing == null) HumlaEvent.ChannelAdded(channel) else HumlaEvent.ChannelStateUpdated(channel)
    }

    /** Removes the channel [id] from the tree; the root is never removed. */
    fun remove(id: Int): Channel? {
        val channel = channels[id]?.takeIf { it.getId() != ROOT_CHANNEL_ID } ?: return null
        channels.remove(id)
        channel.getParent()?.removeSubchannel(channel)
        return channel
    }

    /** Hangs [channel] under [named], or where [fallbackParent] says if that is refused. */
    private fun hang(channel: Channel, named: Channel) {
        val parent = (if (mayHang(channel, named)) named else fallbackParent(channel)) ?: return
        val oldParent = channel.getParent()
        channel.setParent(parent)
        parent.addSubchannel(channel)
        oldParent?.removeSubchannel(channel)
    }

    private fun applyDescription(channel: Channel, msg: Mumble.ChannelState) {
        if (msg.hasDescriptionHash()) {
            channel.setDescriptionHash(msg.descriptionHash.toByteArray())
            channel.setDescription(null)
        }
        if (msg.hasDescription()) {
            channel.setDescription(msg.description)
            channel.setDescriptionHash(null)
        }
    }

    private fun applyLinks(channel: Channel, msg: Mumble.ChannelState) {
        if (msg.linksCount > 0) {
            // Don't add this channel to the other channel's link list: we get a message for the
            // other channels' links later during server synchronization. One replacement rather
            // than clear-then-add: the main thread must never see the emptied list.
            channel.setLinks(msg.linksList.map { channels[it] })
        }
        // Unlike a parent, an unknown linked channel is skipped rather than stubbed.
        for (link in msg.linksRemoveList) {
            val linked = channels[link] ?: continue
            channel.removeLink(linked)
            linked.removeLink(channel)
        }
        for (link in msg.linksAddList) {
            val linked = channels[link] ?: continue
            channel.addLink(linked)
            linked.addLink(channel)
        }
    }

    /**
     * Whether [channel] may be hung under [parent]. Refuses a parent that is [channel] or one of
     * its descendants (a cycle), and a parent already [MAX_CHANNEL_DEPTH] below the root. Compares
     * by identity: channels with equal ids are equal, but the question is about the linked objects.
     */
    private fun mayHang(channel: Channel, parent: Channel): Boolean {
        var depth = 0
        var above: Channel? = parent
        var refusal: String? = null
        while (above != null && refusal == null) {
            refusal = when {
                above === channel -> "refusing to make channel ${channel.getId()} its own ancestor"
                ++depth > MAX_CHANNEL_DEPTH ->
                    "refusing to hang channel ${channel.getId()} deeper than $MAX_CHANNEL_DEPTH"
                else -> null
            }
            above = above.getParent()
        }
        refusal?.let { Log.w(TAG, it) }
        return refusal == null
    }

    /**
     * Where a channel goes when [mayHang] refuses the parent its frame names, or null to leave it
     * where it is.
     *
     * A parentless channel would never heal (the server does not resend its ChannelState) and would
     * be invisible together with its users, since the UI only walks down from the root. So a
     * refused channel without a parent is hung under the root; one that already has a parent keeps
     * it.
     */
    private fun fallbackParent(channel: Channel): Channel? {
        if (channel.getParent() != null) return null
        // The root's own frame need not have arrived first. The fallback is a hang like any other:
        // a frame naming the root as its own parent must not make the root its own parent.
        return getOrStub(ROOT_CHANNEL_ID).takeIf { mayHang(channel, it) }
    }

    private companion object {
        val TAG: String = ChannelTree::class.java.name
    }
}
