package se.lublin.mumla.channel

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Channel
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.User
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.chat.ImageViewerDialogFragment
import se.lublin.mumla.service.IChatMessage
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * The fragment is glue, and glue is what carries the contracts its collaborators cannot enforce:
 * the viewer's uniqueness, a session id that may not throw, a scope on the main thread. So it is
 * driven here through a real host — an `Activity` that is a `HumlaServiceProvider` and a parent
 * `Fragment` that is a `ChatTargetProvider`, which are precisely the two hard casts that keep
 * `FragmentScenario`'s empty host out.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelChatFragmentTest {

    class HostActivity : FragmentActivity(), HumlaServiceProvider {
        /**
         * Backed by a field with explicit accessors rather than a `var`: a Kotlin `var service`
         * would generate `getService()` and collide with the interface's own method.
         */
        @JvmField
        var bound: IMumlaService? = null
        val serviceFragments = mutableListOf<HumlaServiceFragment>()

        override fun getService(): IMumlaService? = bound
        override fun addServiceFragment(fragment: HumlaServiceFragment) { serviceFragments += fragment }
        override fun removeServiceFragment(fragment: HumlaServiceFragment) { serviceFragments -= fragment }
    }

    class HostParentFragment : Fragment(), ChatTargetProvider {
        @JvmField
        var target: ChatTargetProvider.ChatTarget? = null
        val listeners = mutableListOf<ChatTargetProvider.OnChatTargetSelectedListener>()

        override fun onCreateView(i: android.view.LayoutInflater, c: ViewGroup?, s: android.os.Bundle?): View =
            FrameLayout(requireContext()).also { it.id = CONTAINER_ID }

        override fun getChatTarget(): ChatTargetProvider.ChatTarget? = target
        override fun setChatTarget(t: ChatTargetProvider.ChatTarget?) { target = t }
        override fun registerChatTargetListener(l: ChatTargetProvider.OnChatTargetSelectedListener) { listeners += l }
        override fun unregisterChatTargetListener(l: ChatTargetProvider.OnChatTargetSelectedListener) { listeners -= l }

        companion object {
            const val CONTAINER_ID = 0x0f0f0f
        }
    }

    private val service: IMumlaService = mockk(relaxed = true)
    private val session: IHumlaSession = mockk(relaxed = true)
    private val log = mutableListOf<IChatMessage>()

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var activity: HostActivity
    private lateinit var parent: HostParentFragment
    private lateinit var fragment: ChannelChatFragment

    @Before
    fun setUp() {
        every { service.isConnected } returns true
        every { service.HumlaSession() } returns session
        every { service.messageLog } returns log
        every { session.sessionId } returns 7
        every { session.sessionChannel } returns channel("Root")
        every { session.sessionUser } returns null
    }

    @After
    fun tearDown() {
        if (this::controller.isInitialized) controller.close()
    }

    // ---- harness ----------------------------------------------------------------------------

    /** Brings the host up with [withService] already bound, then attaches the fragment. */
    private fun launch(withService: IMumlaService? = service) {
        controller = Robolectric.buildActivity(HostActivity::class.java)
        activity = controller.create().get()
        activity.bound = withService
        parent = HostParentFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, parent, "parent").commitNow()
        fragment = ChannelChatFragment()
        parent.childFragmentManager.beginTransaction()
            .add(HostParentFragment.CONTAINER_ID, fragment, "chat").commitNow()
        controller.start().resume().visible()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Drains the main looper until [condition] holds. One `idle()` is not enough: the adapter's
     * parse hops to `Dispatchers.Default` and back, so the continuation is not on the queue yet
     * when the first drain runs. Fails loudly rather than returning quietly, so a state that never
     * arrives cannot read as a pass.
     */
    private fun drain(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(1)
        }
        idle()
        if (!condition()) throw AssertionError("condition still false after $timeoutMs ms")
    }

    private fun itemCount(): Int = list.adapter!!.itemCount

    private val list: RecyclerView get() = fragment.requireView().findViewById(R.id.chat_list)
    private val editor: EditText get() = fragment.requireView().findViewById(R.id.chatTextEdit)
    private val sendButton: ImageButton get() = fragment.requireView().findViewById(R.id.chatTextSend)
    private val observer: IHumlaObserver get() = fragment.serviceObserver

    private fun channel(name: String?, id: Int = 1): IChannel =
        Channel(id, false).also { it.name = name }

    private fun user(name: String, session: Int = 42): IUser =
        User(session, name)

    private fun info(body: String) =
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, body)

    // ---- the list ---------------------------------------------------------------------------

    @Test
    fun theBoundMessageLogIsWhatTheListShows() {
        log += info("older")
        log += info("newer")
        launch()
        drain { itemCount() == 2 }
        assertThat(itemCount()).isEqualTo(2)
    }

    @Test
    fun anArrivingLogLineIsAppended() {
        launch()
        observer.onLogInfo("hello")
        drain { itemCount() == 1 }
        assertThat(itemCount()).isEqualTo(1)
    }

    @Test
    fun clearEmptiesTheListAndTheServiceLog() {
        log += info("older")
        launch()
        fragment.clear()
        drain { itemCount() == 0 }
        assertThat(itemCount()).isEqualTo(0)
        verify { service.clearMessageLog() }
    }

    /**
     * The observer outlives the view — `HumlaServiceFragment` unregisters it in `onDestroy`, not in
     * `onDestroyView` — so a message can arrive with no view to put it in. `viewLifecycleOwner`
     * throws in exactly that window, which is why the adapter is checked before it is touched.
     */
    @Test
    fun aMessageArrivingAfterTheViewIsGoneIsNotACrash() {
        launch()
        val observerBeforeTeardown = observer
        activity.supportFragmentManager.beginTransaction().remove(parent).commitNow()
        idle()
        observerBeforeTeardown.onLogInfo("late")
    }

    // ---- the compose hint -------------------------------------------------------------------

    @Test
    fun theHintNamesTheSessionChannelWithNoTarget() {
        launch()
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Root"))
    }

    @Test
    fun theHintNamesTheTargetUser() {
        launch()
        fragment.onChatTargetSelected(ChatTargetProvider.ChatTarget(user("Ann")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToUser, "Ann"))
    }

    @Test
    fun theHintNamesTheTargetChannel() {
        launch()
        fragment.onChatTargetSelected(ChatTargetProvider.ChatTarget(channel("Lounge")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Lounge"))
    }

    /** The fourth arm: no target and no session channel either, so there is nothing to name. */
    @Test
    fun theHintIsClearedWhenThereIsNothingToName() {
        every { session.sessionChannel } returns null
        launch()
        assertThat(editor.hint).isNull()
    }

    /**
     * The service usually binds *after* the view exists. The old fragment set the hint only from
     * `onCreateView`, which returns early with no service, and nothing came back to it on the bind
     * — so on every cold start the compose box read "Send message" instead of naming the channel.
     */
    @Test
    fun theHintIsSetWhenTheServiceBindsAfterTheView() {
        launch(withService = null)
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.send_message))
        activity.bound = service
        fragment.setServiceBound(true)
        drain { editor.hint.toString() != activity.getString(R.string.send_message) }
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Root"))
    }

    /** The observer's only non-log arm: the local user moved, and no target overrides the hint. */
    @Test
    fun theHintFollowsTheLocalUserIntoANewChannel() {
        val self = user("Me", session = 7)
        every { session.sessionUser } returns self
        launch()
        every { session.sessionChannel } returns channel("Lounge")
        observer.onUserJoinedChannel(self, channel("Lounge"), channel("Root"))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Lounge"))
    }

    @Test
    fun theHintDoesNotFollowSomebodyElseIntoANewChannel() {
        every { session.sessionUser } returns user("Me", session = 7)
        launch()
        every { session.sessionChannel } returns channel("Lounge")
        observer.onUserJoinedChannel(user("Ann"), channel("Lounge"), channel("Root"))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Root"))
    }

    @Test
    fun theHintDoesNotFollowTheLocalUserWhileATargetIsSet() {
        val self = user("Me", session = 7)
        every { session.sessionUser } returns self
        launch()
        parent.target = ChatTargetProvider.ChatTarget(user("Ann"))
        fragment.onChatTargetSelected(parent.target)
        every { session.sessionChannel } returns channel("Lounge")
        observer.onUserJoinedChannel(self, channel("Lounge"), channel("Root"))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToUser, "Ann"))
    }

    // ---- the compose row --------------------------------------------------------------------

    /**
     * `android:enabled="false"` on an `ImageButton` is inert (see `ChatLayoutTest`), so the send
     * button shipped live over an empty editor; the text watcher only fires on a change and could
     * never correct the initial state.
     */
    @Test
    fun theSendButtonStartsDisabledAndFollowsTheEditor() {
        launch()
        assertThat(sendButton.isEnabled).isFalse()
        editor.setText("hi")
        assertThat(sendButton.isEnabled).isTrue()
        editor.setText("")
        assertThat(sendButton.isEnabled).isFalse()
    }

    @Test
    fun sendingTextMarksItUpAndGoesToTheSessionChannel() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        editor.setText("see http://x.example/ ok")
        sendButton.performClick()
        verify {
            session.sendChannelTextMessage(
                1,
                "see <a href=\"http://x.example/\">http://x.example/</a> ok",
                false,
            )
        }
        assertThat(editor.text.toString()).isEmpty()
    }

    @Test
    fun sendingWithAUserTargetGoesToThatUser() {
        every { session.sendUserTextMessage(any(), any()) } returns Message("out")
        launch()
        parent.target = ChatTargetProvider.ChatTarget(user("Ann", session = 42))
        editor.setText("hi")
        sendButton.performClick()
        verify { session.sendUserTextMessage(42, "hi") }
    }

    @Test
    fun sendingWithAChannelTargetGoesToThatChannel() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        parent.target = ChatTargetProvider.ChatTarget(channel("Lounge", id = 9))
        editor.setText("hi")
        sendButton.performClick()
        verify { session.sendChannelTextMessage(9, "hi", false) }
    }

    @Test
    fun anEmptyEditorSendsNothing() {
        launch()
        sendButton.performClick()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    /** A disconnect between typing and tapping must not take the app down with it. */
    @Test
    fun aDisconnectWhileSendingIsSwallowed() {
        launch()
        every { service.HumlaSession() } throws HumlaDisconnectedException()
        editor.setText("hi")
        sendButton.performClick()
        assertThat(editor.text.toString()).isEqualTo("hi")
    }

    // ---- the viewer -------------------------------------------------------------------------

    private fun openViewers(): Int =
        fragment.parentFragmentManager.fragments.count { it is ImageViewerDialogFragment }

    /**
     * The contract `ChatAdapter`'s `onImageClicked` KDoc states. `show(fm, tag)` is a plain `add`
     * and `FragmentManager` does not deduplicate by tag, so without the check two quick taps give
     * two viewers on one source — and two viewers on one source export to one share path at once.
     */
    @Test
    fun aSecondTapWhileTheViewerIsOpenDoesNotOpenASecond() {
        launch()
        fragment.openImageViewer("data:image/png;base64,AAAA")
        fragment.openImageViewer("data:image/png;base64,AAAA")
        idle()
        assertThat(openViewers()).isEqualTo(1)
    }

    /** ...and the gate is not a latch: once the viewer is gone, the next tap opens one again. */
    @Test
    fun theViewerOpensAgainAfterItIsDismissed() {
        launch()
        fragment.openImageViewer("data:image/png;base64,AAAA")
        idle()
        (fragment.parentFragmentManager.findFragmentByTag(ImageViewerDialogFragment.TAG)
            as ImageViewerDialogFragment).dismissNow()
        idle()
        assertThat(openViewers()).isEqualTo(0)
        fragment.openImageViewer("data:image/png;base64,AAAA")
        idle()
        assertThat(openViewers()).isEqualTo(1)
    }

    // ---- the session id ---------------------------------------------------------------------

    @Test
    fun theSessionIdIsTheLiveOneWhileConnected() {
        launch()
        assertThat(fragment.sessionId()).isEqualTo(7)
    }

    /**
     * The adapter asks for this on every bind, and a disconnect with the log still on screen is an
     * ordinary event. The `ListView` adapter this replaces wrapped the same call in a `try`.
     */
    @Test
    fun theSessionIdSurvivesADisconnect() {
        launch()
        every { service.HumlaSession() } throws HumlaDisconnectedException()
        assertThat(fragment.sessionId()).isNotEqualTo(7)
    }

    @Test
    fun theSessionIdIsAbsentWithNoServiceAndWhenNotConnected() {
        launch(withService = null)
        val noService = fragment.sessionId()
        activity.bound = service
        every { service.isConnected } returns false
        assertThat(fragment.sessionId()).isEqualTo(noService)
    }

    /**
     * The value that stands for "no session" must be one no actor can take, or every actorless
     * message renders right-aligned — as if the local user had sent it — from the moment the
     * connection drops. `Message(String)` sets its actor to exactly -1, which is why -1 is wrong.
     */
    @Test
    fun theAbsentSessionIdCannotCollideWithAMessageActor() {
        launch(withService = null)
        assertThat(fragment.sessionId()).isNotEqualTo(Message("server said so").actor)
    }

    // ---- the outgoing image path ------------------------------------------------------------

    private val progress: View get() = fragment.requireView().findViewById(R.id.chat_image_progress)

    private fun smallBitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    @Test
    fun aConfirmedImageIsSentAsADataUriAndTheSpinnerGoesAway() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        fragment.sendImage(smallBitmap())
        val sent = slot<String>()
        drain { runCatching { verify { session.sendChannelTextMessage(any(), capture(sent), any()) } }.isSuccess }
        assertThat(sent.captured).startsWith("<img src=\"data:image/jpeg;base64,")
        assertThat(progress.visibility).isEqualTo(View.GONE)
    }

    /**
     * The session can break between picking an image and confirming it — a dialog stands between
     * the two — so the service is fetched again at the send, not captured at the pick.
     */
    @Test
    fun anImageConfirmedAfterTheServiceWentAwaySendsNothing() {
        launch()
        activity.bound = null
        fragment.sendImage(smallBitmap())
        idle()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
        assertThat(progress.visibility).isEqualTo(View.GONE)
    }

    /** ...and the same one hop later: connected at the tap, gone by the time the encoder asks. */
    @Test
    fun aDisconnectWhileEncodingAnImageSendsNothing() {
        launch()
        every { service.HumlaSession() } throws HumlaDisconnectedException()
        fragment.sendImage(smallBitmap())
        drain { progress.visibility == View.GONE }
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    /** An image no quality rung can squeeze under the server's limit is reported, not sent. */
    @Test
    fun anImageThatCannotBeMadeToFitIsNotSent() {
        every { session.serverSettings } returns settings(10)
        launch()
        fragment.sendImage(smallBitmap())
        drain { progress.visibility == View.GONE }
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    private fun settings(imageMessageLength: Int): ServerSettings =
        mockk(relaxed = true) { every { getImageMessageLength() } returns imageMessageLength }
}
