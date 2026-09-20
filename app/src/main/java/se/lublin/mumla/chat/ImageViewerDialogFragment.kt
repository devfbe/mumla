package se.lublin.mumla.chat

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.mumla.R
import java.io.IOException

/**
 * Fullscreen viewer for one chat image; decodes for the screen size off the main thread.
 *
 * **Decoding at twice the screen** ([DECODE_SCALE]) is half of the zoom ceiling, the other half
 * being `ZoomState.maxScale`. `BitmapUtils.resizeKeepingAspect` never enlarges, so a decode bounded
 * by the screen produces a bitmap that is exactly view-sized on the limiting axis — a fit scale of
 * 1, and every zoom past the fit pure interpolation. Doubling the bound buys one doubling of real
 * detail at four times the pixels: 40.4 MB for a 1080x2340 phone, against the 128 MiB
 * `heapgrowthlimit` floor and the `maxMemory() / 8` thumbnail cache. Three times the screen would
 * be 91 MB and does not fit.
 *
 * **What the 40.4 MB does not include, and neither does the loader's own sum.** That is what the
 * decode *keeps*. `BoundedBitmapDecoder` samples by powers of two and then calls
 * `Bitmap.createScaledBitmap`, so an intermediate of up to four times the kept pixels is alive
 * across that call: a transient peak with a supremum of 5x — measured 5.0026, the excess being the
 * `toInt()` truncation in `resizeKeepingAspect` — i.e. **about 193 MiB at K=2 on a 1080x2340
 * phone**, against a 128 MiB floor. Neither `ChatImageLoader`'s class-level arithmetic nor the
 * paragraph above carries that term; `theTransientPeakOfADecodeIsFiveTimesTheBitmapItKeeps`
 * measures it. It is reachable with a flat 4320x9360 PNG of 136 303 bytes, a factor of 38 under the
 * fetch cap. Removing it is task 10's: round the sample size up instead of down and drop the
 * rescale on this path.
 *
 * **What this fragment borrows, and who unwinds it.** It keeps one field of its own ([ioDispatcher],
 * a test seam), which is not the interesting half:
 *  - `FragmentManager` owns this instance and its view. Both coroutines run in
 *    `viewLifecycleOwner.lifecycleScope`. **Scope of that claim:** what the teardown cancels is the
 *    *continuation*, not the effect — a dialog dismissed mid-share leaves `export()` running to the
 *    end on its IO thread and the file written; only `startActivity` is skipped. And
 *    `viewLifecycleOwner` rather than `this` is a distinction without an observable *for a
 *    `DialogFragment`*, whose view and instance are always destroyed together: the mutation to a
 *    plain `lifecycleScope` survives the suite, and it is written this way because a fragment that
 *    later gained a second view would not be. Do not read the scope as the thing that makes
 *    dismissal safe; that is the `finally` and the `Context` captured before the launch.
 *  - `ChatImageLoader` is process-wide and outlives every viewer. What it lends this screen:
 *    * the byte array from `fetchBytes`, handed out without a copy — read, never written, and held
 *      here for the life of the dialog so that the share cannot hand out anything else;
 *    * **not** the bitmap. `loadFull` caches nothing, so the up-to-40 MB bitmap in
 *      [ImageResult.Ready] belongs to the `ImageView` alone and dies with it. The sentence that
 *      said otherwise was copied from [ImageResult.Ready]'s KDoc, which describes `loadThumbnail`.
 *    Calling `fetchBytes` also **writes** loader state: it sets the one-entry `lastBytes` memo,
 *    which holds up to 5 MiB process-wide outside the cache budget and displaces whatever the
 *    thumbnail path had put there. It takes no permit from the loader's gate either, so this
 *    screen's fetch is a peak beside the three the gate counts.
 *  - `FileProvider` owns nothing but the per-URI grant on the intent below; the platform drops that
 *    when the receiving activity finishes. The *file* is owned by [ImageShareExporter], which is
 *    why that class prunes on every export — there is no other moment at which anything would, so a
 *    user who shares exactly once keeps that file until the system clears the cache.
 *  - The `ContentResolver` on the receiving side is the one that opens the file, and it does so
 *    after this fragment is gone. Nothing may be deleted on dismissal for that reason.
 *
 * **One throw is not caught, deliberately and with its scope.** `FileProvider.getUriForFile` raises
 * `IllegalArgumentException` for a file outside the published roots, which no `catch` here covers.
 * It is unreachable while `shared_image_paths.xml` publishes the directory [ImageShareExporter]
 * writes to — pinned from both sides by `theProviderPublishesNothingButTheShareDirectory` — and
 * narrowing that file would turn it into an uncaught throw on a user's tap. This is a note about
 * what depends on that XML, not a claim that nothing can throw.
 *
 * **Fullscreen is the theme's, and only the theme's.** `Theme.Mumla.ImageViewer` sets
 * `android:windowIsFloating=false`, which is what makes `PhoneWindow.generateLayout` give the
 * window `MATCH_PARENT x MATCH_PARENT` instead of `WRAP_CONTENT x WRAP_CONTENT`. There used to be a
 * `dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)` in `onStart` as well; it ran after
 * `generateLayout` and was measured to be a no-op in the shipped configuration **and a mask in the
 * broken one** -- with the theme item flipped to `true` the suite stayed green with that line and
 * goes red without it. Two guards on one observable, so the one that is also redundant is gone and
 * `theViewerWindowFillsTheScreen` pins what is left.
 *
 * **There is deliberately no timeout here**, and a future one would be theatre.
 * `ImageFetcher.fetch` blocks and never observes cancellation, so a `withTimeout` around the load
 * cannot end the wait -- measured on this classpath, `withTimeoutOrNull(200)` around a 2000 ms
 * blocking body returned `null` after **2054 ms**. It would change the value and not the spinner.
 * The only bound that works is the fetcher's own (`HttpImageFetcher.totalTimeoutMs`, 20 s), which
 * ends in [ImageError.TIMEOUT] and therefore in the failure message above. Name resolution is still
 * outside that bound; see the loader's ledger entry.
 */
class ImageViewerDialogFragment : DialogFragment() {

    /** Test seam: the dispatcher the share export runs on. Production code must not set this. */
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_Mumla_ImageViewer)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_image_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val image: ZoomImageView = view.findViewById(R.id.image_viewer_image)
        val progress: View = view.findViewById(R.id.image_viewer_progress)
        val status: TextView = view.findViewById(R.id.image_viewer_status)
        val share: View = view.findViewById(R.id.image_viewer_share)
        view.findViewById<View>(R.id.image_viewer_close).setOnClickListener { dismiss() }
        share.isEnabled = false

        /**
         * The one way this screen ends without a picture. Every outcome that is not a bitmap goes
         * through here rather than getting a branch of its own, so "no spinner, a message, no
         * share" is one mechanism with one mutation instead of one per cause. Nothing is ever put
         * *in* [image]: an error drawable with an intrinsic size would spend a restored zoom.
         */
        fun fail() {
            progress.visibility = View.GONE
            status.setText(R.string.chat_image_load_failed)
            status.visibility = View.VISIBLE
        }

        // A viewer without a source is not reachable through newInstance, but a missing argument
        // must not throw: it is the same outcome as a source the loader cannot make sense of, and
        // it is deliberately not a dismiss -- a dialog that vanishes by itself reads as a crash.
        val source = arguments?.getString(ARG_SOURCE)
        if (source == null) {
            fail()
            return
        }

        val metrics = resources.displayMetrics
        val loader = ChatImageLoaders.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            // Fetched here and not in the share path, so that what leaves the app is the array this
            // screen was decoded from. See [shareImage] for what that costs and what it does not buy.
            val bytes = try {
                loader.fetchBytes(source)
            } catch (e: ImageFetchException) {
                return@launch fail()
            }
            val result = loader.loadFull(
                source,
                metrics.widthPixels * DECODE_SCALE,
                metrics.heightPixels * DECODE_SCALE,
            )
            when (result) {
                is ImageResult.Ready -> {
                    progress.visibility = View.GONE
                    image.setImageBitmap(result.bitmap)
                    // The bytes ride in the listener rather than in a field of this fragment: there
                    // is then no state to be null, no guard for a share before the load, and the
                    // reference dies with the view that holds the listener.
                    share.setOnClickListener { shareImage(share, source, bytes) }
                    share.isEnabled = true
                }
                is ImageResult.Failed -> fail()
                ImageResult.Skipped -> fail()
            }
        }
    }

    /**
     * Writes [bytes] out and hands the receiver a grant for them. [bytes] are the array the picture
     * on screen was decoded from, carried here from the load rather than fetched again.
     *
     * **Why not fetch here.** A second `GET` re-announces the user's IP to a host a chat message
     * chose, on a tap that says "share"; and it is free to answer with something else, which
     * `ImageShareExporter.typeOf` would then re-type -- so the user sends bytes they never saw,
     * named by this app. Nothing downstream compares the two. Both pinned by
     * `theSharedBytesAreTheOnesThatWereShown` and `theShareDoesNotGoBackToTheNetwork`.
     *
     * **What that does not buy, stated with its scope.** The array is the one *this dialog* fetched.
     * `ChatImageLoader.loadFull` decodes from the loader's one-entry memo, which is this same array
     * unless another load displaced it in the window between the two calls -- a few dispatches wide,
     * and no longer the width of a decode. In that window the picture is re-fetched and can differ
     * from what is shared. Closing it needs `loadFull` to hand back the bytes it decoded, which is
     * task 10's file, not this one.
     *
     * **What it costs.** Up to `HttpImageFetcher.maxBytes` (5 MiB) stays reachable for the life of
     * the dialog instead of only for the life of the loader's memo. The allocation itself is not
     * new: `fetchBytes` already keeps it process-wide, outside the cache budget, and displaces
     * whatever the thumbnail path had put there.
     */
    private fun shareImage(share: View, source: String, bytes: ByteArray) {
        // The whole debounce. `share.setOnClickListener` has none of its own, and `export()` writes
        // its file unconditionally under a name derived from the source -- so two quick taps used to
        // run two writes of the *same path* on two IO threads, with the second truncating and
        // rewriting while the first one's chooser was already handing the URI out. Disabled for
        // exactly as long as one export is in flight and re-enabled in the `finally` however it
        // ended, because `View.onTouchEvent` refuses a disabled view and that is what makes this a
        // mechanism rather than a greyed-out picture. A tap *after* a share finished is a second
        // share and is allowed.
        share.isEnabled = false
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val exported = withContext(ioDispatcher) { ImageShareExporter(context).export(source, bytes) }
                // The URI has to travel as ClipData and not only as EXTRA_STREAM:
                // Intent.createChooser migrates FLAG_GRANT_READ_URI_PERMISSION to the chooser only
                // for the intent's data or its ClipData, and a chooser without the flag hands the
                // receiver a URI it may not open.
                //
                // The plan also added the flag to the chooser by hand. Measured, that is the *same*
                // guard twice: with the ClipData set, createChooser carries the flag over by
                // itself; with the flag set by hand, the platform's own
                // migrateExtraStreamToClipData fills the ClipData in at startActivity. Each alone
                // keeps `sharingStartsAChooserThatCanReadTheExportedFile` green and only removing
                // both turns it red, which is exactly the shape spec 4.04 calls one guard and a
                // lie. This is the half that is done before the intent leaves, so it stays.
                val send = Intent(Intent.ACTION_SEND)
                    .setType(exported.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, exported.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(null, exported.uri)
                startActivity(Intent.createChooser(send, getString(R.string.chat_image_share)))
            } catch (e: IOException) {
                Toast.makeText(context, R.string.chat_image_load_failed, Toast.LENGTH_SHORT).show()
            } finally {
                share.isEnabled = true
            }
        }
    }

    companion object {
        const val TAG = "image_viewer"

        /**
         * How many screens wide the decode is bounded at. See the class KDoc: it is the decoder half
         * of the zoom ceiling, and the number is a memory decision, not a taste one.
         */
        @VisibleForTesting
        internal const val DECODE_SCALE = 2

        @VisibleForTesting
        internal const val ARG_SOURCE = "source"

        fun newInstance(source: String): ImageViewerDialogFragment = ImageViewerDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, source) }
        }
    }
}
