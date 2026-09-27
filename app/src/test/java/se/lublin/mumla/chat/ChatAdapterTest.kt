package se.lublin.mumla.chat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.Message
import se.lublin.humla.model.UserState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import java.util.Collections
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class ChatAdapterTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val parent = FrameLayout(activity).also { activity.setContentView(it) }
    private val fetched = mutableListOf<String>()
    private var thumbnail: ImageResult = ImageResult.Ready(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888))
    private val clicked = mutableListOf<String>()
    private val url = "https://x.org/a.png"

    private fun info(body: String) =
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, body)

    private fun adapter(
        parser: ChatContentParser = ChatContentParser("[image]"),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        thumbnailPx: Int = 240,
        parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
        selfSessionId: () -> Int = { 42 },
        decodeDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        diff: DiffUtil.ItemCallback<IChatMessage> = ChatAdapter.DIFF,
    ) = ChatAdapter(
        parser = parser,
        loader = fakeLoader(decodeDispatcher),
        thumbnailPx = thumbnailPx,
        selfSessionId = selfSessionId,
        onImageClicked = { clicked += it },
        scope = scope,
        parseDispatcher = parseDispatcher,
        differConfig = AsyncDifferConfig.Builder(diff)
            .setBackgroundThreadExecutor { it.run() }
            .build(),
    )


    /** Answers [thumbnail] on [decodeDispatcher], and [ImageResult.Skipped] for non-positive bounds. */
    private fun fakeLoader(decodeDispatcher: CoroutineDispatcher): ChatImageLoader = mockk {
        coEvery { loadThumbnail(any(), any(), any()) } coAnswers {
            if (secondArg<Int>() <= 0 || thirdArg<Int>() <= 0) {
                ImageResult.Skipped
            } else {
                withContext(decodeDispatcher) {
                    fetched += firstArg<String>()
                    thumbnail
                }
            }
        }
    }

    /**
     * Binds [position] into a fresh holder attached to a real window and laid out: an unattached
     * View queues its click in `HandlerActionQueue` instead of running it.
     */
    private fun ChatAdapter.holderAt(position: Int, viewType: Int): ChatAdapter.Holder {
        val holder = createViewHolder(parent, viewType)
        parent.addView(holder.itemView)
        bindViewHolder(holder, position)
        idleMainLooper()
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
        )
        parent.layout(0, 0, 1080, 1920)
        return holder
    }

    /**
     * A real touch entering at the row and routed down, so disabled and GONE targets are filtered
     * as for a finger ([View.performClick] and a direct dispatch to the target ignore visibility).
     */
    private fun tapRow(holder: ChatAdapter.Holder, targetId: Int) {
        val target = holder.itemView.findViewById<View>(targetId)
        var x = target.width / 2f
        var y = target.height / 2f
        var v: View = target
        while (v !== holder.itemView) {
            x += v.left
            y += v.top
            v = v.parent as View
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0)
        holder.itemView.dispatchTouchEvent(down)
        holder.itemView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        idleMainLooper()
    }

    @Test
    fun parsesEachMessageOffTheMainThreadAndOnlyOnce() = runTest {
        val parseThreads = Collections.synchronizedList(mutableListOf<Long>())
        val parser = spyk(ChatContentParser("[image]"))
        every { parser.parse(any()) } answers {
            parseThreads += Thread.currentThread().id
            callOriginal()
        }
        val adapter = adapter(parser)
        val messages = listOf(info("hello"), info("<img src=\"$url\"/>"))

        adapter.submitMessages(messages)
        idleMainLooper()
        adapter.submitMessages(messages)
        idleMainLooper()

        // Parsed once per message: the second submit reads IChatMessage.content.
        assertThat(parseThreads).hasSize(2)
        assertThat(parseThreads).doesNotContain(Looper.getMainLooper().thread.id)
        assertThat(messages[0].content).isInstanceOf(ChatContent.Text::class.java)
        assertThat(messages[1].content).isInstanceOf(ChatContent.Image::class.java)
        assertThat(adapter.itemCount).isEqualTo(2)
    }

    @Test
    fun appendingAMessageInsertsOneRowAndChangesNothingElse() = runTest {
        val adapter = adapter()
        val first = info("one")
        val second = info("two")
        adapter.submitMessages(listOf(first))
        idleMainLooper()

        val events = mutableListOf<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events += "reset" }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                events += "insert $positionStart+$itemCount"
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                events += "remove $positionStart+$itemCount"
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                events += "change $positionStart+$itemCount"
            }
        })

        adapter.submitMessages(listOf(first, second))
        idleMainLooper()

        assertThat(events).containsExactly("insert 1+1")
        assertThat(adapter.itemCount).isEqualTo(2)
    }

    @Test
    fun viewTypesFollowTheParsedContent() = runTest {
        val adapter = adapter()
        val text = IChatMessage.TextMessage(
            Message(7, "alice", emptyList(), emptyList(), emptyList(), "hi")
        )
        adapter.submitMessages(listOf(text, info("joined"), info("<img src=\"$url\"/>")))
        idleMainLooper()

        assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_TEXT)
        assertThat(adapter.getItemViewType(1)).isEqualTo(ChatAdapter.TYPE_INFO)
        assertThat(adapter.getItemViewType(2)).isEqualTo(ChatAdapter.TYPE_IMAGE)
    }

    @Test
    fun aPictureAUserSentIsAnImageRowAndNotItsRawMarkup() = runTest {
        // The real use case: MumlaService wraps every incoming chat message in a TextMessage, so a
        // sent picture arrives as a TextMessage whose body holds the <img>, not as an InfoMessage.
        val adapter = adapter()
        val sent = text(body = "look <img src=\"$url\"/>")
        adapter.submitMessages(listOf(sent))
        idleMainLooper()

        assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_IMAGE)
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        assertThat(holder).isInstanceOf(ChatAdapter.ImageHolder::class.java)
        val image = holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image)
        assertThat((image.drawable as BitmapDrawable).bitmap.width).isEqualTo(240)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_before).text.toString())
            .isEqualTo("look")
        // The target line is the TextMessage half of the row, and it still gets written.
        assertThat(holder.target.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.target.text.toString()).isEqualTo("alice")
    }

    @Test
    fun anImageRowShowsABoundedThumbnailAndReportsTaps() = runTest {
        val adapter = adapter()
        adapter.submitMessages(listOf(info("before <img src=\"$url\"/> after")))
        idleMainLooper()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)

        val image = holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image)
        val thumbnail = (image.drawable as BitmapDrawable).bitmap
        assertThat(thumbnail.width).isEqualTo(240)
        assertThat(thumbnail.height).isEqualTo(240)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_before).text.toString())
            .isEqualTo("before")
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_after).text.toString())
            .isEqualTo("after")
        assertThat(fetched).containsExactly(url)

        assertThat(image.width).isGreaterThan(0)
        tapRow(holder, R.id.list_chat_item_image)
        assertThat(clicked).containsExactly(url)
    }

    @Test
    fun aFailedThumbnailIsReplacedByTheFailureText() = runTest {
        thumbnail = ImageResult.Failed(ImageError.MALFORMED)
        val adapter = adapter()
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idleMainLooper()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)

        val status = holder.itemView.findViewById<TextView>(R.id.list_chat_item_image_status)
        assertThat(holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image).visibility)
            .isEqualTo(View.GONE)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo(activity.getString(R.string.chat_image_load_failed))
    }

    // Input sweep: every input ChatAdapter.kt branches on.

    private fun channel(name: String?) = ChannelState(0, name)

    private fun text(
        actor: Int = 7,
        actorName: String? = "alice",
        channels: List<ChannelState> = emptyList(),
        trees: List<ChannelState> = emptyList(),
        users: List<UserState> = emptyList(),
        body: String = "hi",
    ) = IChatMessage.TextMessage(Message(actor, actorName, channels, trees, users, body))

    private fun ChatAdapter.textHolderAt(position: Int) =
        holderAt(position, getItemViewType(position)) as ChatAdapter.TextHolder

    @Test
    fun aTextRowNamesTheTargetChannelAndCarriesTheBodyTimeAndLinkHandler() = runTest {
        val adapter = adapter()
        val message = text(channels = listOf(channel("Root")), body = "see <a href=\"https://x.org\">this</a>")
        adapter.submitMessages(listOf(message))
        idleMainLooper()

        val holder = adapter.textHolderAt(0)
        assertThat(holder.target.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.target.text.toString()).isEqualTo("alice → Root")
        assertThat(holder.text.text.toString()).isEqualTo("see this")
        assertThat(holder.text.movementMethod).isInstanceOf(LinkMovementMethod::class.java)
        assertThat(holder.time.text.toString())
            .isEqualTo(java.text.DateFormat.getTimeInstance().format(java.util.Date(message.receivedTime)))
    }

    @Test
    fun theTargetLabelFallsThroughChannelTreeUserAndActorWithTheServerForNoActor() = runTest {
        val adapter = adapter()
        val messages = listOf(
            text(channels = listOf(channel("Root"))),
            text(trees = listOf(channel("Sub"))),
            text(users = listOf(UserState(3, "bob", 0))),
            text(),
            text(actorName = null),
            // Present but nameless.
            text(channels = listOf(channel(null)), users = listOf(UserState(3, "bob", 0))),
            // A user that is there but has no name must not render "alice -> null".
            text(users = listOf(UserState(3, null, 0))),
            text(actorName = null, users = listOf(UserState(3, null, 0))),
        )
        adapter.submitMessages(messages)
        idleMainLooper()

        val labels = messages.indices.map { adapter.textHolderAt(it).target.text.toString() }
        assertThat(labels).containsExactly(
            "alice → Root",
            "alice → Sub",
            "alice → bob",
            "alice",
            activity.getString(R.string.server),
            "alice → bob",
            "alice",
            activity.getString(R.string.server),
        ).inOrder()
    }

    @Test
    fun aMessageNeverHandsOutANullTargetList() = runTest {
        val full =
            Message(7, "alice", listOf(channel("Root")), listOf(channel("Sub")), listOf(UserState(3, "bob", 0)), "hi")
        val empty = Message(-1, null, emptyList(), emptyList(), emptyList(), "just a body")
        for (message in listOf(full, empty)) {
            assertThat(message.targetChannels).isNotNull()
            assertThat(message.targetTrees).isNotNull()
            assertThat(message.targetUsers).isNotNull()
        }
    }

    @Test
    fun ownMessagesAreRightAlignedAndEveryoneElseIsLeftAligned() = runTest {
        val adapter = adapter(selfSessionId = { 7 })
        adapter.submitMessages(listOf(text(actor = 7), text(actor = 8), info("joined")))
        idleMainLooper()

        val mine = adapter.textHolderAt(0)
        val theirs = adapter.textHolderAt(1)
        val notice = adapter.textHolderAt(2)

        assertThat(mine.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.RIGHT)
        assertThat(mine.text.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.RIGHT)
        assertThat(theirs.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.LEFT)
        assertThat(theirs.text.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.LEFT)
        assertThat(notice.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.LEFT)
        assertThat(notice.target.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun theCaptionsAroundAnOwnPictureFollowTheBubbleTheySitIn() = runTest {
        // An own message aligns its caption to the END like its text line; one holder bound twice
        // pins the reset on reuse as well.
        val adapter = adapter(selfSessionId = { 7 })
        adapter.submitMessages(
            listOf(
                text(actor = 7, body = "mine <img src=\"$url\"/> here"),
                text(actor = 8, body = "theirs <img src=\"$url\"/> here"),
            )
        )
        idleMainLooper()
        val captions = intArrayOf(R.id.list_chat_item_text_before, R.id.list_chat_item_text_after)

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        assertThat(holder.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.RIGHT)
        for (id in captions) {
            assertThat(holder.itemView.findViewById<TextView>(id).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                .isEqualTo(Gravity.RIGHT)
        }

        adapter.bindViewHolder(holder, 1)
        idleMainLooper()
        assertThat(holder.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.LEFT)
        for (id in captions) {
            assertThat(holder.itemView.findViewById<TextView>(id).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                .isEqualTo(Gravity.LEFT)
        }
    }

    @Test
    fun aRecycledRowResetsBothWaysRoundBetweenAnOwnMessageAndANotice() = runTest {
        // One holder, both orders: only reuse makes the resets observable.
        val adapter = adapter(selfSessionId = { 7 })
        adapter.submitMessages(listOf(text(actor = 7, channels = listOf(channel("Root"))), info("joined")))
        idleMainLooper()
        val holder = adapter.createViewHolder(parent, ChatAdapter.TYPE_TEXT) as ChatAdapter.TextHolder

        adapter.bindViewHolder(holder, 0)
        assertThat(holder.target.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.RIGHT)

        adapter.bindViewHolder(holder, 1)
        assertThat(holder.target.visibility).isEqualTo(View.GONE)
        assertThat(holder.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.LEFT)

        adapter.bindViewHolder(holder, 0)
        assertThat(holder.target.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.target.text.toString()).isEqualTo("alice \u2192 Root")
        assertThat(holder.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.RIGHT)
    }

    @Test
    fun anUnparsedMessageStillRendersItsRawBodyInsteadOfCrashing() = runTest {
        // submitList is inherited and public: an unparsed message must render as text, not throw.
        val adapter = adapter()
        val message = info("not parsed")
        adapter.submitList(listOf(message))
        idleMainLooper()

        assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_INFO)
        assertThat(adapter.textHolderAt(0).text.text.toString()).isEqualTo("not parsed")
    }

    @Test
    fun textAroundAnImageIsHiddenWhenThereIsNoneAndCarriesTheLinkHandlerWhenThereIs() = runTest {
        val adapter = adapter()
        adapter.submitMessages(
            listOf(info("<img src=\"$url\"/>"), info("a <img src=\"$url\"/> b"))
        )
        idleMainLooper()

        val alone = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        assertThat(alone.itemView.findViewById<TextView>(R.id.list_chat_item_text_before).visibility)
            .isEqualTo(View.GONE)
        assertThat(alone.itemView.findViewById<TextView>(R.id.list_chat_item_text_after).visibility)
            .isEqualTo(View.GONE)

        val around = adapter.holderAt(1, ChatAdapter.TYPE_IMAGE)
        val before = around.itemView.findViewById<TextView>(R.id.list_chat_item_text_before)
        val after = around.itemView.findViewById<TextView>(R.id.list_chat_item_text_after)
        assertThat(before.visibility).isEqualTo(View.VISIBLE)
        assertThat(after.visibility).isEqualTo(View.VISIBLE)
        assertThat(before.movementMethod).isInstanceOf(LinkMovementMethod::class.java)
        assertThat(after.movementMethod).isInstanceOf(LinkMovementMethod::class.java)
    }

    @Test
    fun anEmptyTextAroundAnImageIsHiddenJustLikeAnAbsentOne() = runTest {
        // An empty but VISIBLE TextView would still cost its bottom margin under the picture.
        val adapter = adapter()
        val message = info("<img src=\"$url\"/>")
        message.content = ChatContent.Image(url, SpannableStringBuilder(""), SpannableStringBuilder(""))
        adapter.submitMessages(listOf(message))
        idleMainLooper()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_before).visibility)
            .isEqualTo(View.GONE)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_after).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun aTapOnARowWhoseImageFailedReportsNothing() = runTest {
        // A row whose picture failed is not tappable (it is hidden and measures 0x0).
        thumbnail = ImageResult.Failed(ImageError.MALFORMED)
        val adapter = adapter()
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idleMainLooper()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        tapRow(holder, R.id.list_chat_item_image)

        assertThat(clicked).isEmpty()
    }

    @Test
    fun nonPositiveBoundsLeaveThePlaceholderRatherThanShowingAFailure() = runTest {
        // ChatImageLoader answers Skipped for these; a Failed(UNSUPPORTED) would be cached forever.
        val adapter = adapter(thumbnailPx = 0)
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idleMainLooper()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        assertThat(holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image).drawable).isNull()
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_image_status).visibility)
            .isEqualTo(View.GONE)
        assertThat(fetched).isEmpty()
    }

    @Test
    fun rebindingAHolderResetsTheFailedRowItWasShowing() = runTest {
        val adapter = adapter()
        thumbnail = ImageResult.Failed(ImageError.MALFORMED)
        adapter.submitMessages(listOf(info("<img src=\"https://x.org/broken.png\"/>")))
        idleMainLooper()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        assertThat(holder.image.visibility).isEqualTo(View.GONE)
        assertThat(holder.status.visibility).isEqualTo(View.VISIBLE)

        // Same holder, a row whose image loads. Without the reset the good row stays invisible.
        thumbnail = ImageResult.Ready(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888))
        val adapter2 = adapter()
        adapter2.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idleMainLooper()
        adapter2.bindViewHolder(holder, 0)
        idleMainLooper()

        assertThat(holder.image.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.status.visibility).isEqualTo(View.GONE)
        assertThat(holder.image.drawable).isNotNull()
    }

    @Test
    fun rebindingAHolderDropsTheBitmapOfTheRowItWasShowing() = runTest {
        val adapter = adapter(thumbnailPx = 0)
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idleMainLooper()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        holder.image.setImageDrawable(ColorDrawable(0x112233))

        adapter.bindViewHolder(holder, 0)
        idleMainLooper()

        assertThat(holder.image.drawable).isNull()
    }

    @Test
    fun recyclingARowCancelsItsThumbnailAndClearsTheImage() = runTest {
        // The load is held at its first suspension point, so the row really is still fetching.
        val decode = QueueingDispatcher()
        val adapter = adapter(decodeDispatcher = decode)
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idleMainLooper()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        holder.image.setImageDrawable(ColorDrawable(0x112233))
        val job = holder.job
        assertThat(decode.pending()).isEqualTo(1)

        adapter.onViewRecycled(holder)
        decode.drain()
        idleMainLooper()

        assertThat(job!!.isCancelled).isTrue()
        assertThat(holder.job).isNull()
        // The effect that matters: the bitmap of the row that scrolled away never lands.
        assertThat(holder.image.drawable).isNull()
        assertThat(fetched).isEmpty()
    }

    @Test
    fun recyclingATextRowIsNotAnImageRowsBusiness() = runTest {
        val adapter = adapter()
        adapter.submitMessages(listOf(info("plain")))
        idleMainLooper()
        val holder = adapter.textHolderAt(0)

        adapter.onViewRecycled(holder)

        assertThat(holder.text.text.toString()).isEqualTo("plain")
    }

    @Test
    fun bindingAnImageRowCancelsTheThumbnailTheHolderWasStillFetching() = runTest {
        val decode = QueueingDispatcher()
        val adapter = adapter(decodeDispatcher = decode)
        adapter.submitMessages(
            listOf(info("<img src=\"https://x.org/slow.png\"/>"), info("<img src=\"$url\"/>"))
        )
        idleMainLooper()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        val first = holder.job

        // The row scrolls on before its thumbnail arrives and the holder is reused for row 1.
        adapter.bindViewHolder(holder, 1)
        decode.drain()
        idleMainLooper()

        assertThat(first!!.isCancelled).isTrue()
        assertThat(holder.job).isNotSameInstanceAs(first)
        // Only the row the holder now shows was fetched: the abandoned one cannot land here.
        assertThat(fetched).containsExactly(url)
    }

    @Test
    fun theDifferOnlyEverAsksAboutTheContentsOfOneAndTheSameInstance() = runTest {
        // areContentsTheSame can be a constant: DiffUtil only asks it about pairs areItemsTheSame
        // merged, and that merges by identity.
        val pairs = mutableListOf<Pair<IChatMessage, IChatMessage>>()
        val recording = object : DiffUtil.ItemCallback<IChatMessage>() {
            override fun areItemsTheSame(oldItem: IChatMessage, newItem: IChatMessage) =
                ChatAdapter.DIFF.areItemsTheSame(oldItem, newItem)

            override fun areContentsTheSame(oldItem: IChatMessage, newItem: IChatMessage): Boolean {
                pairs += oldItem to newItem
                return ChatAdapter.DIFF.areContentsTheSame(oldItem, newItem)
            }
        }
        val adapter = adapter(diff = recording)
        val first = info("one")
        val second = info("two")
        adapter.submitMessages(listOf(first))
        idleMainLooper()
        adapter.submitMessages(listOf(first, second))
        idleMainLooper()
        // A fresh list holding the same instances: AsyncListDiffer short-circuits on the identical
        // List object.
        adapter.submitMessages(listOf(first, second))
        idleMainLooper()

        assertThat(pairs).isNotEmpty()
        for ((oldItem, newItem) in pairs) {
            if (oldItem !== newItem) fail("asked about two different instances: $oldItem / $newItem")
        }

        // Two messages that read the same are still two rows.
        val twin = adapter(diff = ChatAdapter.DIFF)
        twin.submitMessages(listOf(info("same"), info("same")))
        idleMainLooper()
        assertThat(twin.itemCount).isEqualTo(2)
    }

    @Test
    fun replacingTheWholeLogRemovesAndInsertsRatherThanRebindingInPlace() = runTest {
        // With areItemsTheSame always true, DiffUtil would pair row i with row i; the log only
        // appends today, so this pins what happens the day it does not.
        val adapter = adapter()
        adapter.submitMessages(listOf(info("one"), info("two")))
        idleMainLooper()

        val events = mutableListOf<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events += "reset" }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                events += "insert $positionStart+$itemCount"
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                events += "remove $positionStart+$itemCount"
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                events += "change $positionStart+$itemCount"
            }
        })

        adapter.submitMessages(listOf(info("three"), info("four")))
        idleMainLooper()

        assertThat(events).doesNotContain("change 0+2")
        assertThat(events).containsExactly("remove 0+2", "insert 0+2").inOrder()
        assertThat(adapter.currentList.map { it.body }).containsExactly("three", "four").inOrder()
    }

    @Test
    fun theListIsSnapshotted() = runTest {
        val adapter = adapter()
        val log = mutableListOf<IChatMessage>(info("one"))
        adapter.submitMessages(log)
        idleMainLooper()

        log += info("two")
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(1)
    }

    /** Runs nothing until told to, and then in the order asked for. */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }
        fun drainNewestFirst() {
            while (queued.isNotEmpty()) queued.removeLast().run()
        }
        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
        fun pending(): Int = queued.size
    }

    @Test
    fun aSlowParseCannotOverwriteANewerListAndLoseTheNewestMessage() = runTest {
        val parse = QueueingDispatcher()
        val adapter = adapter(parseDispatcher = parse)
        val one = info("one")
        val two = info("two")
        val outside = CoroutineScope(Dispatchers.Unconfined)

        // Two messages arrive back to back; both parses are in flight.
        outside.launch { adapter.submitMessages(listOf(one)) }
        outside.launch { adapter.submitMessages(listOf(one, two)) }
        // The newer one finishes parsing first, so the older submit lands last.
        parse.drainNewestFirst()
        idleMainLooper()

        assertThat(adapter.itemCount).isEqualTo(2)
        assertThat(adapter.currentList.map { it.body }).containsExactly("one", "two").inOrder()
    }

    // The layouts: every attribute the adapter relies on, read back off the view.

    private fun inflate(layout: Int): View =
        activity.layoutInflater.inflate(layout, parent, false)

    @Test
    fun bothRowLayoutsWrapTheirContentAndKeepTheOldDividerAsAMargin() = runTest {
        // match_parent would make every RecyclerView row one viewport tall.
        val spacing = activity.resources.getDimensionPixelSize(R.dimen.chat_item_spacing)
        for (layout in intArrayOf(R.layout.list_chat_item, R.layout.list_chat_item_image)) {
            val params = inflate(layout).layoutParams as ViewGroup.MarginLayoutParams
            assertThat(params.height).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
            assertThat(params.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            assertThat(params.bottomMargin).isEqualTo(spacing)
        }
    }

    @Test
    fun theThumbnailIsBoundedFocusableAndAnnouncedAsSomethingToOpen() = runTest {
        val row = inflate(R.layout.list_chat_item_image)
        val image = row.findViewById<ImageView>(R.id.list_chat_item_image)
        val max = activity.resources.getDimensionPixelSize(R.dimen.chat_thumbnail_max)

        assertThat(image.maxWidth).isEqualTo(max)
        assertThat(image.maxHeight).isEqualTo(max)
        // Without adjustViewBounds the maxima above do nothing for a wrap_content ImageView.
        assertThat(image.adjustViewBounds).isTrue()
        // Explicitly FOCUSABLE, not FOCUSABLE_AUTO (which resolves from clickable), so the thumbnail
        // stays reachable by keyboard and switch access even without android:clickable.
        assertThat(image.focusable).isEqualTo(View.FOCUSABLE)
        assertThat(image.isFocusable).isTrue()
        assertThat(image.isClickable).isTrue()
        assertThat(image.contentDescription.toString())
            .isEqualTo(activity.getString(R.string.chat_image_open))
        assertThat(image.background).isNotNull()
    }

    @Test
    fun theImageRowStacksItsPartsAndKeepsTheTextSelectable() = runTest {
        val row = inflate(R.layout.list_chat_item_image)
        val box = row.findViewById<LinearLayout>(R.id.list_chat_item_box)
        // The LinearLayout default is HORIZONTAL.
        assertThat(box.orientation).isEqualTo(LinearLayout.VERTICAL)
        // The plain text row lets a message be selected and copied; so must the picture row.
        for (id in intArrayOf(R.id.list_chat_item_text_before, R.id.list_chat_item_text_after)) {
            assertThat(row.findViewById<TextView>(id).isTextSelectable).isTrue()
        }
        // A VISIBLE but empty caption would still cost this much space under the picture.
        for (id in intArrayOf(
            R.id.list_chat_item_text_before,
            R.id.list_chat_item_text_after,
            R.id.list_chat_item_image,
        )) {
            val params = row.findViewById<View>(id).layoutParams as ViewGroup.MarginLayoutParams
            assertThat(params.bottomMargin).isGreaterThan(0)
        }
    }

    @Test
    fun theImageRowStartsWithEverythingOptionalHidden() = runTest {
        val row = inflate(R.layout.list_chat_item_image)
        for (id in intArrayOf(
            R.id.list_chat_item_text_before,
            R.id.list_chat_item_text_after,
            R.id.list_chat_item_image_status,
        )) {
            assertThat(row.findViewById<View>(id).visibility).isEqualTo(View.GONE)
        }
        assertThat(row.findViewById<View>(R.id.list_chat_item_box)).isNotNull()
        assertThat(row.findViewById<View>(R.id.list_chat_item_target)).isNotNull()
        assertThat(row.findViewById<View>(R.id.list_chat_item_time)).isNotNull()
    }

    @Test
    fun theTwoRowLayoutsAreTheOnesTheViewTypesAskFor() = runTest {
        val adapter = adapter()
        assertThat(adapter.createViewHolder(parent, ChatAdapter.TYPE_TEXT))
            .isInstanceOf(ChatAdapter.TextHolder::class.java)
        assertThat(adapter.createViewHolder(parent, ChatAdapter.TYPE_INFO))
            .isInstanceOf(ChatAdapter.TextHolder::class.java)
        assertThat(adapter.createViewHolder(parent, ChatAdapter.TYPE_IMAGE))
            .isInstanceOf(ChatAdapter.ImageHolder::class.java)
    }

    // The log is unbounded. These compare index by index: Truth would render both lists into the
    // failure message, which on a list this size times out.

    @Test
    fun aLongSessionParsesEachMessageExactlyOnceNoMatterHowOftenTheLogIsResubmitted() = runTest {
        val parsed = java.util.concurrent.atomic.AtomicInteger()
        val parser = spyk(ChatContentParser("[image]"))
        every { parser.parse(any()) } answers { parsed.incrementAndGet(); callOriginal() }
        val adapter = adapter(parser)

        val log = mutableListOf<IChatMessage>()
        repeat(400) {
            log += info("message $it")
            adapter.submitMessages(log)
        }
        idleMainLooper()

        assertThat(parsed.get()).isEqualTo(400)
        assertThat(adapter.itemCount).isEqualTo(400)
        val shown = adapter.currentList
        for (i in 0 until 400) {
            if (shown[i] !== log[i]) fail("row $i is ${shown[i].body}, expected ${log[i].body}")
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
