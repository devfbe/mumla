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
import se.lublin.mumla.databinding.DialogImageViewerBinding
import java.io.IOException

/**
 * Fullscreen viewer for one chat image; decodes at most [DECODE_SCALE] times the screen, which
 * together with `ZoomState.maxScale` sets the zoom ceiling (three screens would not fit the heap).
 *
 * The bitmap from `decodeFull` is not cached and belongs to the `ImageView`; the bytes from
 * `fetchBytes` are held for the life of the dialog so the share hands out exactly what was shown.
 * Teardown cancels the coroutines' continuations, not a running export. The exported file must
 * survive dismissal: the receiving app opens it afterwards.
 *
 * `FileProvider.getUriForFile` throws for files outside `shared_image_paths.xml`, which must keep
 * publishing [ImageShareExporter]'s directory. Fullscreen comes from `Theme.Mumla.ImageViewer`
 * (`windowIsFloating=false`). No timeout here: the image HTTP client has its own total budget.
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
        DialogImageViewerBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = DialogImageViewerBinding.bind(view)
        val image: ZoomImageView = binding.imageViewerImage
        val progress: View = binding.imageViewerProgress
        val status: TextView = binding.imageViewerStatus
        val share: View = binding.imageViewerShare
        binding.imageViewerClose.setOnClickListener { dismiss() }
        share.isEnabled = false

        /**
         * Every outcome that is not a bitmap. Nothing is put in [image]: an error drawable with an
         * intrinsic size would spend a restored zoom.
         */
        fun fail() {
            progress.visibility = View.GONE
            status.setText(R.string.chat_image_load_failed)
            status.visibility = View.VISIBLE
        }

        // Unreachable via newInstance; fail rather than dismiss, which would read as a crash.
        val source = arguments?.getString(ARG_SOURCE)
        if (source == null) {
            fail()
            return
        }

        val metrics = resources.displayMetrics
        val loader = ChatImageLoaders.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            // Fetched here, not in the share path, so what leaves the app is what was shown.
            val bytes = try {
                loader.fetchBytes(source)
            } catch (e: ImageFetchException) {
                return@launch fail()
            }
            val result = loader.decodeFull(
                bytes,
                metrics.widthPixels * DECODE_SCALE,
                metrics.heightPixels * DECODE_SCALE,
            )
            when (result) {
                is ImageResult.Ready -> {
                    progress.visibility = View.GONE
                    image.setImageBitmap(result.bitmap)
                    // Captured by the listener, so the bytes die with the view.
                    share.setOnClickListener { shareImage(share, source, bytes) }
                    share.isEnabled = true
                }
                is ImageResult.Failed -> fail()
                ImageResult.Skipped -> fail()
            }
        }
    }

    /**
     * Writes [bytes] (the array the picture was decoded from) out and hands the receiver a grant.
     * Not re-fetched: a second `GET` would re-announce the user's IP to a stranger-chosen host and
     * could return different bytes than were shown.
     */
    private fun shareImage(share: View, source: String, bytes: ByteArray) {
        // Debounce: two quick taps would write the same file path concurrently.
        share.isEnabled = false
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val exported = withContext(ioDispatcher) { ImageShareExporter(context).export(source, bytes) }
                // ClipData, not only EXTRA_STREAM: createChooser migrates the read grant to the
                // chooser only for the intent's data or ClipData.
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

        /** How many screens wide the decode is bounded at; a memory decision, see the class KDoc. */
        @VisibleForTesting
        internal const val DECODE_SCALE = 2

        @VisibleForTesting
        internal const val ARG_SOURCE = "source"

        fun newInstance(source: String): ImageViewerDialogFragment = ImageViewerDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_SOURCE, source) }
        }
    }
}
