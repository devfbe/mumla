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
package se.lublin.humla.util

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A composite wrapper around Humla observers to easily broadcast to each observer.
 * Created by andrew on 12/07/14.
 */
class HumlaCallbacks : IHumlaObserver {
    private val observers: MutableSet<IHumlaObserver> = Collections.newSetFromMap(ConcurrentHashMap())

    fun registerObserver(observer: IHumlaObserver) {
        observers.add(observer)
    }

    fun unregisterObserver(observer: IHumlaObserver) {
        observers.remove(observer)
    }

    private inline fun each(block: (IHumlaObserver) -> Unit) {
        for (observer in observers) block(observer)
    }

    override fun onConnected() = each { it.onConnected() }
    override fun onConnecting() = each { it.onConnecting() }
    override fun onDisconnected(e: HumlaException?) = each { it.onDisconnected(e) }
    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>?) = each { it.onTLSHandshakeFailed(chain) }
    override fun onChannelAdded(channel: IChannel?) = each { it.onChannelAdded(channel) }
    override fun onChannelStateUpdated(channel: IChannel?) = each { it.onChannelStateUpdated(channel) }
    override fun onChannelRemoved(channel: IChannel?) = each { it.onChannelRemoved(channel) }
    override fun onChannelPermissionsUpdated(channel: IChannel?) = each { it.onChannelPermissionsUpdated(channel) }
    override fun onUserConnected(user: IUser?) = each { it.onUserConnected(user) }
    override fun onUserStateUpdated(user: IUser?) = each { it.onUserStateUpdated(user) }
    override fun onUserTalkStateUpdated(user: IUser?) = each { it.onUserTalkStateUpdated(user) }
    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) =
        each { it.onUserJoinedChannel(user, newChannel, oldChannel) }
    override fun onUserRemoved(user: IUser?, reason: String?) = each { it.onUserRemoved(user, reason) }
    override fun onPermissionDenied(reason: String?) = each { it.onPermissionDenied(reason) }
    override fun onMessageLogged(message: IMessage?) = each { it.onMessageLogged(message) }
    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) = each { it.onVoiceTargetChanged(mode) }
    override fun onLogInfo(message: String?) = each { it.onLogInfo(message) }
    override fun onLogWarning(message: String?) = each { it.onLogWarning(message) }
    override fun onLogError(message: String?) = each { it.onLogError(message) }
}
