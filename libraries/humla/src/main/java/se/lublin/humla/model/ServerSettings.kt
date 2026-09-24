
package se.lublin.humla.model

import se.lublin.humla.protobuf.Mumble

/** A snapshot of one `ServerConfig` message. */
class ServerSettings(msg: Mumble.ServerConfig) : IServerSettings {
    override val allowHtml: Boolean = msg.allowHtml
    override val messageLength: Int = msg.messageLength
    override val imageMessageLength: Int = msg.imageMessageLength
    override val maxBandwidth: Int = msg.maxBandwidth
    override val maxUsers: Int = msg.maxUsers
    override val welcomeText: String = msg.welcomeText
    override val recordingAllowed: Boolean = !msg.hasRecordingAllowed() || msg.recordingAllowed
}
