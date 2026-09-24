package se.lublin.humla.model

/** The limits and welcome text from the server's `ServerConfig`. */
interface IServerSettings {
    val allowHtml: Boolean
    val messageLength: Int
    val imageMessageLength: Int
    val maxBandwidth: Int
    val maxUsers: Int
    val welcomeText: String
}
