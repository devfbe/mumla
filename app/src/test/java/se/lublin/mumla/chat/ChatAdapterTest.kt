package se.lublin.mumla.chat

import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBitmapFactory
import se.lublin.humla.model.Message
import se.lublin.mumla.R
import se.lublin.mumla.service.IChatMessage
import java.util.Collections

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
    ) = ChatAdapter(
        parser = parser,
        loader = ChatImageLoader(
            fetcher = ImageFetcher { source -> fetched += source; remoteBody },
            externalImagesAllowed = { true },
            maxCacheBytes = 8L * 1024 * 1024,
            ioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = Dispatchers.Unconfined,
        ),
        thumbnailPx = thumbnailPx,
        selfSessionId = { 42 },
        onImageClicked = { clicked += it },
        scope = scope,
        parseDispatcher = Dispatchers.Default,
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
     * A real touch, not [View.performClick]: `performClick` ignores `isEnabled` *and* visibility, so
     * a test written with it cannot see a guard that a finger would see (spec 4.05).
     */
    private fun tap(view: View) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 1f, 1f, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
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
    fun anImageRowShowsABoundedThumbnailAndReportsTaps() = runTest {
        val adapter = adapter()
        adapter.submitMessages(listOf(info("before <img src=\"$url\"/> after")))
        idle()

        val holder = adapter.holderAt(0, ChatAdapter.TYPE_IMAGE)

        val image = holder.itemView.findViewById<ImageView>(R.id.list_chat_item_image)
        assertThat((image.drawable as BitmapDrawable).bitmap.width).isAtMost(240)
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_before).text.toString())
            .isEqualTo("before")
        assertThat(holder.itemView.findViewById<TextView>(R.id.list_chat_item_text_after).text.toString())
            .isEqualTo("after")
        assertThat(fetched).containsExactly(url)

        tap(image)
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
}
