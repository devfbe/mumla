
package se.lublin.humla.model

import se.lublin.humla.protobuf.Mumble

/** The server's `ServerConfig`. */
data class ServerSettings(
    val allowHtml: Boolean,
    val messageLength: Int,
    val imageMessageLength: Int,
    val maxBandwidth: Int,
    val maxUsers: Int,
    val welcomeText: String,
    /**
     * Whether the server allows clients to record. Servers that don't say so allow it. This client
     * has no recorder, so it only reports this.
     */
    val recordingAllowed: Boolean,
) {
    internal companion object {
        fun from(msg: Mumble.ServerConfig) = ServerSettings(
            allowHtml = msg.allowHtml,
            messageLength = msg.messageLength,
            imageMessageLength = msg.imageMessageLength,
            maxBandwidth = msg.maxBandwidth,
            maxUsers = msg.maxUsers,
            welcomeText = msg.welcomeText,
            recordingAllowed = !msg.hasRecordingAllowed() || msg.recordingAllowed,
        )
    }
}
