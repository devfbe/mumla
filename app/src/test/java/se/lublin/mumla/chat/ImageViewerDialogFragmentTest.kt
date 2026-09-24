package se.lublin.mumla.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragment
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import se.lublin.mumla.R
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class ImageViewerDialogFragmentTest {
    private val source = "https://x.org/a.png"
    private val other = "https://x.org/b.png"

    // printf 'https://x.org/a.png' | sha1sum
    private val keyOfA = "c03da97f398e3f951d29689263e7fa31bf3c163d"

    /** Runs nothing until it is told to. */
    private class ParkingDispatcher : CoroutineDispatcher() {
        private val parked = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            parked += block
        }

        fun release() {
            while (parked.isNotEmpty()) parked.removeFirst().run()
        }
    }

    private var loader: ChatImageLoader? = null

    /** Installs a real loader over a fake fetcher, on dispatchers that never leave this thread. */
    private fun installLoader(
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        fetcher: ImageFetcher,
    ) {
        loader = ChatImageLoader(
            fetcher = fetcher,
            externalImagesAllowed = { true },
            maxCacheBytes = 8L * 1024 * 1024,
            ioDispatcher = ioDispatcher,
            decodeDispatcher = Dispatchers.Unconfined,
        ).also { ChatImageLoaders.setForTests(it) }
    }

    @Before
    fun resetTheFileProviderRoots() {
        FileProviderCache.clear()
    }

    @After
    fun removeLoader() {
        ChatImageLoaders.setForTests(null)
        loader = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * A tap that enters at the fragment's root view and is hit-tested down to [id], so disabled,
     * `GONE` and unlaid-out (0x0) targets fail as they would for a finger; [View.performClick]
     * would see none of these.
     */
    private fun ImageViewerDialogFragment.tap(id: Int) {
        val root = requireView()
        val target = root.findViewById<View>(id)
        assertThat(target.width).isGreaterThan(0)
        assertThat(target.height).isGreaterThan(0)
        var x = target.width / 2f
        var y = target.height / 2f
        var view: View = target
        while (view !== root) {
            x += view.left
            y += view.top
            view = view.parent as View
        }
        val down = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(down, down + 1, MotionEvent.ACTION_UP, x, y, 0)
        root.dispatchTouchEvent(downEvent)
        root.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
        idle()
    }

    private fun ImageViewerDialogFragment.image(): ZoomImageView =
        requireView().findViewById(R.id.image_viewer_image)

    private fun ImageViewerDialogFragment.progress(): View =
        requireView().findViewById(R.id.image_viewer_progress)

    private fun ImageViewerDialogFragment.status(): TextView =
        requireView().findViewById(R.id.image_viewer_status)

    private fun ImageViewerDialogFragment.share(): View =
        requireView().findViewById(R.id.image_viewer_share)

    /** The ACTION_SEND the chooser was built around, or an assertion failure if nothing was started. */
    private fun sentIntent(fragment: ImageViewerDialogFragment): Intent {
        val chooser = shadowOf(fragment.requireActivity()).nextStartedActivity
        assertThat(chooser).isNotNull()
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    }

    private fun exportedFile(fragment: ImageViewerDialogFragment, name: String): File =
        File(fragment.requireContext().cacheDir, ImageShareExporter.DIRECTORY + "/" + name)

    /** Sizes the image view by hand so the assertions can rely on a known size. */
    private fun ImageViewerDialogFragment.layOutTheImage(side: Int = 400) {
        image().layout(0, 0, side, side)
    }

    /**
     * Arguments go through `fragmentArgs`: `FragmentScenario` calls `setArguments(fragmentArgs)` on
     * whatever the lambda returned, which would overwrite the bundle [ImageViewerDialogFragment.newInstance]
     * built with `null`.
     */
    private fun launch(source: String? = this.source): FragmentScenario<ImageViewerDialogFragment> =
        launchFragment(fragmentArgs = source?.let { ImageViewerDialogFragment.newInstance(it).arguments }) {
            ImageViewerDialogFragment()
        }

    /**
     * [launch], one block on the fragment, and close the scenario, so no host activity stays in the
     * stack where `shadowOf(activity).nextStartedActivity` would read it.
     */
    private fun launched(source: String? = this.source, block: (ImageViewerDialogFragment) -> Unit) {
        launch(source).use { it.onFragment(block) }
    }

    @Test
    fun aLoadedImageIsShownAndCanBeShared() {
        installLoader { TestImages.png(100, 50) }
        launched { fragment ->
            idle()
            assertThat(fragment.progress().visibility).isEqualTo(View.GONE)
            assertThat(fragment.image().drawable).isNotNull()
            assertThat(fragment.status().visibility).isEqualTo(View.GONE)
            assertThat(fragment.share().isEnabled).isTrue()
        }
    }

    @Test
    fun aFailedLoadShowsTheErrorAndKeepsSharingDisabled() {
        installLoader { throw ImageFetchException(ImageError.NETWORK) }
        launched { fragment ->
            idle()
            assertThat(fragment.progress().visibility).isEqualTo(View.GONE)
            assertThat(fragment.status().visibility).isEqualTo(View.VISIBLE)
            assertThat(fragment.status().text.toString())
                .isEqualTo(fragment.getString(R.string.chat_image_load_failed))
            assertThat(fragment.share().isEnabled).isFalse()
            assertThat(fragment.image().drawable).isNull()
        }
    }

    @Test
    fun sharingStartsAChooserThatCanReadTheExportedFile() {
        installLoader { TestImages.png(100, 50) }
        launched { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            fragment.tap(R.id.image_viewer_share)

            val activity: Activity = fragment.requireActivity()
            val chooser = shadowOf(activity).nextStartedActivity
            assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
            assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)

            val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
            assertThat(send.type).isEqualTo("image/png")
            assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
            val expected = "content://${fragment.requireContext().packageName}.fileprovider/" +
                "shared_images/c03da97f398e3f951d29689263e7fa31bf3c163d.png"
            assertThat(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java).toString())
                .isEqualTo(expected)
            // Without ClipData the chooser drops the grant; assert it is carried.
            assertThat(send.clipData!!.getItemAt(0).uri.toString()).isEqualTo(expected)
            // The debounce also releases after a successful share.
            assertThat(fragment.share().isEnabled).isTrue()
        }
    }

    /**
     * `BitmapUtils.resizeKeepingAspect` never enlarges, so the requested bound (twice the screen)
     * is exactly the bitmap's size on the limiting axis. The expectation is built from the
     * environment's own metrics.
     */
    @Test
    fun theImageIsDecodedBetweenOneAndTwoScreensSoThereIsDetailToZoomInto() {
        installLoader { TestImages.png(2000, 2000) }
        launched { fragment ->
            idle()
            val metrics = fragment.resources.displayMetrics
            assertThat(metrics.widthPixels).isEqualTo(320)
            assertThat(metrics.heightPixels).isEqualTo(470)
            val bitmap = (fragment.image().drawable as BitmapDrawable).bitmap
            assertThat(bitmap.width).isAtMost(2 * metrics.widthPixels)
            assertThat(bitmap.width).isAtLeast(metrics.widthPixels)
        }
    }

    /** The same on a different screen, so a constant bound cannot pass. */
    @Test
    @Config(qualifiers = "w480dp-h800dp-mdpi")
    fun theDecodeBoundFollowsTheScreenRatherThanAConstant() {
        installLoader { TestImages.png(3000, 3000) }
        launched { fragment ->
            idle()
            val metrics = fragment.resources.displayMetrics
            assertThat(metrics.widthPixels).isEqualTo(480)
            assertThat(metrics.heightPixels).isEqualTo(800)
            val bitmap = (fragment.image().drawable as BitmapDrawable).bitmap
            // 3000 into 960 samples at 8, into 640 at 16: 750 here, 375 on the default screen.
            assertThat(bitmap.width).isEqualTo(750)
            assertThat(bitmap.width).isAtMost(2 * metrics.widthPixels)
            assertThat(bitmap.width).isAtLeast(metrics.widthPixels)
        }
    }

    /** 1080x2340 at xxhdpi: the doubled bound is 2160x4680 ARGB_8888 = 40_435_200 B. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun theDoubledDecodeKeepsFortyMegabytesOnAFullHdPhone() {
        installLoader { TestImages.png(2400, 5200) }
        launched { fragment ->
            idle()
            val metrics = fragment.resources.displayMetrics
            assertThat(metrics.widthPixels).isEqualTo(1080)
            assertThat(metrics.heightPixels).isEqualTo(2340)

            val shown = (fragment.image().drawable as BitmapDrawable).bitmap
            // 2400 x 5200 into 2160 x 4680 samples at 2 and stops there.
            assertThat(shown.width).isEqualTo(1200)
            assertThat(shown.height).isEqualTo(2600)
            assertThat(shown.byteCount).isAtMost(40_435_200)
            assertThat(shadowOf(shown).createdFromBitmap).isNull()
        }
    }

    /**
     * The decode peak is the bitmap that is kept: `loadFull` uses `decodeAtMost`, so there is no
     * scaled intermediate alive beside the result. The bitmap is still never smaller than one
     * screen on the limiting axis.
     */
    @Test
    fun theDecodeHoldsNothingBesideTheBitmapItKeeps() {
        // 1279x1879 into the 640x940 box: one halving would undershoot the exact fit.
        installLoader { TestImages.png(1279, 1879) }
        launched { fragment ->
            idle()
            val shown = (fragment.image().drawable as BitmapDrawable).bitmap

            assertThat(shadowOf(shown).createdFromBitmap).isNull()
            assertThat(shown.width to shown.height).isEqualTo(639 to 939)
            assertThat(shown.byteCount).isEqualTo(2_400_084)

            val peak = shown.byteCount.toLong()
            assertThat(peak).isEqualTo(2_400_084L)
            val ratio = 12_015_604.0 / peak
            assertThat(ratio).isGreaterThan(5.0)
            assertThat(ratio).isLessThan(5.01)
        }
    }

    /**
     * [ZoomImageView] restores its zoom only if the view has an `android:id` and nothing else
     * (placeholder, error icon) is ever put into it. The first recreation carries a zoom applied to
     * a finished image; the second happens while the new fragment's load is still parked.
     */
    @Test
    fun theZoomSurvivesTwoRecreationsWhileTheImageIsStillLoading() {
        val parked = ParkingDispatcher()
        installLoader(ioDispatcher = parked) { TestImages.png(400, 400) }
        val scenario = launch()

        scenario.onFragment { fragment ->
            parked.release()
            idle()
            fragment.layOutTheImage()
            // Asks for four; this image earns a ceiling of two, and two is what it gets.
            fragment.image().zoomBy(4f, 200f, 200f)
            assertThat(fragment.image().state.scale).isEqualTo(2f)
        }

        scenario.recreate()
        scenario.onFragment { fragment ->
            assertThat(fragment.image().drawable).isNull()
            fragment.layOutTheImage()
        }

        scenario.recreate()
        scenario.onFragment { fragment ->
            assertThat(fragment.image().drawable).isNull()
            assertThat(fragment.progress().visibility).isEqualTo(View.VISIBLE)
            fragment.layOutTheImage()

            parked.release()
            idle()

            assertThat(fragment.image().drawable).isNotNull()
            assertThat(fragment.image().state.scale).isEqualTo(2f)
        }
        scenario.close()
    }

    /**
     * Every outcome that is not a bitmap ends the same way: no spinner, a message, no share. The
     * set is iterated, so a new [ImageError] or [ImageResult] fails this test until handled.
     */
    @Test
    fun everyOutcomeThatIsNotAReadyBitmapEndsAsAVisibleFailure() {
        // `PermittedSubclasses` rather than `KClass.sealedSubclasses`, which needs kotlin-reflect.
        assertThat(ImageResult::class.java.permittedSubclasses!!.map { it.simpleName })
            .containsExactly("Ready", "Failed", "Skipped")
        val outcomes: List<ImageResult> =
            ImageError.entries.map { ImageResult.Failed(it) } + ImageResult.Skipped
        assertThat(outcomes).hasSize(7)

        for (outcome in outcomes) {
            val mocked = mockk<ChatImageLoader>()
            // This test is about the decode result, so the byte fetch always succeeds.
            coEvery { mocked.fetchBytes(any()) } returns TestImages.png(4, 4)
            coEvery { mocked.loadFull(any(), any(), any()) } returns outcome
            ChatImageLoaders.setForTests(mocked)
            launched { fragment ->
                idle()
                assertThat(fragment.progress().visibility).isEqualTo(View.GONE)
                assertThat(fragment.status().visibility).isEqualTo(View.VISIBLE)
                assertThat(fragment.status().text.toString())
                    .isEqualTo(fragment.getString(R.string.chat_image_load_failed))
                assertThat(fragment.share().isEnabled).isFalse()
                assertThat(fragment.image().drawable).isNull()
            }
        }
    }

    /**
     * Two quick taps must not start two exports: both would write the same file on two IO threads
     * while the first chooser already hands out the URI. The export is parked so the second tap
     * lands inside the first write.
     */
    @Test
    fun aSecondTapWhileTheFirstShareIsStillWritingIsRefused() {
        val exporting = ParkingDispatcher()
        installLoader { TestImages.png(40, 40) }
        launched { fragment ->
            fragment.ioDispatcher = exporting
            idle()

            fragment.tap(R.id.image_viewer_share)
            assertThat(fragment.share().isEnabled).isFalse()
            fragment.tap(R.id.image_viewer_share)

            exporting.release()
            idle()

            val activity = fragment.requireActivity()
            assertThat(shadowOf(activity).nextStartedActivity).isNotNull()
            assertThat(shadowOf(activity).nextStartedActivity).isNull()
        }
    }

    /**
     * The share hands out the bytes this dialog decoded; a second fetch could return different
     * bytes (and a different type). The loader remembers one payload, so one row bound behind the
     * dialog displaces it.
     */
    @Test
    fun theSharedBytesAreTheOnesThatWereShown() {
        val shown = TestImages.png(40, 40)
        var served: ByteArray = shown
        installLoader { served }
        launched { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            // The server changes its answer and a row bound behind the dialog displaces the payload.
            served = "not an image at all".toByteArray()
            runBlocking { loader!!.fetchBytes(other) }

            fragment.tap(R.id.image_viewer_share)

            val send = sentIntent(fragment)
            assertThat(send.type).isEqualTo("image/png")
            assertThat(exportedFile(fragment, "$keyOfA.png").readBytes()).isEqualTo(shown)
        }
    }

    /** Sharing must not contact the host a second time (privacy: it re-announces the user's IP). */
    @Test
    fun theShareDoesNotGoBackToTheNetwork() {
        val fetched = mutableListOf<String>()
        installLoader { url -> fetched += url; TestImages.png(40, 40) }
        launched { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            runBlocking { loader!!.fetchBytes(other) }

            fragment.tap(R.id.image_viewer_share)

            assertThat(sentIntent(fragment).type).isEqualTo("image/png")
            assertThat(fetched.count { it == source }).isEqualTo(1)
        }
    }

    /**
     * Fullscreen is a property of the window: `android:windowIsFloating=false` in
     * `Theme.Mumla.ImageViewer` makes `PhoneWindow.generateLayout` use MATCH_PARENT.
     */
    @Test
    fun theViewerWindowFillsTheScreen() {
        installLoader { TestImages.png(8, 8) }
        launched { fragment ->
            idle()
            val attributes = fragment.dialog!!.window!!.attributes
            assertThat(attributes.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            assertThat(attributes.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    /** The black background is the window's only; a layout background would overdraw every frame. */
    @Test
    fun theBlackSurfaceIsTheWindowAndNotASecondLayerInTheLayout() {
        installLoader { TestImages.png(8, 8) }
        launched { fragment ->
            idle()
            val window = (fragment.dialog!!.window!!.decorView.background as ColorDrawable).color
            assertThat(window).isEqualTo(Color.BLACK)
            assertThat(fragment.requireView().background).isNull()
        }
    }

    /** A fragment rebuilt without its argument fails visibly and does not ask the loader. */
    @Test
    fun aViewerWithoutASourceFailsWithoutAskingTheLoader() {
        val asked = mutableListOf<String>()
        installLoader { url -> asked += url; TestImages.png(4, 4) }
        launched(source = null) { fragment ->
            idle()
            assertThat(fragment.progress().visibility).isEqualTo(View.GONE)
            assertThat(fragment.status().visibility).isEqualTo(View.VISIBLE)
            assertThat(fragment.share().isEnabled).isFalse()
            assertThat(asked).isEmpty()
        }
    }

    @Test
    fun theCloseButtonDismissesTheDialog() {
        installLoader { TestImages.png(4, 4) }
        launched { fragment ->
            idle()
            assertThat(fragment.dialog?.isShowing).isTrue()
            fragment.tap(R.id.image_viewer_close)
            assertThat(fragment.dialog?.isShowing ?: false).isFalse()
        }
    }

    /** The close button works while the load (which has no timeout) is still pending. */
    @Test
    fun theCloseButtonWorksWhileTheImageIsStillLoading() {
        val parked = ParkingDispatcher()
        installLoader(ioDispatcher = parked) { TestImages.png(8, 8) }
        launched { fragment ->
            idle()
            assertThat(fragment.progress().visibility).isEqualTo(View.VISIBLE)
            assertThat(fragment.dialog?.isShowing).isTrue()

            fragment.tap(R.id.image_viewer_close)

            assertThat(fragment.dialog?.isShowing ?: false).isFalse()
        }
    }

    /** A share whose write fails (a plain file where the staging directory should be). */
    @Test
    fun aShareThatCannotBeWrittenSaysSoAndStartsNothing() {
        installLoader { TestImages.png(4, 4) }
        launched { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            val blocking = File(fragment.requireContext().cacheDir, ImageShareExporter.DIRECTORY)
            blocking.deleteRecursively()
            blocking.writeBytes(ByteArray(1))

            fragment.tap(R.id.image_viewer_share)

            assertThat(ShadowToast.getTextOfLatestToast())
                .isEqualTo(fragment.getString(R.string.chat_image_load_failed))
            assertThat(shadowOf(fragment.requireActivity()).nextStartedActivity).isNull()
            // ...and the button comes back.
            assertThat(fragment.share().isEnabled).isTrue()
        }
    }
}
