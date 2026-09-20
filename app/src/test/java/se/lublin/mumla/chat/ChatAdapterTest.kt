package se.lublin.mumla.chat

import android.app.Activity
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
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBitmapFactory
import se.lublin.humla.model.Channel
import se.lublin.humla.model.Message
import se.lublin.humla.model.User
import se.lublin.mumla.R
import se.lublin.mumla.service.IChatMessage
import java.util.Collections
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class ChatAdapterTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val parent = FrameLayout(activity).also { activity.setContentView(it) }
    private val fetched = mutableListOf<String>()
    private var remoteBody: ByteArray = TestImages.png(300, 300)
    private val clicked = mutableListOf<String>()
    private val url = "https://x.org/a.png"

    @Before
    fun rejectInvalidImageData() {
        // Without this Robolectric invents a 100x100 bitmap for undecodable bytes and the
        // failure test would be green without testing anything (spec 4.05).
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }

    private fun info(body: String) =
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, body)

    private fun adapter(
        parser: ChatContentParser = ChatContentParser("[image]"),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        thumbnailPx: Int = 240,
        parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
        selfSessionId: () -> Int = { 42 },
        decodeDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = ChatAdapter(
        parser = parser,
        loader = ChatImageLoader(
            fetcher = ImageFetcher { source -> fetched += source; remoteBody },
            externalImagesAllowed = { true },
            maxCacheBytes = 8L * 1024 * 1024,
            ioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = decodeDispatcher,
        ),
        thumbnailPx = thumbnailPx,
        selfSessionId = selfSessionId,
        onImageClicked = { clicked += it },
        scope = scope,
        parseDispatcher = parseDispatcher,
        differConfig = AsyncDifferConfig.Builder(ChatAdapter.DIFF)
            .setBackgroundThreadExecutor { it.run() }
            .build(),
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Binds [position] into a freshly created holder that is **attached to a real window** and laid
     * out. Attachment is not decoration: an unattached View queues its click in
     * `HandlerActionQueue` instead of running it, so a tap on a detached row never reports.
     */
    private fun ChatAdapter.holderAt(position: Int, viewType: Int): ChatAdapter.Holder {
        val holder = createViewHolder(parent, viewType)
        parent.addView(holder.itemView)
        bindViewHolder(holder, position)
        idle()
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
        )
        parent.layout(0, 0, 1080, 1920)
        return holder
    }

    /**
     * A real touch **through the row**, not [View.performClick] and not a direct dispatch to the
     * target either. `performClick` ignores `isEnabled` and visibility; a direct
     * `view.dispatchTouchEvent` ignores visibility too, because it is the *parent* that filters
     * GONE children out of hit-testing (`ViewGroup.canViewReceivePointerEvents`). Only a touch
     * that enters at the row and is routed down sees what a finger sees — which is why
     * [holderAt] attaches the row to a real window and lays it out.
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
        idle()
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
        idle()
        adapter.submitMessages(messages)
        idle()

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
        idle()

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
        idle()

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
        idle()

        assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_TEXT)
        assertThat(adapter.getItemViewType(1)).isEqualTo(ChatAdapter.TYPE_INFO)
        assertThat(adapter.getItemViewType(2)).isEqualTo(ChatAdapter.TYPE_IMAGE)
    }

    @Test
    fun aPictureAUserSentIsAnImageRowAndNotItsRawMarkup() = runTest {
        // getItemViewType branches on two booleans, and this is the corner the rest of the suite
        // never builds: every other image fixture here is an InfoMessage, while the feature's
        // actual use case is a TextMessage -- MumlaService wraps *every* incoming chat message in
        // one (MumlaService.java:246), so a picture a user sends arrives as a TextMessage whose
        // body holds the <img>. Under `content is Image && message is InfoMessage` this row falls
        // through to the TextHolder, which finds no ChatContent.Text to unwrap and prints the raw
        // body -- the user sees `look <img src="..."/>` where the picture should be.
        val adapter = adapter()
        val sent = text(body = "look <img src=\"$url\"/>")
        adapter.submitMessages(listOf(sent))
        idle()

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
        idle()

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
        remoteBody = "not an image".toByteArray()
        val adapter = adapter()
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idle()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)

        val status = holder.itemView.findViewById<TextView>(R.id.list_chat_item_image_status)
        assertThat(holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image).visibility)
            .isEqualTo(View.GONE)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo(activity.getString(R.string.chat_image_load_failed))
    }

    // ------------------------------------------------------------------------------------------
    // Input sweep: every input ChatAdapter.kt branches on, enumerated from the production file.
    // ------------------------------------------------------------------------------------------

    private fun channel(name: String?) = Channel().also { it.name = name }

    private fun text(
        actor: Int = 7,
        actorName: String? = "alice",
        channels: List<Channel> = emptyList(),
        trees: List<Channel> = emptyList(),
        users: List<User> = emptyList(),
        body: String = "hi",
    ) = IChatMessage.TextMessage(Message(actor, actorName, channels, trees, users, body))

    private fun ChatAdapter.textHolderAt(position: Int) =
        holderAt(position, getItemViewType(position)) as ChatAdapter.TextHolder

    @Test
    fun aTextRowNamesTheTargetChannelAndCarriesTheBodyTimeAndLinkHandler() = runTest {
        val adapter = adapter()
        val message = text(channels = listOf(channel("Root")), body = "see <a href=\"https://x.org\">this</a>")
        adapter.submitMessages(listOf(message))
        idle()

        val holder = adapter.textHolderAt(0)
        assertThat(holder.target.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.target.text.toString()).isEqualTo("alice → Root")
        assertThat(holder.text.text.toString()).isEqualTo("see this")
        assertThat(holder.text.movementMethod).isInstanceOf(LinkMovementMethod::class.java)
        assertThat(holder.time.text.toString())
            .isEqualTo(java.text.DateFormat.getTimeInstance().format(java.util.Date(message.receivedTime)))
    }

    @Test
    fun theTargetLabelFallsThroughChannelTreeUserActorAndFinallyUnknown() = runTest {
        val adapter = adapter()
        val messages = listOf(
            text(channels = listOf(channel("Root"))),
            text(trees = listOf(channel("Sub"))),
            text(users = listOf(User(3, "bob"))),
            text(),
            text(actorName = null),
            // Present but nameless: the old ListView adapter printed "Unknown" here and stopped.
            text(channels = listOf(channel(null)), users = listOf(User(3, "bob"))),
        )
        adapter.submitMessages(messages)
        idle()

        val labels = messages.indices.map { adapter.textHolderAt(it).target.text.toString() }
        assertThat(labels).containsExactly(
            "alice → Root",
            "alice → Sub",
            "alice → bob",
            "alice",
            activity.getString(R.string.unknown),
            "alice → bob",
        ).inOrder()
    }

    @Test
    fun ownMessagesAreRightAlignedAndEveryoneElseIsLeftAligned() = runTest {
        val adapter = adapter(selfSessionId = { 7 })
        adapter.submitMessages(listOf(text(actor = 7), text(actor = 8), info("joined")))
        idle()

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
    fun aRecycledRowResetsBothWaysRoundBetweenAnOwnMessageAndANotice() = runTest {
        // One holder, both orders. Each direction pins a different line: a fresh row is already
        // left-aligned with a visible name, so only the *reuse* makes the resets observable, and
        // only doing it both ways round makes both resets observable.
        val adapter = adapter(selfSessionId = { 7 })
        adapter.submitMessages(listOf(text(actor = 7, channels = listOf(channel("Root"))), info("joined")))
        idle()
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
        // submitList is not the supported entry point, but it is inherited and public. A message
        // that reaches the list unparsed must render as text, not throw.
        val adapter = adapter()
        val message = info("not parsed")
        adapter.submitList(listOf(message))
        idle()

        assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_INFO)
        assertThat(adapter.textHolderAt(0).text.text.toString()).isEqualTo("not parsed")
    }

    @Test
    fun textAroundAnImageIsHiddenWhenThereIsNoneAndCarriesTheLinkHandlerWhenThereIs() = runTest {
        val adapter = adapter()
        adapter.submitMessages(
            listOf(info("<img src=\"$url\"/>"), info("a <img src=\"$url\"/> b"))
        )
        idle()

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
        // ChatContentParser returns null rather than an empty Spanned, so this only reaches
        // bindImage through the public ChatContent.Image/IChatMessage.content pair. An empty but
        // VISIBLE TextView would still cost its 4dp bottom margin under the picture.
        val adapter = adapter()
        val message = info("<img src=\"$url\"/>")
        message.content = ChatContent.Image(url, SpannableStringBuilder(""), SpannableStringBuilder(""))
        adapter.submitMessages(listOf(message))
        idle()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_before).visibility)
            .isEqualTo(View.GONE)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_after).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun aTapOnARowWhoseImageFailedReportsNothing() = runTest {
        // Both performClick() and a dispatchTouchEvent aimed straight at the ImageView would
        // report a tap here: neither consults the view's own visibility. Only the routed touch in
        // tapRow does, because the parent refuses to hand a GONE child any pointer.
        remoteBody = "not an image".toByteArray()
        val adapter = adapter()
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idle()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)
        tapRow(holder, R.id.list_chat_item_image)

        assertThat(clicked).isEmpty()
    }

    @Test
    fun nonPositiveBoundsLeaveThePlaceholderRatherThanShowingAFailure() = runTest {
        // ChatImageLoader answers Skipped for these; a Failed(UNSUPPORTED) would be cached forever.
        val adapter = adapter(thumbnailPx = 0)
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idle()

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
        remoteBody = "not an image".toByteArray()
        adapter.submitMessages(listOf(info("<img src=\"https://x.org/broken.png\"/>")))
        idle()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        assertThat(holder.image.visibility).isEqualTo(View.GONE)
        assertThat(holder.status.visibility).isEqualTo(View.VISIBLE)

        // Same holder, a row whose image loads. Without the reset the good row stays invisible.
        remoteBody = TestImages.png(300, 300)
        val adapter2 = adapter()
        adapter2.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idle()
        adapter2.bindViewHolder(holder, 0)
        idle()

        assertThat(holder.image.visibility).isEqualTo(View.VISIBLE)
        assertThat(holder.status.visibility).isEqualTo(View.GONE)
        assertThat(holder.image.drawable).isNotNull()
    }

    @Test
    fun rebindingAHolderDropsTheBitmapOfTheRowItWasShowing() = runTest {
        val adapter = adapter(thumbnailPx = 0)
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idle()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        holder.image.setImageDrawable(ColorDrawable(0x112233))

        adapter.bindViewHolder(holder, 0)
        idle()

        assertThat(holder.image.drawable).isNull()
    }

    @Test
    fun recyclingARowCancelsItsThumbnailAndClearsTheImage() = runTest {
        // The load is held at its first suspension point, so the row really is still fetching.
        val decode = QueueingDispatcher()
        val adapter = adapter(decodeDispatcher = decode)
        adapter.submitMessages(listOf(info("<img src=\"$url\"/>")))
        idle()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        holder.image.setImageDrawable(ColorDrawable(0x112233))
        val job = holder.job
        assertThat(decode.pending()).isEqualTo(1)

        adapter.onViewRecycled(holder)
        decode.drain()
        idle()

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
        idle()
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
        idle()
        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
        val first = holder.job

        // The row scrolls on before its thumbnail arrives and the holder is reused for row 1.
        adapter.bindViewHolder(holder, 1)
        decode.drain()
        idle()

        assertThat(first!!.isCancelled).isTrue()
        assertThat(holder.job).isNotSameInstanceAs(first)
        // Only the row the holder now shows was fetched: the abandoned one cannot land here.
        assertThat(fetched).containsExactly(url)
    }

    // ------------------------------------------------------------------------------------------
    // The snapshot and the submit ordering.
    // ------------------------------------------------------------------------------------------

    @Test
    fun replacingTheWholeLogRemovesAndInsertsRatherThanRebindingInPlace() = runTest {
        // With areItemsTheSame always true, DiffUtil would pair row i with row i and report
        // changes. The log only ever appends today, so this is the dimension that says what
        // happens the day it does not.
        val adapter = adapter()
        adapter.submitMessages(listOf(info("one"), info("two")))
        idle()

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
        idle()

        assertThat(events).doesNotContain("change 0+2")
        assertThat(events).containsExactly("remove 0+2", "insert 0+2").inOrder()
        assertThat(adapter.currentList.map { it.body }).containsExactly("three", "four").inOrder()
    }

    @Test
    fun theListIsSnapshotted() = runTest {
        val adapter = adapter()
        val log = mutableListOf<IChatMessage>(info("one"))
        adapter.submitMessages(log)
        idle()

        log += info("two")
        idle()

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
        idle()

        assertThat(adapter.itemCount).isEqualTo(2)
        assertThat(adapter.currentList.map { it.body }).containsExactly("one", "two").inOrder()
    }

    // ------------------------------------------------------------------------------------------
    // Effect sweep: the layouts. Every attribute the adapter relies on, read back off the view.
    // ------------------------------------------------------------------------------------------

    private fun inflate(layout: Int): View =
        activity.layoutInflater.inflate(layout, parent, false)

    @Test
    fun bothRowLayoutsWrapTheirContentAndKeepTheOldDividerAsAMargin() = runTest {
        // match_parent here is what the ListView tolerated and a RecyclerView does not: every row
        // would be one viewport tall. The margin replaces ListView's dividerHeight.
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
        // Explicitly FOCUSABLE, not FOCUSABLE_AUTO. Measured: with the attribute removed,
        // isFocusable is still true at minSdk 31 because FOCUSABLE_AUTO resolves itself from
        // clickable — so isFocusable alone cannot tell the two apart and the attribute would read
        // as unpinned. What the explicit value buys is that the thumbnail stays reachable by
        // keyboard and switch access even if someone later drops android:clickable.
        assertThat(image.focusable).isEqualTo(View.FOCUSABLE)
        assertThat(image.isFocusable).isTrue()
        assertThat(image.isClickable).isTrue()
        assertThat(image.contentDescription.toString())
            .isEqualTo(activity.getString(R.string.chat_image_open))
        assertThat(image.background).isNotNull()
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

    // ------------------------------------------------------------------------------------------
    // The log is unbounded (stream A). Both of these compare index by index and report the first
    // divergence: Truth renders both lists into the failure message, which on a list this size is
    // the "TIMED OUT without output" failure mode of spec 4.05.
    // ------------------------------------------------------------------------------------------

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
        idle()

        assertThat(parsed.get()).isEqualTo(400)
        assertThat(adapter.itemCount).isEqualTo(400)
        val shown = adapter.currentList
        for (i in 0 until 400) {
            if (shown[i] !== log[i]) fail("row $i is ${shown[i].body}, expected ${log[i].body}")
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
