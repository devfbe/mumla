package se.lublin.mumla.channel

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import se.lublin.mumla.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
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
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.exception.HumlaDisconnectedException
import se.lublin.mumla.R
import se.lublin.mumla.chat.ChatAdapter
import se.lublin.mumla.chat.ChatImageLoader
import se.lublin.mumla.chat.ChatImageLoaders
import se.lublin.mumla.chat.ImageError
import se.lublin.mumla.chat.ImageFetchException
import se.lublin.mumla.chat.ImageResult
import se.lublin.mumla.chat.ImageViewerDialogFragment
import se.lublin.mumla.chat.OutgoingImagePreparer
import se.lublin.mumla.chat.TestImages
import se.lublin.mumla.service.IChatMessage
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ChatTargetParentFragment
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubDisconnected
import se.lublin.mumla.testing.stubEvents

/**
 * Driven through a real host: an `Activity` whose [se.lublin.mumla.app.ServiceViewModel] holds the
 * service, and a parent `Fragment` that holds the chat target.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelChatFragmentTest {

    private val service: IMumlaService = mockk(relaxed = true)
    private val session: IHumlaSession = mockk(relaxed = true)
    private val log = MutableStateFlow<List<IChatMessage>>(emptyList())

    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var activity: ServiceHostActivity
    private lateinit var parent: ChatTargetParentFragment
    private lateinit var fragment: ChannelChatFragment

    @Before
    fun setUp() {
        service.stubConnected(session)
        service.stubEvents()
        every { service.messageLog } returns log
        every { service.clearMessageLog() } answers { log.value = emptyList() }
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
    /** Delivered at once: the fragment collects on the immediate main dispatcher. */
    private fun selectTarget(target: ChatTarget) {
        parent.chatTargets.select(target)
    }

    private fun launch(withService: IMumlaService? = service) {
        controller = Robolectric.buildActivity(ServiceHostActivity::class.java)
        activity = controller.create().get()
        activity.bind(withService)
        parent = ChatTargetParentFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, parent, "parent").commitNow()
        fragment = ChannelChatFragment()
        parent.childFragmentManager.beginTransaction()
            .add(ChatTargetParentFragment.CONTAINER_ID, fragment, "chat").commitNow()
        controller.start().resume().visible()
        idleMainLooper()
    }


    private fun itemCount(): Int = list.adapter!!.itemCount

    private val list: RecyclerView get() = fragment.requireView().findViewById(R.id.chat_list)
    private val editor: EditText get() = fragment.requireView().findViewById(R.id.chatTextEdit)
    private val sendButton: ImageButton get() = fragment.requireView().findViewById(R.id.chatTextSend)

    private fun channel(name: String?, id: Int = 1): IChannel =
        Channel(id, false).also { it.name = name }

    private fun user(name: String, session: Int = 42): IUser =
        User(session, name)

    private fun info(body: String) =
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, body)

    // ---- the list ---------------------------------------------------------------------------

    @Test
    fun theBoundMessageLogIsWhatTheListShows() {
        log.value += info("older")
        log.value += info("newer")
        launch()
        drainMainUntil { itemCount() == 2 }
        assertThat(itemCount()).isEqualTo(2)
    }

    @Test
    fun anArrivingLogLineIsAppended() {
        launch()
        log.value += info("hello")
        drainMainUntil { itemCount() == 1 }
        assertThat(itemCount()).isEqualTo(1)
    }

    @Test
    fun clearEmptiesTheListAndTheServiceLog() {
        log.value += info("older")
        launch()
        fragment.clear()
        drainMainUntil { itemCount() == 0 }
        assertThat(itemCount()).isEqualTo(0)
        verify { service.clearMessageLog() }
    }

    @Test
    fun aMessageArrivingAfterTheViewIsGoneIsNotACrash() {
        launch()
        activity.supportFragmentManager.beginTransaction().remove(parent).commitNow()
        idleMainLooper()
        log.value += info("late")
        idleMainLooper()
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
        selectTarget(ChatTarget.User(user("Ann")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToUser, "Ann"))
    }

    @Test
    fun theHintNamesTheTargetChannel() {
        launch()
        selectTarget(ChatTarget.Channel(channel("Lounge")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Lounge"))
    }

    /** No target and no session channel: nothing to name. */
    @Test
    fun theHintIsClearedWhenThereIsNothingToName() {
        every { session.sessionChannel } returns null
        launch()
        assertThat(editor.hint).isNull()
    }

    /** The service usually binds after the view exists, so the hint must be set on bind too. */
    @Test
    fun theHintIsSetWhenTheServiceBindsAfterTheView() {
        launch(withService = null)
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.send_message))
        activity.bind(service)
        drainMainUntil { editor.hint.toString() != activity.getString(R.string.send_message) }
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Root"))
    }

    /** The local user moved, and no target overrides the hint. */
    @Test
    fun theHintFollowsTheLocalUserIntoANewChannel() {
        val self = user("Me", session = 7)
        every { session.sessionUser } returns self
        launch()
        every { session.sessionChannel } returns channel("Lounge")
        fragment.onServiceEvent(HumlaEvent.UserJoinedChannel(self, channel("Lounge"), channel("Root")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Lounge"))
    }

    @Test
    fun theHintDoesNotFollowSomebodyElseIntoANewChannel() {
        every { session.sessionUser } returns user("Me", session = 7)
        launch()
        every { session.sessionChannel } returns channel("Lounge")
        fragment.onServiceEvent(HumlaEvent.UserJoinedChannel(user("Ann"), channel("Lounge"), channel("Root")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Root"))
    }

    @Test
    fun theHintDoesNotFollowTheLocalUserWhileATargetIsSet() {
        val self = user("Me", session = 7)
        every { session.sessionUser } returns self
        launch()
        selectTarget(ChatTarget.User(user("Ann")))
        every { session.sessionChannel } returns channel("Lounge")
        fragment.onServiceEvent(HumlaEvent.UserJoinedChannel(self, channel("Lounge"), channel("Root")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToUser, "Ann"))
    }

    // ---- the compose row --------------------------------------------------------------------

    /**
     * `android:enabled="false"` on an `ImageButton` is inert (see `ChatLayoutTest`), and the text
     * watcher only fires on a change, so the initial state has to be set in code.
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
    fun typedMarkdownIsSentAsHtmlAndTypedHtmlIsEscaped() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        editor.setText("**hi** <b>there</b>")
        sendButton.performClick()
        verify { session.sendChannelTextMessage(1, "<b>hi</b> &lt;b&gt;there&lt;/b&gt;", false) }
    }

    @Test
    fun withoutMarkdownTheTextIsSentAsBefore() {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
            .edit().putBoolean(Settings.PREF_MARKDOWN, false).commit()
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        editor.setText("**hi** <b>there</b>")
        sendButton.performClick()
        verify { session.sendChannelTextMessage(1, "**hi** <b>there</b>", false) }
    }

    @Test
    fun sendingWithAUserTargetGoesToThatUser() {
        every { session.sendUserTextMessage(any(), any()) } returns Message("out")
        launch()
        selectTarget(ChatTarget.User(user("Ann", session = 42)))
        editor.setText("hi")
        sendButton.performClick()
        verify { session.sendUserTextMessage(42, "hi") }
    }

    @Test
    fun sendingWithAChannelTargetGoesToThatChannel() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        selectTarget(ChatTarget.Channel(channel("Lounge", id = 9)))
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

    @Test
    fun aDisconnectWhileSendingIsSwallowed() {
        launch()
        every { service.session } throws HumlaDisconnectedException()
        editor.setText("hi")
        sendButton.performClick()
        assertThat(editor.text.toString()).isEqualTo("hi")
    }

    // ---- the viewer -------------------------------------------------------------------------

    private fun openViewers(): Int =
        fragment.parentFragmentManager.fragments.count { it is ImageViewerDialogFragment }

    /**
     * `show(fm, tag)` does not deduplicate by tag, so two quick taps would open two viewers
     * exporting to one share path.
     */
    @Test
    fun aSecondTapWhileTheViewerIsOpenDoesNotOpenASecond() {
        launch()
        fragment.openImageViewer("data:image/png;base64,AAAA")
        fragment.openImageViewer("data:image/png;base64,AAAA")
        idleMainLooper()
        assertThat(openViewers()).isEqualTo(1)
    }

    /** Once the viewer is gone, the next tap opens one again. */
    @Test
    fun theViewerOpensAgainAfterItIsDismissed() {
        launch()
        fragment.openImageViewer("data:image/png;base64,AAAA")
        idleMainLooper()
        (fragment.parentFragmentManager.findFragmentByTag(ImageViewerDialogFragment.TAG)
            as ImageViewerDialogFragment).dismissNow()
        idleMainLooper()
        assertThat(openViewers()).isEqualTo(0)
        fragment.openImageViewer("data:image/png;base64,AAAA")
        idleMainLooper()
        assertThat(openViewers()).isEqualTo(1)
    }

    // ---- the session id ---------------------------------------------------------------------

    @Test
    fun theSessionIdIsTheLiveOneWhileConnected() {
        launch()
        assertThat(fragment.sessionId()).isEqualTo(7)
    }

    /** Asked on every bind, and a disconnect with the log on screen is ordinary. */
    @Test
    fun theSessionIdSurvivesADisconnect() {
        launch()
        every { service.session } throws HumlaDisconnectedException()
        assertThat(fragment.sessionId()).isNotEqualTo(7)
    }

    @Test
    fun theSessionIdIsAbsentWithNoServiceAndWhenNotConnected() {
        launch(withService = null)
        val noService = fragment.sessionId()
        activity.bind(service)
        every { service.isConnected } returns false
        assertThat(fragment.sessionId()).isEqualTo(noService)
    }

    /**
     * "No session" must be a value no actor can take: `Message(String)` sets its actor to -1, so
     * -1 would render actorless messages as the local user's.
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
        drainMainUntil {
            runCatching { verify { session.sendChannelTextMessage(any(), capture(sent), any()) } }.isSuccess
        }
        assertThat(sent.captured).startsWith("<img src=\"data:image/jpeg;base64,")
        assertThat(progress.visibility).isEqualTo(View.GONE)
    }

    /**
     * A dialog stands between picking and confirming, so the service is fetched again at send.
     */
    @Test
    fun anImageConfirmedAfterTheServiceWentAwaySendsNothing() {
        launch()
        activity.bind(null)
        fragment.sendImage(smallBitmap())
        idleMainLooper()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
        assertThat(progress.visibility).isEqualTo(View.GONE)
    }

    /** Connected at the tap, gone by the time the encoder asks. */
    @Test
    fun aDisconnectWhileEncodingAnImageSendsNothing() {
        launch()
        every { service.session } throws HumlaDisconnectedException()
        fragment.sendImage(smallBitmap())
        drainMainUntil { progress.visibility == View.GONE }
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    /** An image no quality rung can squeeze under the server's limit is reported, not sent. */
    @Test
    fun anImageThatCannotBeMadeToFitIsNotSent() {
        every { session.serverSettings } returns settings(10)
        launch()
        fragment.sendImage(smallBitmap())
        drainMainUntil { progress.visibility == View.GONE }
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    private fun settings(imageMessageLength: Int): ServerSettings =
        mockk(relaxed = true) { every { this@mockk.imageMessageLength } returns imageMessageLength }

    /**
     * `isConnected` and the throw inside `session` read the same state in production, so
     * this disconnects both together.
     */
    private fun disconnect() {
        service.stubDisconnected()
    }

    @Test
    fun theHintIsLeftAloneWhileDisconnected() {
        launch()
        val before = editor.hint.toString()
        disconnect()
        selectTarget(ChatTarget.User(user("Ann")))
        assertThat(editor.hint.toString()).isEqualTo(before)
    }

    @Test
    fun aChannelJoinArrivingAfterTheDisconnectIsIgnored() {
        every { session.sessionUser } returns user("Me", session = 7)
        launch()
        val self = session.sessionUser!!
        disconnect()
        fragment.onServiceEvent(HumlaEvent.UserJoinedChannel(self, channel("Lounge"), channel("Root")))
    }

    /** `updateChatTargetText` is public, so it may be called before the view exists. */
    @Test
    fun theHintIsSafeToUpdateBeforeThereIsAView() {
        ChannelChatFragment().updateChatTargetText(null)
    }

    @Test
    fun aTargetSelectedWhilePausedIsShownOnResumption() {
        launch()
        controller.pause()
        selectTarget(ChatTarget.User(user("Ann")))
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToChannel, "Root"))
        controller.resume()
        idleMainLooper()
        assertThat(editor.hint.toString()).isEqualTo(activity.getString(R.string.messageToUser, "Ann"))
    }

    @Test
    fun theClearMenuItemClearsTheLog() {
        log.value += info("older")
        launch()
        val item: MenuItem = mockk(relaxed = true) { every { itemId } returns R.id.menu_clear_chat }
        assertThat(fragment.onMenuItemSelected(item)).isTrue()
        drainMainUntil { itemCount() == 0 }
        verify { service.clearMessageLog() }
    }

    // ---- storage permission ------------------------------------------------------------------
    private fun tapUpload() {
        val button = fragment.requireView().findViewById<ImageButton>(R.id.chatImageSend)
        // performClick ignores isEnabled and visibility, so the state is asserted explicitly.
        assertThat(button.isEnabled).isTrue()
        assertThat(button.visibility).isEqualTo(View.VISIBLE)
        button.performClick()
        idleMainLooper()
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

    // ---- confirmation dialog -----------------------------------------------------------------
    private fun latestDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun theConfirmationShowsThePickedImageAndSendsOnlyOnOk() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        val bitmap = smallBitmap()
        fragment.confirmImage(bitmap)
        idleMainLooper()
        val dialog = latestDialog()
        assertThat(dialog.isShowing).isTrue()
        val preview = dialog.window!!.decorView.firstImageView()
        assertThat(preview).isNotNull()
        assertThat((preview!!.drawable as BitmapDrawable).bitmap).isSameInstanceAs(bitmap)
        assertThat(preview.contentDescription.toString())
            .isEqualTo(activity.getString(R.string.image_confirm_send))
        assertThat(preview.adjustViewBounds).isTrue()
        assertThat(preview.scaleType).isEqualTo(ImageView.ScaleType.FIT_CENTER)
        assertThat(preview.maxHeight).isEqualTo(activity.resources.displayMetrics.heightPixels / 3)

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMainLooper()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
    }

    @Test
    fun theConfirmationSendsOnOk() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        fragment.confirmImage(smallBitmap())
        idleMainLooper()
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        drainMainUntil { runCatching { verify { session.sendChannelTextMessage(any(), any(), any()) } }.isSuccess }
    }

    private fun View.firstImageView(): ImageView? {
        if (this is ImageView && contentDescription != null) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).firstImageView()?.let { return it }
        }
        return null
    }

    /** The hint sets the compose box width, so a changed hint has to reach layout. */
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
        selectTarget(ChatTarget.User(user("Ann")))
        assertThat(editor.isLayoutRequested).isTrue()
    }

    /**
     * The server does not echo a message back to its sender, so this is what puts your own message
     * into your log.
     */
    @Test
    fun aSentMessageAppearsInTheListImmediately() {
        every { session.sendChannelTextMessage(any(), any(), any()) } answers {
            Message("hi there").also { log.value += IChatMessage.TextMessage(it) }
        }
        launch()
        editor.setText("hi there")
        sendButton.performClick()
        drainMainUntil { itemCount() == 1 }
        assertThat(itemCount()).isEqualTo(1)
    }

    /** Rebinding (every reconnect) shows the log as it is rather than appending it again. */
    @Test
    fun rebindingShowsTheLogWithoutDuplicatingIt() {
        log.value += info("a")
        log.value += info("b")
        launch()
        log.value += info("live")
        drainMainUntil { itemCount() == 3 }
        activity.bind(null)
        activity.bind(service)
        idleMainLooper()
        assertThat(itemCount()).isEqualTo(3)
    }

    @Test
    fun theListSticksToTheNewestMessage() {
        launch()
        assertThat((list.layoutManager as LinearLayoutManager).stackFromEnd).isTrue()
    }

    /**
     * Detaching the adapter recycles the bound rows, which cancels their thumbnail coroutines.
     */
    @Test
    fun theViewTeardownDetachesTheAdapter() {
        launch()
        val recycler = list
        activity.supportFragmentManager.beginTransaction().remove(parent).commitNow()
        idleMainLooper()
        assertThat(recycler.adapter).isNull()
    }

    /** The session user is null until the server has named it; a join then moves nothing. */
    @Test
    fun aChannelJoinBeforeTheSessionUserIsKnownIsIgnored() {
        every { session.sessionUser } returns null
        launch()
        val before = editor.hint.toString()
        every { session.sessionChannel } returns channel("Lounge")
        val self = user("Me", session = 7)
        fragment.onServiceEvent(HumlaEvent.UserJoinedChannel(self, channel("Lounge"), channel("Root")))
        assertThat(editor.hint.toString()).isEqualTo(before)
    }

    /**
     * An exception escaping `lifecycleScope.launch` goes to the thread's uncaught-exception
     * handler, so that is where the encode's catch is checked.
     */
    @Test
    fun aDisconnectWhileEncodingDoesNotEscapeTheCoroutine() {
        val escaped = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        try {
            every { session.serverSettings } returns settings(0)
            launch()
            every { service.session } throws HumlaDisconnectedException()
            fragment.sendImage(smallBitmap())
            drainMainUntil { progress.visibility == View.GONE }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertThat(escaped).isEmpty()
    }

    /**
     * `lifecycleScope` is `Dispatchers.Main.immediate`, so with a loader that does not suspend the
     * bitmap is on the view before `bindViewHolder` returns.
     */
    @Test
    fun theAdapterIsGivenAScopeThatDispatchesOnTheMainThread() {
        val bitmap = smallBitmap()
        val loader = mockk<ChatImageLoader>()
        coEvery { loader.loadThumbnail(any(), any(), any()) } returns ImageResult.Ready(bitmap)
        ChatImageLoaders.setForTests(loader)
        try {
            log.value += info("<img src=\"data:image/png;base64,AAAA\"/>")
            launch()
            drainMainUntil { itemCount() == 1 }
            val adapter = list.adapter as ChatAdapter
            assertThat(adapter.getItemViewType(0)).isEqualTo(ChatAdapter.TYPE_IMAGE)
            val holder = adapter.createViewHolder(list, ChatAdapter.TYPE_IMAGE) as ChatAdapter.ImageHolder
            adapter.bindViewHolder(holder, 0)
            assertThat(holder.image.drawable).isNotNull()
        } finally {
            ChatImageLoaders.setForTests(null)
        }
    }


    // ---- seams: what the fragment hands the adapter, driven through a real row ---------------

    /**
     * Lays the host out for real: `performClick()` ignores `isEnabled`, a touch sent straight to a
     * child ignores its visibility, and an unattached view queues its click, so row tests enter at
     * the `RecyclerView` of a laid-out window.
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
        idleMainLooper()
    }

    /** Tapping an image row opens the viewer through the `onImageClicked` wire. */
    @Test
    fun tappingAPictureInTheLogOpensTheViewerOnIt() {
        val (loader, _) = installThumbnailLoader()
        // The viewer fetches the full image; this test is only about opening it.
        coEvery { loader.fetchBytes(any()) } throws ImageFetchException(ImageError.NETWORK)
        try {
            log.value += imageMessage("data:image/png;base64,TAPPED")
            launch()
            drainMainUntil { itemCount() == 1 }
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
     * The fragment supplies its session id to the adapter, so your own messages align right. The
     * fixture carries both actors to tell the two apart.
     */
    @Test
    fun yourOwnMessagesAreAlignedToYourSideAndOtherPeoplesAreNot() {
        every { session.sessionId } returns 7
        log.value += IChatMessage.TextMessage(Message(7, "Me", emptyList(), emptyList(), emptyList(), "mine"))
        log.value += IChatMessage.TextMessage(Message(9, "Ann", emptyList(), emptyList(), emptyList(), "theirs"))
        launch()
        drainMainUntil { itemCount() == 2 }
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
            log.value += imageMessage()
            launch()
            drainMainUntil { itemCount() == 1 }
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
            log.value += info("<img src=\"data:image/png;base64,AAAA\"/>tail<img src=\"data:image/png;base64,BBBB\"/>")
            launch()
            drainMainUntil { itemCount() == 1 }
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


    // ---- pick -> prepare -> confirm ----------------------------------------------------------
    private fun registerImage(uri: Uri, bytes: ByteArray) {
        shadowOf(activity.contentResolver).registerInputStreamSupplier(uri) {
            java.io.ByteArrayInputStream(bytes)
        }
    }

    /** Granting the storage permission opens the picker. */
    @Test
    @Config(sdk = [31])
    fun grantingTheStoragePermissionOpensThePicker() {
        launch()
        fragment.onReadPermissionResult(true)
        idleMainLooper()
        assertThat(startedAction()).isEqualTo(Intent.ACTION_GET_CONTENT)
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    @Test
    @Config(sdk = [31])
    fun refusingTheStoragePermissionSaysSoAndOpensNothing() {
        launch()
        fragment.onReadPermissionResult(false)
        idleMainLooper()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(activity.getString(R.string.permission_denied_storage))
        assertThat(startedAction()).isNull()
    }

    @Test
    fun cancellingThePickerDoesNothing() {
        launch()
        fragment.onImagePickResult(null)
        idleMainLooper()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        assertThat(ShadowDialog.getLatestDialog()).isNull()
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    /**
     * The outgoing path end to end over the real `OutgoingImagePreparer` and a real JPEG.
     * `@GraphicsMode(NATIVE)` here only: `ImageDecoder` needs it, and it is slower.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPickedPhotoIsPreparedAndShownForConfirmation() {
        launch()
        val uri = Uri.parse("content://se.lublin.mumla.test/photo.jpg")
        registerImage(uri, TestImages.jpeg(1200, 900))
        fragment.onImagePickResult(uri)
        assertThat(progress.visibility).isEqualTo(View.VISIBLE)
        drainMainUntil { ShadowDialog.getLatestDialog() != null }
        idleMainLooper()
        assertThat(progress.visibility).isEqualTo(View.GONE)

        val preview = latestDialog().window!!.decorView.firstImageView()!!
        val shown = (preview.drawable as BitmapDrawable).bitmap
        // Bounded by height: 1200 x 900 -> 533 x 400.
        assertThat(shown.width).isEqualTo(533)
        assertThat(shown.height).isEqualTo(OutgoingImagePreparer.MAX_HEIGHT)
    }

    /** A uri that is not an image is reported rather than shown. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPickedFileThatIsNotAnImageIsReportedAndOpensNoDialog() {
        launch()
        val uri = Uri.parse("content://se.lublin.mumla.test/notes.txt")
        registerImage(uri, "definitely not an image".toByteArray())
        fragment.onImagePickResult(uri)
        drainMainUntil { ShadowToast.getLatestToast() != null }
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(activity.getString(R.string.image_decode_failed))
        assertThat(ShadowDialog.getLatestDialog()).isNull()
        assertThat(progress.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun aPickedImageWithNoSessionIsDroppedBeforeAnythingIsDecoded() {
        launch()
        activity.bind(null)
        fragment.onImagePickResult(Uri.parse("content://se.lublin.mumla.test/x.jpg"))
        idleMainLooper()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }

    @Test
    fun aPickedImageWhileDisconnectedIsDroppedBeforeAnythingIsDecoded() {
        launch()
        disconnect()
        fragment.onImagePickResult(Uri.parse("content://se.lublin.mumla.test/x.jpg"))
        idleMainLooper()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        assertThat(ShadowDialog.getLatestDialog()).isNull()
    }


    // ---- misc ---------------------------------------------------------------------------------

    @Test
    fun theSpinnerIsUpWhileAnImageIsBeingEncoded() {
        every { session.serverSettings } returns settings(0)
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        assertThat(progress.visibility).isEqualTo(View.GONE)
        fragment.sendImage(smallBitmap())
        assertThat(progress.visibility).isEqualTo(View.VISIBLE)
        drainMainUntil { progress.visibility == View.GONE }
    }

    /** An image no quality rung fits is reported. */
    @Test
    fun anImageThatCannotBeMadeToFitSaysSo() {
        every { session.serverSettings } returns settings(10)
        launch()
        fragment.sendImage(smallBitmap())
        drainMainUntil { ShadowToast.getLatestToast() != null }
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(activity.getString(R.string.image_too_large))
    }

    @Test
    fun theListIsScrolledToTheNewestMessage() {
        repeat(40) { log.value += info("m$it") }
        launch()
        drainMainUntil { itemCount() == 40 }
        layOutHost()
        idleMainLooper()
        val lm = list.layoutManager as LinearLayoutManager
        assertThat(lm.findLastVisibleItemPosition()).isEqualTo(39)
    }

    /** The clear-chat item reaches the activity's menu while the chat is shown. */
    @Test
    fun theFragmentAddsItsOwnMenu() {
        launch()
        val menu = MenuBuilder(activity)
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        assertThat(menu.findItem(R.id.menu_clear_chat)).isNotNull()
    }

    /**
     * A hardware Enter sends. `TextView.doKeyDown` offers `KEYCODE_ENTER` to the listener as
     * `IME_NULL` with the key event, so this uses a real key event; `onEditorAction(id)` would
     * pass a null event and take the other branch.
     */
    @Test
    fun aHardwareEnterInTheEditorSendsTheMessage() {
        every { session.sendChannelTextMessage(any(), any(), any()) } returns Message("out")
        launch()
        layOutHost()
        editor.requestFocus()
        editor.setText("typed")
        editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        idleMainLooper()
        verify { session.sendChannelTextMessage(any(), eq("typed"), any()) }
        assertThat(editor.text.toString()).isEmpty()
    }

    /** A soft action that is not Enter does not send. */
    @Test
    fun aSoftImeActionWithoutAKeyEventDoesNotSend() {
        launch()
        editor.setText("typed")
        editor.onEditorAction(EditorInfo.IME_ACTION_SEND)
        idleMainLooper()
        verify(exactly = 0) { session.sendChannelTextMessage(any(), any(), any()) }
        assertThat(editor.text.toString()).isEqualTo("typed")
    }
}
