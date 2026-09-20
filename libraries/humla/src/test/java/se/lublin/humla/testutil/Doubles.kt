package se.lublin.humla.testutil

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.IHumlaObserver
import se.lublin.humla.util.VoiceTargetMode
import java.security.cert.X509Certificate

/**
 * An [IHumlaObserver] that does nothing, for tests whose subject is what the *producer* does. A
 * mock would work too, but recording thousands of invocations is exactly the cost these tests are
 * measuring around.
 */
open class NoopObserver : IHumlaObserver {
    override fun onConnected() = Unit
    override fun onConnecting() = Unit
    override fun onDisconnected(e: HumlaException?) = Unit
    override fun onTLSHandshakeFailed(chain: Array<out X509Certificate>?) = Unit
    override fun onChannelAdded(channel: IChannel?) = Unit
    override fun onChannelStateUpdated(channel: IChannel?) = Unit
    override fun onChannelRemoved(channel: IChannel?) = Unit
    override fun onChannelPermissionsUpdated(channel: IChannel?) = Unit
    override fun onUserConnected(user: IUser?) = Unit
    override fun onUserStateUpdated(user: IUser?) = Unit
    override fun onUserTalkStateUpdated(user: IUser?) = Unit
    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) = Unit
    override fun onUserRemoved(user: IUser?, reason: String?) = Unit
    override fun onPermissionDenied(reason: String?) = Unit
    override fun onMessageLogged(message: IMessage?) = Unit
    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) = Unit
    override fun onLogInfo(message: String?) = Unit
    override fun onLogWarning(message: String?) = Unit
    override fun onLogError(message: String?) = Unit
}

/** A [HumlaLogger] that drops everything. */
object SilentLogger : HumlaLogger {
    override fun logInfo(message: String?) = Unit
    override fun logWarning(message: String?) = Unit
    override fun logError(message: String?) = Unit
}
