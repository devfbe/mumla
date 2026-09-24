package se.lublin.mumla.channel

import android.content.Context
import android.widget.Toast
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.mumla.R

/**
 * Moves the local user into [channel], or tells the user why not when the server has said they
 * may not enter it.
 */
fun IHumlaSession.joinOrExplain(context: Context, channel: IChannel) {
    if (!channel.canEnter) {
        val text = context.getString(R.string.channel_enter_denied, channel.name)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        return
    }
    joinChannel(channel.id)
}
