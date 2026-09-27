/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.mumla.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R

/**
 * Heads-up notification for unread chat messages, augmenting [MumlaConnectionNotification], as a
 * conversation with an inline reply to the current channel. It counts every unread message but
 * shows only the newest [MAX_MESSAGES], each cut to [MAX_TEXT_CHARS].
 */
class MumlaMessageNotification(private val context: Context) {
    private val messages = ArrayDeque<NotificationCompat.MessagingStyle.Message>()
    private var unreadCount = 0
    private var channelCreated = false
    private var conversation = Conversation()

    /** Where the conversation happens: [self] is our name, [channel] where a reply goes, on [server]. */
    data class Conversation(val self: String? = null, val channel: String? = null, val server: String? = null)

    /** Adds a message from [actorName] with the plain (markup-free) [text] and posts the conversation. */
    fun show(actorName: String, text: String, conversation: Conversation = Conversation()) {
        unreadCount++
        val sender = Person.Builder().setName(actorName).build()
        add(NotificationCompat.MessagingStyle.Message(text.ellipsize(MAX_TEXT_CHARS), now(), sender))
        this.conversation = conversation
        post(silent = false)
    }

    /** Adds our own inline [reply], which counts as nothing unread, and re-posts without alerting. */
    fun showReply(reply: String, conversation: Conversation = Conversation()) {
        add(NotificationCompat.MessagingStyle.Message(reply.ellipsize(MAX_TEXT_CHARS), now(), null as Person?))
        this.conversation = conversation
        post(silent = true)
    }

    /** Re-posts the conversation unchanged and without alerting, e.g. after an empty reply. */
    fun refresh() {
        if (messages.isNotEmpty()) post(silent = true)
    }

    /** Dismisses the notification, marking all messages read. */
    fun dismiss() {
        messages.clear()
        unreadCount = 0
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun add(message: NotificationCompat.MessagingStyle.Message) {
        messages.addLast(message)
        while (messages.size > MAX_MESSAGES) messages.removeFirst()
    }

    private fun post(silent: Boolean) {
        val self = Person.Builder()
            .setName(conversation.self?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.notification_you))
            .build()
        val style = NotificationCompat.MessagingStyle(self)
            .setConversationTitle(conversation.channel)
            .setGroupConversation(true)
        messages.forEach(style::addMessage)

        val channelListIntent = MainScreen.intent(context, MainScreen.CHANNELS)
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        val pendingIntent = PendingIntent.getActivity(
            context, 0, channelListIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        ensureChannel()
        val notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSubText(conversation.server)
            .setVibrate(VIBRATION_PATTERN)
            .setStyle(style)
            .setNumber(unreadCount)
            .setSilent(silent)
            .addAction(replyAction())
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The inline reply, delivered to [MumlaService] as [ACTION_REPLY]. Mutable, which RemoteInput
     * needs to add the typed text, and so explicit.
     */
    private fun replyAction(): NotificationCompat.Action {
        val intent = Intent(context, MumlaService::class.java).setAction(ACTION_REPLY)
        val pendingIntent = PendingIntent.getService(
            context, REPLY_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val label = context.getString(R.string.notification_reply)
        return NotificationCompat.Action.Builder(R.drawable.ic_action_send, label, pendingIntent)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun ensureChannel() {
        if (channelCreated) return
        NotificationChannels.create(context)
        channelCreated = true
    }

    companion object {
        /** The service intent action of an inline reply; see [replyText]. */
        const val ACTION_REPLY = "se.lublin.mumla.action.CHAT_REPLY"

        private const val NOTIFICATION_ID = 2
        private const val REPLY_REQUEST_CODE = 2
        private const val KEY_REPLY = "reply"
        private const val MAX_MESSAGES = 5
        private const val MAX_TEXT_CHARS = 200
        private val VIBRATION_PATTERN = longArrayOf(0, 100)

        /** The text typed into an [ACTION_REPLY] intent's reply field, or null. */
        fun replyText(intent: Intent): String? =
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()

        private fun now() = System.currentTimeMillis()

        /** Cuts this to at most [max] characters, ending in an ellipsis, without splitting a surrogate pair. */
        private fun String.ellipsize(max: Int): String {
            if (length <= max) return this
            var end = max - 1
            if (this[end - 1].isHighSurrogate()) end--
            return substring(0, end) + "…"
        }
    }
}
