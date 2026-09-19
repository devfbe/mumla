package se.lublin.mumla.chat

import android.text.Spanned

/** The parsed body of one chat message. Parsed once per message and cached on the message. */
sealed class ChatContent {
    /** A message without images. */
    data class Text(val spanned: Spanned) : ChatContent()

    /**
     * A message containing an image. [source] is the raw `src` attribute of the first `<img>`;
     * [textBefore]/[textAfter] hold the text around it (null when empty). Further images are
     * replaced by a placeholder inside [textAfter].
     */
    data class Image(val source: String, val textBefore: Spanned?, val textAfter: Spanned?) : ChatContent()
}
