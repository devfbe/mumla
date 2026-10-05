package se.lublin.mumla.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.mumla.R
import se.lublin.mumla.databinding.DialogImageViewerBinding
import se.lublin.mumla.ui.showSnackbar
import se.lublin.mumla.util.Edge
import se.lublin.mumla.util.padForSystemBars
import java.io.IOException

/**
 * Fullscreen viewer for one chat image; decodes at most [DECODE_SCALE] times the screen, which
 * together with `ZoomState.maxScale` sets the zoom ceiling (three screens would not fit the heap).
 *
 * The bitmap from `decodeFull` is not cached and belongs to the `ImageView`; the bytes from
 * `fetchBytes` are held for the life of the dialog so share and save hand out exactly what was
 * shown. Teardown cancels the coroutines' continuations, not a running export. The exported file
 * must survive dismissal: the receiving app opens it afterwards. The save goes through MediaStore
 * ([ImageGallerySaver]), needs no permission and does not use the FileProvider.
 *
 * `FileProvider.getUriForFile` throws for files outside `file_provider_paths.xml`, which must keep
 * publishing [ImageShareExporter]'s directory. Fullscreen comes from `Theme.Mumla.ImageViewer`
 * (`windowIsFloating=false`). No timeout here: the image HTTP client has its own total budget.
 *
 * An [Action] in the arguments (a save or share picked from the chat log) runs once the picture is
 * shown, on its bytes, and is then removed from the arguments so a recreated viewer does not run it
 * again.
 */
class ImageViewerDialogFragment : DialogFragment() {

    /** Test seam: the dispatcher the share export and gallery save run on. Production code must not set this. */
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.Theme_Mumla_ImageViewer)
    }

    override fun onStart() {
        super.onStart()
        // Behind the bars; the status bar hides as with a fullscreen window, until swiped in.
        val window = dialog?.window ?: return
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
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
        val save: View = binding.imageViewerSave
        binding.imageViewerClose.setOnClickListener { dismiss() }
        binding.imageViewerActions.padForSystemBars(Edge.TOP, Edge.END)
        share.isEnabled = false
        save.isEnabled = false

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
                    // The decoder reads a few types (WBMP) the gallery would not be given.
                    val savable = ImageGallerySaver.canSave(bytes)
                    save.setOnClickListener { saveImage(save, bytes) }
                    save.isEnabled = savable
                    when (takeAction()) {
                        Action.SAVE -> if (savable) {
                            saveImage(save, bytes)
                        } else {
                            showSnackbar(view, getString(R.string.chat_image_save_failed))
                        }
                        Action.SHARE -> shareImage(share, source, bytes)
                        null -> Unit
                    }
                }
                is ImageResult.Failed -> fail()
                ImageResult.Skipped -> fail()
            }
        }
    }

    /** The pending [Action], removed as it is read. */
    private fun takeAction(): Action? {
        val name = arguments?.getString(ARG_ACTION)
        arguments?.remove(ARG_ACTION)
        return Action.entries.firstOrNull { it.name == name }
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
                startActivity(shareChooser(context, exported))
            } catch (e: IOException) {
                showSnackbar(requireView(), getString(R.string.chat_image_load_failed))
            } finally {
                share.isEnabled = true
            }
        }
    }

    /**
     * Stores [bytes] (the array the picture was decoded from) in the gallery. Not re-fetched, as
     * for [shareImage]: a second `GET` would re-announce the user's IP to a stranger-chosen host and
     * could return different bytes than were shown. Debounced apart from share: they never write
     * to the same target.
     */
    private fun saveImage(save: View, bytes: ByteArray) {
        // Debounce: two quick taps would save two copies.
        save.isEnabled = false
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(ioDispatcher) { ImageGallerySaver(context).save(bytes) }
                showSnackbar(requireView(), getString(R.string.chat_image_saved), R.string.chat_image_view) {
                    context.viewSaved(saved)
                }
            } catch (_: IOException) {
                showSnackbar(requireView(), getString(R.string.chat_image_save_failed))
            } finally {
                save.isEnabled = true
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

        @VisibleForTesting
        internal const val ARG_ACTION = "action"

        fun newInstance(source: String, action: Action? = null): ImageViewerDialogFragment =
            ImageViewerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE, source)
                    if (action != null) putString(ARG_ACTION, action.name)
                }
            }
    }

    /** What to do with the picture as soon as it is shown. */
    enum class Action { SAVE, SHARE }
}
