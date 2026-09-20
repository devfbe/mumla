package se.lublin.mumla.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
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

    /** Runs nothing until it is told to; see `theZoomSurvivesTwoRecreationsWhileTheImageIsStillLoading`. */
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

    private fun ImageViewerDialogFragment.image(): ZoomImageView =
        requireView().findViewById(R.id.image_viewer_image)

    private fun ImageViewerDialogFragment.progress(): View =
        requireView().findViewById(R.id.image_viewer_progress)

    private fun ImageViewerDialogFragment.status(): TextView =
        requireView().findViewById(R.id.image_viewer_status)

    private fun ImageViewerDialogFragment.share(): View =
        requireView().findViewById(R.id.image_viewer_share)

    /** Robolectric never runs a layout pass for the dialog window, so the view is sized by hand. */
    private fun ImageViewerDialogFragment.layOutTheImage(side: Int = 400) {
        image().layout(0, 0, side, side)
    }

    /**
     * The arguments go through `fragmentArgs`, **not** through the instantiating lambda, and that is
     * not a style choice. `FragmentScenario` calls `fragment.setArguments(fragmentArgs)` on whatever
     * the lambda returned, so the plan's `launchFragment { newInstance(source) }` overwrites the
     * bundle `newInstance` had just put there with `null` -- a viewer with no source, silently. The
     * bundle is still built by [ImageViewerDialogFragment.newInstance], so that stays covered.
     */
    private fun launch(source: String? = this.source): FragmentScenario<ImageViewerDialogFragment> =
        launchFragment(fragmentArgs = source?.let { ImageViewerDialogFragment.newInstance(it).arguments }) {
            ImageViewerDialogFragment()
        }

    // --- the brief's three tests ----------------------------------------------------------------

    @Test
    fun aLoadedImageIsShownAndCanBeShared() {
        installLoader { TestImages.png(100, 50) }
        launch().onFragment { fragment ->
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
        launch().onFragment { fragment ->
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
        launch().onFragment { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            fragment.share().performClick()
            idle()

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
        }
    }

    // --- Spec 4.1, first requirement: decode at K x screen, K = 2 -------------------------------

    /**
     * `BitmapUtils.resizeKeepingAspect` never enlarges, so whatever bound the viewer asks for is
     * exactly what the bitmap ends up being on the limiting axis. Asking for the screen would make
     * every zoom past the fit pure interpolation; asking for twice the screen buys one doubling of
     * real detail and costs four times the pixels, which is the largest factor that still fits
     * beside the thumbnail cache in the 128 MiB heap growth limit task 6 measured.
     *
     * The factor is what is pinned, not the pixel count: the expectation is built from the
     * environment's own metrics, so the same assertion holds under the second screen below.
     */
    @Test
    fun theImageIsDecodedAtTwiceTheScreenSoThereIsDetailToZoomInto() {
        installLoader { TestImages.png(2000, 2000) }
        launch().onFragment { fragment ->
            idle()
            val metrics = fragment.resources.displayMetrics
            assertThat(metrics.widthPixels).isEqualTo(320)
            assertThat(metrics.heightPixels).isEqualTo(470)
            val bitmap = (fragment.image().drawable as BitmapDrawable).bitmap
            assertThat(bitmap.width).isEqualTo(2 * metrics.widthPixels)
            assertThat(bitmap.height).isEqualTo(2 * metrics.widthPixels)
        }
    }

    /**
     * The same claim on a different screen. A suite that only ever runs Robolectric's default
     * 320x470 device pins one configuration, and a mutation that replaced the metrics with two
     * constants would survive it.
     */
    @Test
    @Config(qualifiers = "w480dp-h800dp-mdpi")
    fun theDecodeBoundFollowsTheScreenRatherThanAConstant() {
        installLoader { TestImages.png(2000, 2000) }
        launch().onFragment { fragment ->
            idle()
            val metrics = fragment.resources.displayMetrics
            assertThat(metrics.widthPixels).isEqualTo(480)
            assertThat(metrics.heightPixels).isEqualTo(800)
            val bitmap = (fragment.image().drawable as BitmapDrawable).bitmap
            assertThat(bitmap.width).isEqualTo(2 * metrics.widthPixels)
        }
    }

    /**
     * What the doubling *keeps* on the phone the spec picked the factor for: 1080x2340 at xxhdpi.
     * 2160x4680 ARGB_8888 is 40_435_200 B, which is the spec's number for K=2.
     */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun theDoubledDecodeKeepsFortyMegabytesOnAFullHdPhone() {
        installLoader { TestImages.png(2400, 5200) }
        launch().onFragment { fragment ->
            idle()
            val metrics = fragment.resources.displayMetrics
            assertThat(metrics.widthPixels).isEqualTo(1080)
            assertThat(metrics.heightPixels).isEqualTo(2340)

            val shown = (fragment.image().drawable as BitmapDrawable).bitmap
            assertThat(shown.width).isEqualTo(2160)
            assertThat(shown.height).isEqualTo(4680)
            assertThat(shown.byteCount).isEqualTo(40_435_200)
        }
    }

    /**
     * ...and what it *costs while it is being made*, which is the number the spec does not have.
     *
     * `BoundedBitmapDecoder` samples by powers of two and then calls `Bitmap.createScaledBitmap`,
     * so the intermediate and the result are both alive for the length of that call. Sampling stops
     * as soon as one more halving would undershoot the target, which leaves the intermediate
     * anywhere in [1x, 2x) of the target on each axis -- up to four times the pixels. A source
     * sized just above a halving hits that ceiling exactly, and the peak is then five times the
     * bitmap the viewer keeps.
     *
     * Measured here at the default 320x470 screen because a full-size reproduction would have to
     * build a 40-megapixel image in the test JVM. The factor does not depend on the size, so at
     * 1080x2340 and K=2 it reads: **5 x 40_435_200 B = 202_176_000 B, about 193 MiB**, against the
     * 128 MiB `heapgrowthlimit` floor task 6 measured. At K=1 the same worst case is 50_544_000 B,
     * about 48 MiB. Applying the spec's own criterion to the measured peak rather than to the
     * retained bitmap therefore does *not* yield K=2; see the task report. Nothing catches the
     * `OutOfMemoryError` on the way out, by design (task 5), so the failure mode is a crash.
     */
    @Test
    fun theTransientPeakOfADecodeIsFiveTimesTheBitmapItKeeps() {
        // 1279x1879 into the 640x940 box: one halving would undershoot, so nothing is sampled away.
        installLoader { TestImages.png(1279, 1879) }
        launch().onFragment { fragment ->
            idle()
            val shown = (fragment.image().drawable as BitmapDrawable).bitmap
            val intermediate = shadowOf(shown).createdFromBitmap!!

            assertThat(shown.width to shown.height).isEqualTo(639 to 940)
            assertThat(shown.byteCount).isEqualTo(2_402_640)
            assertThat(intermediate.width to intermediate.height).isEqualTo(1279 to 1879)
            assertThat(intermediate.byteCount).isEqualTo(9_612_964)

            val peak = intermediate.byteCount.toLong() + shown.byteCount
            assertThat(peak).isEqualTo(12_015_604L)
            assertThat(peak.toDouble() / shown.byteCount).isWithin(0.01).of(5.0)
        }
    }

    // --- Spec 4.1, second requirement: the two restore conditions --------------------------------

    /**
     * Pins both conditions [ZoomImageView]'s KDoc states and neither the plan nor the brief
     * mentions, in the suite of the screen that has to satisfy them:
     *
     *  1. **The view has an `android:id`.** Without one `View.dispatchSaveInstanceState` stores
     *     nothing at all, and the zoom is gone after the first rotation.
     *  2. **Nothing else is ever put in this view.** A placeholder or an error icon with an
     *     intrinsic size is a drawable like any other: it would spend the restored zoom, and the
     *     real image arriving afterwards would count as a second image and snap back to the fit.
     *     That is why the progress spinner and the failure message are separate views.
     *
     * Two recreations, not one, and both while the load is still parked -- the dialog loads over the
     * network, so a rotation before the image arrives is the ordinary case, and a second one inside
     * the same window is what catches a restore that saves live state instead of the pending one.
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
    }

    // --- the outcome set, not one member of it ---------------------------------------------------

    /**
     * Every outcome that is not a bitmap has to end the same way: no spinner, a message, no share.
     * The set is iterated rather than written out, so a seventh [ImageError] or a fourth
     * [ImageResult] fails this test until somebody decides what the viewer does with it -- which is
     * also how [ImageResult.Skipped] got a branch at all. The plan's `when` had two arms for a
     * sealed class with three members.
     */
    @Test
    fun everyOutcomeThatIsNotAReadyBitmapEndsAsAVisibleFailure() {
        // The JVM's own record of the sealed hierarchy (`PermittedSubclasses`), not
        // `KClass.sealedSubclasses`: the latter needs kotlin-reflect, which is on this classpath
        // only because mockk happens to drag it in.
        assertThat(ImageResult::class.java.permittedSubclasses!!.map { it.simpleName })
            .containsExactly("Ready", "Failed", "Skipped")
        val outcomes: List<ImageResult> =
            ImageError.entries.map { ImageResult.Failed(it) } + ImageResult.Skipped
        assertThat(outcomes).hasSize(7)

        for (outcome in outcomes) {
            val mocked = mockk<ChatImageLoader>()
            coEvery { mocked.loadFull(any(), any(), any()) } returns outcome
            ChatImageLoaders.setForTests(mocked)
            launch().onFragment { fragment ->
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

    // --- the window, which is the dimension "fullscreen" actually lives in -----------------------

    /**
     * The viewer is a *fullscreen* dialog, and "fullscreen" is a property of the **window**, not of
     * the layout: `dialog_image_viewer.xml` asking for `match_parent` only fills whatever the window
     * gives it. Four dimensions of this screen were swept -- the load outcomes, the arguments, the
     * display metrics and the dispatchers -- and this one was never entered at all, which is why the
     * line that used to sit in `onStart` could be deleted with the whole suite staying green.
     *
     * What holds the property up is `android:windowIsFloating=false` in `Theme.Mumla.ImageViewer`:
     * `PhoneWindow.generateLayout` reads it and calls `setLayout(MATCH_PARENT, MATCH_PARENT)` for a
     * non-floating window and `setLayout(WRAP_CONTENT, WRAP_CONTENT)` for a floating one. Measured
     * by mutating the theme item to `true`: this test then reads WRAP_CONTENT (-2).
     */
    @Test
    fun theViewerWindowFillsTheScreen() {
        installLoader { TestImages.png(8, 8) }
        launch().use { scenario ->
            scenario.onFragment { fragment ->
                idle()
                val attributes = fragment.dialog!!.window!!.attributes
                assertThat(attributes.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
                assertThat(attributes.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    }

    /**
     * The black is the window's, once. A `android:background` on the layout root would paint a
     * second full-screen layer over a window background that is already black -- lint's `Overdraw`,
     * and a real extra fill of every pixel on every frame while a 40 MB bitmap is being drawn over
     * it. This pins which of the two layers is the one that exists.
     */
    @Test
    fun theBlackSurfaceIsTheWindowAndNotASecondLayerInTheLayout() {
        installLoader { TestImages.png(8, 8) }
        launch().use { scenario ->
            scenario.onFragment { fragment ->
                idle()
                val window = (fragment.dialog!!.window!!.decorView.background as ColorDrawable).color
                assertThat(window).isEqualTo(Color.BLACK)
                assertThat(fragment.requireView().background).isNull()
            }
        }
    }

    // --- the paths nobody asked for ---------------------------------------------------------------

    /**
     * A fragment the system rebuilt without its argument must end like any other failure, and must
     * not ask the loader for a source it does not have. Not a self-dismissal: a dialog that
     * disappears by itself is indistinguishable from a crash.
     */
    @Test
    fun aViewerWithoutASourceFailsWithoutAskingTheLoader() {
        val asked = mutableListOf<String>()
        installLoader { url -> asked += url; TestImages.png(4, 4) }
        launch(source = null).onFragment { fragment ->
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
        launch().onFragment { fragment ->
            idle()
            assertThat(fragment.dialog?.isShowing).isTrue()
            fragment.requireView().findViewById<View>(R.id.image_viewer_close).performClick()
            idle()
            assertThat(fragment.dialog?.isShowing ?: false).isFalse()
        }
    }

    /**
     * The share re-fetches, and the re-fetch can fail: the loader remembers exactly one payload, so
     * any image bound behind the dialog displaces it. A message, not a crash, and nothing started.
     */
    @Test
    fun aShareWhoseRefetchFailsSaysSoAndStartsNothing() {
        var armed = false
        installLoader { url ->
            if (armed && url.endsWith("a.png")) throw ImageFetchException(ImageError.NETWORK)
            TestImages.png(4, 4)
        }
        launch().onFragment { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            // Displace the one remembered payload, exactly as a row binding behind the dialog would.
            runBlocking { loader!!.fetchBytes(other) }
            armed = true

            fragment.share().performClick()
            idle()

            assertThat(ShadowToast.getTextOfLatestToast())
                .isEqualTo(fragment.getString(R.string.chat_image_load_failed))
            assertThat(shadowOf(fragment.requireActivity()).nextStartedActivity).isNull()
        }
    }

    /**
     * The other half of the share's failure surface: the fetch worked and the *write* did not. A
     * plain file where the staging directory should be makes `mkdirs` fail and the write throw,
     * which is an `IOException` rather than an `ImageFetchException` -- two catch clauses, two
     * tests, neither of them able to cover for the other.
     */
    @Test
    fun aShareThatCannotBeWrittenSaysSoAndStartsNothing() {
        installLoader { TestImages.png(4, 4) }
        launch().onFragment { fragment ->
            fragment.ioDispatcher = Dispatchers.Unconfined
            idle()
            val blocking = File(fragment.requireContext().cacheDir, ImageShareExporter.DIRECTORY)
            blocking.deleteRecursively()
            blocking.writeBytes(ByteArray(1))

            fragment.share().performClick()
            idle()

            assertThat(ShadowToast.getTextOfLatestToast())
                .isEqualTo(fragment.getString(R.string.chat_image_load_failed))
            assertThat(shadowOf(fragment.requireActivity()).nextStartedActivity).isNull()
        }
    }
}
