package se.lublin.mumla.channel

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MenuItem
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
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
import se.lublin.mumla.chat.ChatAdapter
import se.lublin.mumla.chat.ChatImageLoader
import se.lublin.mumla.chat.ChatImageLoaders
import se.lublin.mumla.chat.ImageResult
import se.lublin.mumla.chat.ImageViewerDialogFragment
import se.lublin.mumla.chat.OutgoingImagePreparer
import se.lublin.mumla.chat.TestImages
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

    // ---- corners the first fixture set could not express ------------------------------------

    /**
     * `isConnected()` and the throw inside `HumlaSession()` are **the same condition** in
     * production — both read `mConnectionState == CONNECTED` — and a mock that lets them disagree
     * closes off every branch behind the pair. This is the coupled state; the race below is the
     * uncoupled one, and both are real.
     */
    private fun disconnect() {
        every { service.isConnected } returns false
        every { service.HumlaSession() } throws HumlaDisconnectedException()
    }

    @Test
    fun theHintIsLeftAloneWhileDisconnected() {
        launch()
        val before = editor.hint.toString()
        disconnect()
        fragment.onChatTargetSelected(ChatTargetProvider.ChatTarget(user("Ann")))
        assertThat(editor.hint.toString()).isEqualTo(before)
    }

    @Test
    fun aChannelJoinArrivingAfterTheDisconnectIsIgnored() {
        every { session.sessionUser } returns user("Me", session = 7)
        launch()
        val self = session.sessionUser
        disconnect()
        observer.onUserJoinedChannel(self, channel("Lounge"), channel("Root"))
    }

    /**
     * `updateChatTargetText` is public API, so it can be called before the view exists — and the
     * lateinit field behind it would then throw. Nothing in the fragment reaches it that early
     * today, which is exactly why this is written down rather than left to a call site.
     */
    @Test
    fun theHintIsSafeToUpdateBeforeThereIsAView() {
        ChannelChatFragment().updateChatTargetText(null)
    }

    /** The listener is the only way a target chosen elsewhere reaches the hint. */
    @Test
    fun theTargetListenerIsHeldOnlyWhileResumed() {
        launch()
        assertThat(parent.listeners).contains(fragment)
        controller.pause()
        assertThat(parent.listeners).doesNotContain(fragment)
        controller.resume()
        assertThat(parent.listeners).contains(fragment)
    }

    @Test
    fun theClearMenuItemClearsTheLog() {
        log += info("older")
        launch()
        val item: MenuItem = mockk(relaxed = true) { every { itemId } returns R.id.menu_clear_chat }
        assertThat(fragment.onOptionsItemSelected(item)).isTrue()
        drain { itemCount() == 0 }
        verify { service.clearMessageLog() }
    }

    // ---- the storage permission, which only one SDK level asks for --------------------------

    private fun tapUpload() {
        val button = fragment.requireView().findViewById<ImageButton>(R.id.chatImageSend)
        // performClick ignores both isEnabled and visibility, so the state is asserted rather than
        // assumed: an inert button would otherwise report this branch as working.
        assertThat(button.isEnabled).isTrue()
        assertThat(button.visibility).isEqualTo(View.VISIBLE)
        button.performClick()
        idle()
    }

    private fun startedAction(): String? =
        shadowOf(activity).nextStartedActivityForResult?.intent?.action

    @Test
    @Config(sdk = [31])
    fun onAndroid12TheUploadButtonAsksForStoragePermissionFirst() {
        launch()
        tapUpload()
        assertThat(startedAction()).isEqualTo("android.content.pm.action.REQUEST_PERMISSIONS")
    }

    @Test
    @Config(sdk = [33])
    fun fromAndroid13OnTheUploadButtonOpensThePickerDirectly() {
        launch()
        tapUpload()
        assertThat(startedAction()).isEqualTo(Intent.ACTION_GET_CONTENT)
    }

    @Test
    @Config(sdk = [31])
    fun onAndroid12AGrantedStoragePermissionGoesStraightToThePicker() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        launch()
        tapUpload()
        assertThat(startedAction()).isEqualTo(Intent.ACTION_GET_CONTENT)
    }

    // ---- the confirmation dialog, which nothing else reads back -----------------------------

    private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun theConfirmationShowsThePickedImageAndSendsOnlyOnOk() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        val bitmap = smallBitmap()
        fragment.confirmImage(bitmap)
        idle()
        val dialog = latestDialog()
        assertThat(dialog.isShowing).isTrue()
        val preview = dialog.window!!.decorView.firstImageView()
        assertThat(preview).isNotNull()
        // The one property the four below never checked: that the dialog shows the picked image at
        // all. Removing `setImageBitmap` left this test green, which is a cover that did not exist.
        assertThat((preview!!.drawable as BitmapDrawable).bitmap).isSameInstanceAs(bitmap)
        assertThat(preview.contentDescription.toString())
            .isEqualTo(activity.getString(R.string.image_confirm_send))
        assertThat(preview.adjustViewBounds).isTrue()
        assertThat(preview.scaleType).isEqualTo(ImageView.ScaleType.FIT_CENTER)
        assertThat(preview.maxHeight).isEqualTo(activity.resources.displayMetrics.heightPixels / 3)

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    @Test
    fun theConfirmationSendsOnOk() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        fragment.confirmImage(smallBitmap())
        idle()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        drain { runCatching { verify { session.sendChannelTextMessage(any(), any(), any()) } }.isSuccess }
    }

    private fun View.firstImageView(): ImageView? {
        if (this is ImageView && contentDescription != null) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).firstImageView()?.let { return it }
        }
        return null
    }

    /**
     * The hint is what the compose box is as wide as, so a changed hint has to reach layout. The
     * explicit `requestLayout()` the old fragment carried a comment for is measured here rather
     * than explained: see the fragment for what the measurement said.
     */
    @Test
    fun changingTheHintAsksForAFreshLayout() {
        launch()
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 1920)
        assertThat(editor.isLayoutRequested).isFalse()
        fragment.onChatTargetSelected(ChatTargetProvider.ChatTarget(user("Ann")))
        assertThat(editor.isLayoutRequested).isTrue()
    }

    /**
     * The server does not echo a message back to its sender, so this call is the only thing that
     * puts what you just typed into your own log. Found by mutation: dropping it left all 32 tests
     * green while the sent message vanished from the screen.
     */
    @Test
    fun aSentMessageAppearsInTheListImmediately() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("hi there")
        launch()
        editor.setText("hi there")
        sendButton.performClick()
        drain { itemCount() == 1 }
        assertThat(itemCount()).isEqualTo(1)
    }

    // ---- corners the mutation sweep pointed at ----------------------------------------------

    /**
     * Rebinding replaces the log; it does not append it. Reached on every reconnect, and without
     * the clear the whole history is on screen twice.
     */
    @Test
    fun rebindingReplacesTheLogRatherThanAppendingIt() {
        log += info("a")
        log += info("b")
        launch()
        observer.onLogInfo("live")
        drain { itemCount() == 3 }
        fragment.setServiceBound(false)
        fragment.setServiceBound(true)
        drain { itemCount() == 2 }
        assertThat(itemCount()).isEqualTo(2)
    }

    /** The list is read newest-last, so it has to sit at the bottom rather than the top. */
    @Test
    fun theListSticksToTheNewestMessage() {
        launch()
        assertThat((list.layoutManager as LinearLayoutManager).stackFromEnd).isTrue()
    }

    /**
     * Detaching the adapter is what recycles the bound rows, which is what cancels their thumbnail
     * coroutines — `RecyclerView.setAdapter(null)` runs `onViewRecycled` over the whole window.
     */
    @Test
    fun theViewTeardownDetachesTheAdapter() {
        launch()
        val recycler = list
        activity.supportFragmentManager.beginTransaction().remove(parent).commitNow()
        idle()
        assertThat(recycler.adapter).isNull()
    }

    /**
     * `onUserJoinedChannel` is declared with a nullable user, and the session user is null until
     * the server has named it — so `null == null` is a reachable pair, and without the explicit
     * `user != null` the hint would follow a channel change that is not the local user's.
     */
    @Test
    fun aChannelJoinWithNoUserAndNoSessionUserIsIgnored() {
        every { session.sessionUser } returns null
        launch()
        val before = editor.hint.toString()
        every { session.sessionChannel } returns channel("Lounge")
        observer.onUserJoinedChannel(null, channel("Lounge"), channel("Root"))
        assertThat(editor.hint.toString()).isEqualTo(before)
    }

    /**
     * An exception escaping a `lifecycleScope.launch` is not a failed test, it is a dead app: the
     * default handler for an unhandled coroutine exception is the thread's. So the catch around
     * the encode is measured by watching that handler, which is the only place its absence shows.
     */
    @Test
    fun aDisconnectWhileEncodingDoesNotEscapeTheCoroutine() {
        val escaped = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        try {
            every { session.serverSettings } returns settings(0)
            launch()
            every { service.HumlaSession() } throws HumlaDisconnectedException()
            fragment.sendImage(smallBitmap())
            drain { progress.visibility == View.GONE }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertThat(escaped).isEmpty()
    }

    /**
     * The one test in this class that binds an image row, and so the only one that runs anything in
     * the scope the adapter was handed. `lifecycleScope` is `Dispatchers.Main.immediate`, so a
     * coroutine started from the main thread runs inline up to its first real suspension: with a
     * loader that answers without suspending, the bitmap is on the view **before
     * `bindViewHolder` returns**. A scope on a background dispatcher cannot do that, because the
     * body would first have to be handed to another thread.
     */
    @Test
    fun theAdapterIsGivenAScopeThatDispatchesOnTheMainThread() {
        val bitmap = smallBitmap()
        val loader = mockk<ChatImageLoader>()
        coEvery { loader.loadThumbnail(any(), any(), any()) } returns ImageResult.Ready(bitmap)
        ChatImageLoaders.setForTests(loader)
        try {
            log += info("<img src=\"data:image/png;base64,AAAA\"/>")
            launch()
            drain { itemCount() == 1 }
            val adapter = list.adapter as ChatAdapter
            assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_IMAGE)
            val holder = adapter.createViewHolder(list, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
            adapter.bindViewHolder(holder, 0)
            assertThat(holder.image.drawable).isNotNull()
        } finally {
            ChatImageLoaders.setForTests(null)
        }
    }


    // ---- the seams: what the fragment hands the adapter, driven through a real row -----------

    /**
     * Lays the host out for real. `performClick()` ignores `isEnabled` and a touch delivered
     * straight at a child ignores its visibility — the filter is in the parent — and an unattached
     * view puts its click into the `HandlerActionQueue` while `post()` still returns true. So the
     * row tests below enter at the `RecyclerView` on a measured, laid-out window.
     */
    private fun layOutHost() {
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 1920)
    }

    /** A loader that answers `Ready` without suspending, so a bound row really has a bitmap. */
    private fun installThumbnailLoader(bitmap: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)):
        Pair<ChatImageLoader, MutableList<Triple<String, Int, Int>>> {
        val asked = mutableListOf<Triple<String, Int, Int>>()
        val loader = mockk<ChatImageLoader>()
        coEvery { loader.loadThumbnail(any(), any(), any()) } answers {
            asked += Triple(firstArg(), secondArg(), thirdArg())
            ImageResult.Ready(bitmap)
        }
        ChatImageLoaders.setForTests(loader)
        return loader to asked
    }

    private fun imageMessage(src: String = "data:image/png;base64,AAAA", trailing: String = "") =
        info("<img src=\"$src\"/>$trailing")

    /** Enters at [root] with the coordinates of [target]'s centre, the way a finger does. */
    private fun tapThrough(root: View, target: View) {
        var x = target.width / 2f
        var y = target.height / 2f
        var v: View = target
        while (v !== root) {
            x += v.left
            y += v.top
            v = v.parent as View
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0)
        root.dispatchTouchEvent(down)
        root.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        idle()
    }

    /**
     * I1: the seam this whole task exists for. Both ends were pinned — `ChatAdapter` calls
     * `onImageClicked` on a tap, and `openImageViewer` opens exactly one viewer — and the wire
     * between them was not: replacing `onImageClicked = ::openImageViewer` with `{ }` left all 432
     * tests green while tapping a picture did nothing at all.
     */
    @Test
    fun tappingAPictureInTheLogOpensTheViewerOnIt() {
        installThumbnailLoader()
        try {
            log += imageMessage("data:image/png;base64,TAPPED")
            launch()
            drain { itemCount() == 1 }
            layOutHost()
            val row = list.getChildAt(0)
            assertThat(row).isNotNull()
            val image = row.findViewById<ImageView>(R.id.list_chat_item_image)
            assertThat(image.visibility).isEqualTo(View.VISIBLE)
            assertThat(image.width).isGreaterThan(0)

            tapThrough(list, image)

            val viewer = fragment.parentFragmentManager
                .findFragmentByTag(ImageViewerDialogFragment.TAG) as ImageViewerDialogFragment
            assertThat(viewer.requireArguments().getString("source"))
                .isEqualTo("data:image/png;base64,TAPPED")
        } finally {
            ChatImageLoaders.setForTests(null)
        }
    }

    /**
     * I3: the other constructor seam, and the same shape as `NO_SESSION = -1` one level up.
     * `sessionId()` is pinned four ways and the adapter is pinned to align on it, but that the
     * fragment *supplies* it was read back by nothing: `selfSessionId = { NO_SESSION }` survived,
     * and every message you sent yourself would render left-aligned, as if somebody else had.
     *
     * The fixture carries both actors, because a fake whose session id is a constant cannot
     * express the difference the branch is about.
     */
    @Test
    fun yourOwnMessagesAreAlignedToYourSideAndOtherPeoplesAreNot() {
        every { session.sessionId } returns 7
        log += IChatMessage.TextMessage(Message(7, "Me", emptyList(), emptyList(), emptyList(), "mine"))
        log += IChatMessage.TextMessage(Message(9, "Ann", emptyList(), emptyList(), emptyList(), "theirs"))
        launch()
        drain { itemCount() == 2 }
        layOutHost()
        val adapter = list.adapter as ChatAdapter
        val mine = adapter.createViewHolder(list, ChatAdapter.TYPE_TEXT)
        adapter.bindViewHolder(mine, 0)
        val theirs = adapter.createViewHolder(list, ChatAdapter.TYPE_TEXT)
        adapter.bindViewHolder(theirs, 1)
        // Masked: LinearLayout.setGravity ORs a vertical gravity in, so the raw int carries bits
        // this claim is not about.
        assertThat(mine.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.RIGHT)
        assertThat(theirs.box.gravity and Gravity.HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.LEFT)
    }

    /** The bounds the fragment measures out of `chat_thumbnail_max` are what the loader is asked for. */
    @Test
    fun theThumbnailIsAskedForAtTheDimensionResourcesBound() {
        val (_, asked) = installThumbnailLoader()
        try {
            log += imageMessage()
            launch()
            drain { itemCount() == 1 }
            layOutHost()
            val expected = activity.resources.getDimensionPixelSize(R.dimen.chat_thumbnail_max)
            assertThat(expected).isGreaterThan(0)
            assertThat(asked).isNotEmpty()
            assertThat(asked.first().second).isEqualTo(expected)
            assertThat(asked.first().third).isEqualTo(expected)
        } finally {
            ChatImageLoaders.setForTests(null)
        }
    }

    /** The parser the fragment builds carries the localised stand-in for a second picture. */
    @Test
    fun aSecondPictureInOneMessageIsWrittenOutAsThePlaceholder() {
        installThumbnailLoader()
        try {
            log += info("<img src=\"data:image/png;base64,AAAA\"/>tail<img src=\"data:image/png;base64,BBBB\"/>")
            launch()
            drain { itemCount() == 1 }
            layOutHost()
            val adapter = list.adapter as ChatAdapter
            val holder = adapter.createViewHolder(list, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
            adapter.bindViewHolder(holder, 0)
            assertThat(holder.textAfter.text.toString())
                .contains(activity.getString(R.string.chat_image_placeholder))
        } finally {
            ChatImageLoaders.setForTests(null)
        }
    }


    // ---- I2: pick -> prepare -> confirm, which had no test at all ---------------------------

    private fun registerImage(uri: Uri, bytes: ByteArray) {
        shadowOf(activity.contentResolver).registerInputStreamSupplier(uri) {
            java.io.ByteArrayInputStream(bytes)
        }
    }

    /**
     * Granting the storage permission must open the picker. The mutation that flips this — `if
     * (granted)` to `if (!granted)` — is the one that matters: on Android 12 the user who says yes
     * would get "Permission denied to read storage" and never see a picker at all.
     */
    @Test
    @Config(sdk = [31])
    fun grantingTheStoragePermissionOpensThePicker() {
        launch()
        fragment.onReadPermissionResult(true)
        idle()
        assertThat(startedAction()).isEqualTo(Intent.ACTION_GET_CONTENT)
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    @Config(sdk = [31])
    fun refusingTheStoragePermissionSaysSoAndOpensNothing() {
        launch()
        fragment.onReadPermissionResult(false)
        idle()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(activity.getString(R.string.permission_denied_storage))
        assertThat(startedAction()).isNull()
    }

    /** Cancelling the picker is an ordinary outcome: no spinner, no dialog, no complaint. */
    @Test
    fun cancellingThePickerDoesNothing() {
        launch()
        fragment.onImagePickResult(null)
        idle()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        assertThat(ShadowDialog.getLatestDialog()).isNull()
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    /**
     * The whole outgoing path end to end, over the real `OutgoingImagePreparer` and a real JPEG:
     * picked uri, spinner up, decoded to the outgoing bounds off the main thread, confirmation
     * showing that very bitmap, spinner down.
     *
     * `@GraphicsMode(NATIVE)` for this test alone: the preparer decodes with `ImageDecoder`, which
     * Robolectric's legacy graphics does not implement. Its neighbours do not need it and it is
     * slower, which is why it is per test.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPickedPhotoIsPreparedAndShownForConfirmation() {
        launch()
        val uri = Uri.parse("content://se.lublin.mumla.test/photo.jpg")
        registerImage(uri, TestImages.jpeg(1200, 900))
        fragment.onImagePickResult(uri)
        assertThat(progress.visibility).isEqualTo(View.VISIBLE)
        drain { ShadowDialog.getLatestDialog() != null }
        idle()
        assertThat(progress.visibility).isEqualTo(View.GONE)

        val preview = latestDialog().window!!.decorView.firstImageView()!!
        val shown = (preview.drawable as BitmapDrawable).bitmap
        // Decoded straight to the outgoing bounds, not to the photo's own size: 1200 x 900 is
        // bounded by its height, so 533 x 400 rather than 1200 x 900 or 600 x 450.
        assertThat(shown.width).isEqualTo(533)
        assertThat(shown.height).isEqualTo(OutgoingImagePreparer.MAX_HEIGHT)
    }

    /** ...and a uri that is not an image is reported rather than shown. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPickedFileThatIsNotAnImageIsReportedAndOpensNoDialog() {
        launch()
        val uri = Uri.parse("content://se.lublin.mumla.test/notes.txt")
        registerImage(uri, "definitely not an image".toByteArray())
        fragment.onImagePickResult(uri)
        drain { ShadowToast.getLatestToast() != null }
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(activity.getString(R.string.image_decode_failed))
        assertThat(ShadowDialog.getLatestDialog()).isNull()
        assertThat(progress.visibility).isEqualTo(View.GONE)
    }

    /** A picked image with no session behind it never reaches the decoder. */
    @Test
    fun aPickedImageWithNoSessionIsDroppedBeforeAnythingIsDecoded() {
        launch()
        activity.bound = null
        fragment.onImagePickResult(Uri.parse("content://se.lublin.mumla.test/x.jpg"))
        idle()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }

    @Test
    fun aPickedImageWhileDisconnectedIsDroppedBeforeAnythingIsDecoded() {
        launch()
        disconnect()
        fragment.onImagePickResult(Uri.parse("content://se.lublin.mumla.test/x.jpg"))
        idle()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }


    // ---- the rest of the effect pass, run to exhaustion --------------------------------------

    /** The spinner is the only sign that the encode is running; it has to be up while it is. */
    @Test
    fun theSpinnerIsUpWhileAnImageIsBeingEncoded() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        fragment.sendImage(smallBitmap())
        assertThat(progress.visibility).isEqualTo(View.VISIBLE)
        drain { progress.visibility == View.GONE }
    }

    /** An image no quality rung fits is said out loud, not swallowed. */
    @Test
    fun anImageThatCannotBeMadeToFitSaysSo() {
        every { session.serverSettings } returns settings(10)
        launch()
        fragment.sendImage(smallBitmap())
        drain { ShadowToast.getLatestToast() != null }
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(activity.getString(R.string.image_too_large))
    }

    /** The newest message is the one you are meant to be looking at. */
    @Test
    fun theListIsScrolledToTheNewestMessage() {
        repeat(40) { log += info("m$it") }
        launch()
        drain { itemCount() == 40 }
        layOutHost()
        idle()
        val lm = list.layoutManager as LinearLayoutManager
        assertThat(lm.findLastVisibleItemPosition()).isEqualTo(39)
    }

    /** Without this the clear-chat item never reaches onCreateOptionsMenu at all. */
    @Test
    fun theFragmentAsksForItsOwnMenu() {
        launch()
        @Suppress("DEPRECATION")
        assertThat(fragment.hasOptionsMenu()).isTrue()
    }

    /**
     * A hardware Enter sends. `TextView.doKeyDown` offers `KEYCODE_ENTER` to the editor action
     * listener as `IME_NULL` **with the key event**, before it would insert a newline — which is
     * why the listener tests for the event and not only for the action id. Driven with a real key
     * event for that reason: `onEditorAction(id)` hands the listener a null event and would take
     * the other branch, so it would report this as working while a keyboard did nothing.
     */
    @Test
    fun aHardwareEnterInTheEditorSendsTheMessage() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        layOutHost()
        editor.requestFocus()
        editor.setText("typed")
        editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        idle()
        verify { session.sendChannelTextMessage(any(), eq("typed"), any()) }
        assertThat(editor.text.toString()).isEmpty()
    }

    /** ...and a soft action that is not Enter does not, which is the other side of that clause. */
    @Test
    fun aSoftImeActionWithoutAKeyEventDoesNotSend() {
        launch()
        editor.setText("typed")
        editor.onEditorAction(EditorInfo.IME_ACTION_SEND)
        idle()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
        assertThat(editor.text.toString()).isEqualTo("typed")
    }
}
