package se.lublin.humla.model

/** The limits and welcome text from the server's `ServerConfig`. */
interface IServerSettings {
    val allowHtml: Boolean
    val messageLength: Int
    val imageMessageLength: Int
    val maxBandwidth: Int
    val maxUsers: Int
    val welcomeText: String

    /**
     * Whether the server allows clients to record. Servers that don't say so allow it. This client
     * has no recorder, so it only reports this.
     */
    val recordingAllowed: Boolean
}
