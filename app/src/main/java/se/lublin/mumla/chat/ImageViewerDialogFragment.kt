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
 * **What this fragment borrows, and who unwinds it.** It keeps one field of its own ([ioDispatcher],
 * a test seam), which is not the interesting half:
 *  - `FragmentManager` owns this instance and its view. The load runs in
 *    `viewLifecycleOwner.lifecycleScope`, so the manager's own teardown cancels it; nothing here
 *    holds a view past `onDestroyView`, and the only `Context` the share path uses is captured
 *    before the coroutine starts rather than fetched inside it.
 *  - `ChatImageLoader` is process-wide and outlives every viewer. It owns the bitmap handed over in
 *    [ImageResult.Ready] and the byte array handed out by `fetchBytes` — neither is copied, so both
 *    are read and never recycled or written here. It also keeps *one* fetched payload, which is why
 *    the share path can have to fetch a second time.
 *  - `FileProvider` owns nothing but the per-URI grant on the intent below; the platform drops that
 *    when the receiving activity finishes. The *file* is owned by [ImageShareExporter], which is
 *    why that class prunes on every export — there is no other moment at which anything would.
 *  - The `ContentResolver` on the receiving side is the one that opens the file, and it does so
 *    after this fragment is gone. Nothing may be deleted on dismissal for that reason.
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
        share.setOnClickListener { shareImage(source) }

        val metrics = resources.displayMetrics
        viewLifecycleOwner.lifecycleScope.launch {
            val result = ChatImageLoaders.get(requireContext()).loadFull(
                source,
                metrics.widthPixels * DECODE_SCALE,
                metrics.heightPixels * DECODE_SCALE,
            )
            when (result) {
                is ImageResult.Ready -> {
                    progress.visibility = View.GONE
                    image.setImageBitmap(result.bitmap)
                    share.isEnabled = true
                }
                is ImageResult.Failed -> fail()
                ImageResult.Skipped -> fail()
            }
        }
    }

    /**
     * The loader remembers exactly one payload, so this usually costs nothing — but a row bound
     * behind the dialog displaces it and then this really does fetch again, which is why the
     * failure path is not decoration. Note that a direct `fetchBytes` takes **no permit** from the
     * loader's concurrency gate: a share running beside three loads is a fourth concurrent peak.
     */
    private fun shareImage(source: String) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val exported = try {
                withContext(ioDispatcher) {
                    val bytes = ChatImageLoaders.get(context).fetchBytes(source)
                    ImageShareExporter(context).export(source, bytes)
                }
            } catch (e: ImageFetchException) {
                Toast.makeText(context, R.string.chat_image_load_failed, Toast.LENGTH_SHORT).show()
                return@launch
            } catch (e: IOException) {
                Toast.makeText(context, R.string.chat_image_load_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // The URI has to travel as ClipData and not only as EXTRA_STREAM: Intent.createChooser
            // migrates FLAG_GRANT_READ_URI_PERMISSION to the chooser only for the intent's data or
            // its ClipData, and a chooser without the flag hands the receiver a URI it may not open.
            //
            // The plan also added the flag to the chooser by hand. Measured, that is the *same*
            // guard twice: with the ClipData set, createChooser carries the flag over by itself;
            // with the flag set by hand, the platform's own migrateExtraStreamToClipData fills the
            // ClipData in at startActivity. Each alone keeps
            // `sharingStartsAChooserThatCanReadTheExportedFile` green and only removing both turns
            // it red, which is exactly the shape spec 4.04 calls one guard and a lie. This is the
            // half that is done before the intent leaves, so it is the half that stays.
            val send = Intent(Intent.ACTION_SEND)
                .setType(exported.mimeType)
                .putExtra(Intent.EXTRA_STREAM, exported.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, exported.uri)
            startActivity(Intent.createChooser(send, getString(R.string.chat_image_share)))
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
