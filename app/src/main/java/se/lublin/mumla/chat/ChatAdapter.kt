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
import se.lublin.humla.model.IMessage
import se.lublin.mumla.R
import se.lublin.mumla.service.IChatMessage
import java.text.DateFormat
import java.util.Date

/**
 * The chat list. Three view types: a text message, an informational notice and a message whose
 * body contains an image.
 *
 * Bodies are parsed into [ChatContent] before the list is submitted, never during bind — see
 * [submitMessages]. Diffing is by identity because the message log appends immutable instances.
 *
 * **Threading contract.** [submitMessages] and every RecyclerView entry point run on the main
 * thread; [scope] must dispatch there too, because the thumbnail coroutine it starts touches views
 * (`Dispatchers.Main.immediate`, i.e. a `lifecycleScope`, is what Task 11 supplies). The only work
 * this class moves off the main thread is the parse inside [submitMessages] and whatever
 * [ChatImageLoader] does with its own dispatchers.
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

    /**
     * Counts calls to [submitMessages] so a slower parse cannot overwrite a newer list. Read and
     * written on the main thread only, between the call and its first suspension point.
     */
    private var submitGeneration = 0

    /**
     * Parses every message that has no [IChatMessage.content] yet on [parseDispatcher], then submits
     * the list on the caller's (main) thread. The only entry point: calling `submitList` directly
     * would let [getItemViewType] see an unparsed message.
     *
     * Two calls can be in flight at once — one per arriving message — and they parse on a shared
     * pool, so they can finish out of order. Without the generation check below the older, shorter
     * snapshot would be submitted last and the newest message would *disappear* from the log until
     * the one after it arrived. `AsyncListDiffer` has a generation counter of its own, but it starts
     * at `submitList`, which is after the race; this one starts before the parse.
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
            ImageHolder(inflater.inflate(R.layout.list_chat_item_image, parent, false))
        } else {
            TextHolder(inflater.inflate(R.layout.list_chat_item, parent, false))
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
            // Safe: this holder exists only because getItemViewType saw an Image, and content is
            // written exactly once per message (submitMessages only fills a null one), so it cannot
            // have become something else in between.
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
        // The four lines below are the recycled holder's reset: it may arrive showing the previous
        // row's bitmap or its failure text.
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
                // Task 6 returns this for non-positive bounds, which is a view that has not been
                // measured yet. It cannot happen from here — thumbnailPx is a constant handed to the
                // constructor, not a measured width — but reporting a failure would be wrong if it
                // ever did, and ImageResult.Failed(UNSUPPORTED) would additionally be cached for the
                // life of the process. Leave the empty placeholder.
                is ImageResult.Skipped -> Unit
            }
        }
    }

    /**
     * Deliberately different from the `ListView` adapter it replaces in one corner: a target list
     * that is non-empty but names nothing used to render as "Unknown" and stop there. Here it falls
     * through to the next target kind and finally to the actor, which is strictly more informative,
     * and the actor is no longer passed to `setText` as a raw null.
     */
    private fun targetLabel(context: Context, message: IMessage): String {
        val channel = message.targetChannels?.firstOrNull() ?: message.targetTrees?.firstOrNull()
        if (channel?.name != null) {
            return context.getString(R.string.chat_message_to, message.actorName, channel.name)
        }
        val user = message.targetUsers?.firstOrNull()
        if (user?.name != null) {
            return context.getString(R.string.chat_message_to, message.actorName, user.name)
        }
        return message.actorName ?: context.getString(R.string.unknown)
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

    sealed class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val box: LinearLayout = view.findViewById(R.id.list_chat_item_box)
        val target: TextView = view.findViewById(R.id.list_chat_item_target)
        val time: TextView = view.findViewById(R.id.list_chat_item_time)
    }

    class TextHolder(view: View) : Holder(view) {
        val text: TextView = view.findViewById(R.id.list_chat_item_text)
    }

    class ImageHolder(view: View) : Holder(view) {
        val textBefore: TextView = view.findViewById(R.id.list_chat_item_text_before)
        val textAfter: TextView = view.findViewById(R.id.list_chat_item_text_after)
        val image: ImageView = view.findViewById(R.id.list_chat_item_image)
        val status: TextView = view.findViewById(R.id.list_chat_item_image_status)
        var job: Job? = null
    }

    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_INFO = 1
        const val TYPE_IMAGE = 2

        /**
         * Stream A's message log appends immutable instances and never edits one, so identity is
         * both the item id and the content check: re-submitting a list rebinds nothing and there
         * are no change payloads.
         */
        val DIFF: DiffUtil.ItemCallback<IChatMessage> = object : DiffUtil.ItemCallback<IChatMessage>() {
            override fun areItemsTheSame(oldItem: IChatMessage, newItem: IChatMessage) =
                oldItem === newItem

            override fun areContentsTheSame(oldItem: IChatMessage, newItem: IChatMessage) =
                oldItem === newItem
        }
    }
}
