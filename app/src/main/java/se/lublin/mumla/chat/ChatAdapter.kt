/*
 * Copyright (C) 2025 The Mumla Authors
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

package se.lublin.mumla.chat

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.model.Message
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ListChatItemBinding
import se.lublin.mumla.databinding.ListChatItemImageBinding
import java.text.DateFormat
import java.util.Date

/**
 * The chat list. Three view types: a text message, an informational notice and a message whose
 * body contains an image. Bodies are parsed into [ChatContent] in [submitMessages], never during
 * bind.
 *
 * Threading: [submitMessages] and every RecyclerView entry point run on the main thread, and [scope]
 * must dispatch there too (the thumbnail coroutine touches views). The diff runs on [differConfig]'s
 * background executor.
 *
 * [DIFF] compares by identity, which is cheap only while the message log hands out the same
 * instances and never edits one in place; rebuilt message objects make every row a delete plus an
 * insert.
 *
 * @param selfSessionId the local user's session id, used to align rows. Must not throw (the session
 *   is not synchronized while the log is still shown after a disconnect).
 * @param onImageClicked called with the raw `src` of the tapped row. Before opening
 *   `ImageViewerDialogFragment`, check `findFragmentByTag(ImageViewerDialogFragment.TAG) == null`:
 *   two live viewers would write the same share file.
 */
class ChatAdapter(
    private val parser: ChatContentParser,
    private val loader: ChatImageLoader,
    private val thumbnailPx: Int,
    private val selfSessionId: () -> Int,
    private val onImageClicked: (String) -> Unit,
    private val scope: CoroutineScope,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    differConfig: AsyncDifferConfig<IChatMessage> = AsyncDifferConfig.Builder(DIFF).build(),
) : ListAdapter<IChatMessage, ChatAdapter.Holder>(differConfig) {

    private val timeFormat: DateFormat = DateFormat.getTimeInstance()

    /** Lets a slower parse not overwrite a newer list. Main thread only. */
    private var submitGeneration = 0

    /**
     * Parses every message that has no [IChatMessage.content] yet on [parseDispatcher], then submits
     * the list on the caller's (main) thread. The only entry point: calling `submitList` directly
     * would let [getItemViewType] see an unparsed message. Parses can finish out of order, so a
     * stale one is dropped (`AsyncListDiffer`'s own generation counter starts too late for that).
     */
    suspend fun submitMessages(messages: List<IChatMessage>) {
        val snapshot = messages.toList()
        val generation = ++submitGeneration
        withContext(parseDispatcher) {
            for (message in snapshot) {
                if (message.content == null) message.content = parser.parse(message.body)
            }
        }
        if (generation != submitGeneration) return
        submitList(snapshot)
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return when {
            message.content is ChatContent.Image -> TYPE_IMAGE
            message is IChatMessage.InfoMessage -> TYPE_INFO
            else -> TYPE_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_IMAGE) {
            ImageHolder(ListChatItemImageBinding.inflate(inflater, parent, false))
        } else {
            TextHolder(ListChatItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = getItem(position)
        holder.time.text = timeFormat.format(Date(message.receivedTime))
        bindHeader(holder, message)
        when (holder) {
            is TextHolder -> holder.text.apply {
                text = (message.content as? ChatContent.Text)?.spanned ?: message.body
                gravity = holder.box.gravity
                movementMethod = LinkMovementMethod.getInstance()
            }
            // Safe: content is written once per message, and getItemViewType saw an Image.
            is ImageHolder -> bindImage(holder, message.content as ChatContent.Image)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        if (holder is ImageHolder) {
            holder.job?.cancel()
            holder.job = null
            holder.image.setImageDrawable(null)
        }
    }

    private fun bindHeader(holder: Holder, message: IChatMessage) {
        message.accept(object : IChatMessage.Visitor {
            override fun visit(message: IChatMessage.TextMessage) {
                holder.box.gravity =
                    if (message.message.actor == selfSessionId()) Gravity.END else Gravity.START
                holder.target.text = targetLabel(holder.itemView.context, message.message)
                holder.target.visibility = View.VISIBLE
            }

            override fun visit(message: IChatMessage.InfoMessage) {
                holder.box.gravity = Gravity.START
                holder.target.visibility = View.GONE
            }
        })
    }

    private fun bindImage(holder: ImageHolder, content: ChatContent.Image) {
        holder.textBefore.setTextOrGone(content.textBefore)
        holder.textAfter.setTextOrGone(content.textAfter)
        // Captions follow the bubble's side; set on every bind so a recycled holder cannot keep it.
        holder.textBefore.gravity = holder.box.gravity
        holder.textAfter.gravity = holder.box.gravity
        // Reset a recycled holder's previous bitmap or failure text.
        holder.image.visibility = View.VISIBLE
        holder.image.setImageDrawable(null)
        holder.status.visibility = View.GONE
        holder.image.setOnClickListener { onImageClicked(content.source) }
        holder.job?.cancel()
        holder.job = scope.launch {
            when (val result = loader.loadThumbnail(content.source, thumbnailPx, thumbnailPx)) {
                is ImageResult.Ready -> holder.image.setImageBitmap(result.bitmap)
                is ImageResult.Failed -> {
                    holder.image.visibility = View.GONE
                    holder.status.visibility = View.VISIBLE
                    holder.status.setText(R.string.chat_image_load_failed)
                }
                // Only for a non-positive thumbnailPx; leave the placeholder rather than show an error.
                is ImageResult.Skipped -> Unit
            }
        }
    }

    /**
     * First named target channel/tree, then user, then the actor alone; a target without a name
     * falls through to the next kind.
     */
    private fun targetLabel(context: Context, message: Message): String {
        val sender = NoticeFormatter(context).senderName(message)
        val channel = message.targetChannels.firstOrNull() ?: message.targetTrees.firstOrNull()
        val target = channel?.name ?: message.targetUsers.firstOrNull()?.name
        return if (target == null) sender else context.getString(R.string.chat_message_to, sender, target)
    }

    private fun TextView.setTextOrGone(value: CharSequence?) {
        if (value.isNullOrEmpty()) {
            visibility = View.GONE
        } else {
            visibility = View.VISIBLE
            text = value
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    sealed class Holder(view: View, val box: LinearLayout, val target: TextView, val time: TextView) :
        RecyclerView.ViewHolder(view)

    class TextHolder(binding: ListChatItemBinding) :
        Holder(binding.root, binding.listChatItemBox, binding.listChatItemTarget, binding.listChatItemTime) {
        val text: TextView = binding.listChatItemText
    }

    class ImageHolder(binding: ListChatItemImageBinding) :
        Holder(binding.root, binding.listChatItemBox, binding.listChatItemTarget, binding.listChatItemTime) {
        val textBefore: TextView = binding.listChatItemTextBefore
        val textAfter: TextView = binding.listChatItemTextAfter
        val image: ImageView = binding.listChatItemImage
        val status: TextView = binding.listChatItemImageStatus
        var job: Job? = null
    }

    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_INFO = 1
        const val TYPE_IMAGE = 2

        /**
         * Identity is the item id: the message log appends immutable instances and never edits one.
         * `===` (not `==`) keeps two identical-looking messages as two rows. [areContentsTheSame] is
         * only asked about identical objects, so it is `true` by construction.
         */
        val DIFF: DiffUtil.ItemCallback<IChatMessage> = object : DiffUtil.ItemCallback<IChatMessage>() {
            override fun areItemsTheSame(oldItem: IChatMessage, newItem: IChatMessage) =
                oldItem === newItem

            override fun areContentsTheSame(oldItem: IChatMessage, newItem: IChatMessage) = true
        }
    }
}
