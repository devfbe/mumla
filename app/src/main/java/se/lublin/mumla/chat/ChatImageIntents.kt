package se.lublin.mumla.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import se.lublin.mumla.R

/** A chooser that offers [exported] to other apps with a read grant. */
internal fun shareChooser(context: Context, exported: ImageShareExporter.Exported): Intent {
    // ClipData, not only EXTRA_STREAM: createChooser migrates the read grant to the
    // chooser only for the intent's data or ClipData.
    val send = Intent(Intent.ACTION_SEND)
        .setType(exported.mimeType)
        .putExtra(Intent.EXTRA_STREAM, exported.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, exported.uri)
    return Intent.createChooser(send, context.getString(R.string.chat_image_share))
}

/** Opens [saved] in the user's gallery or viewer: the target is an external app, so implicit is correct. */
internal fun viewIntent(saved: ImageGallerySaver.Saved): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(saved.uri, saved.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/** Starts [viewIntent]; call on an activity context, so no NEW_TASK is needed. */
internal fun Context.viewSaved(saved: ImageGallerySaver.Saved) {
    try {
        startActivity(viewIntent(saved))
    } catch (_: ActivityNotFoundException) {
        // The image is saved; only the shortcut to it is unavailable.
    }
}
