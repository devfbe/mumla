/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.channel

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.mumla.R
import se.lublin.mumla.chat.ChatAdapter
import se.lublin.mumla.chat.ChatContentParser
import se.lublin.mumla.chat.ChatImageLoaders
import se.lublin.mumla.chat.IChatMessage
import se.lublin.mumla.chat.ImageFetchException
import se.lublin.mumla.chat.ImageGallerySaver
import se.lublin.mumla.chat.ImageShareExporter
import se.lublin.mumla.chat.ImageSource
import se.lublin.mumla.chat.ImageViewerDialogFragment
import se.lublin.mumla.chat.shareChooser
import se.lublin.mumla.chat.viewSaved
import se.lublin.mumla.databinding.FragmentChatBinding
import se.lublin.mumla.ui.showPermissionDeniedSnackbar
import se.lublin.mumla.ui.showSnackbar
import java.io.IOException

/** The image preview takes at most a third of the screen height. */
private const val PREVIEW_SCREEN_FRACTION = 3

/**
 * The chat tab: a [RecyclerView] of [IChatMessage]s plus the compose row, over the parent's
 * [ChatViewModel]. Parsing and rendering live in [ChatAdapter]. [openImageViewer] is the uniqueness
 * gate `ChatAdapter.onImageClicked` requires, and the adapter gets a `lifecycleScope`
 * (`Dispatchers.Main.immediate`) because its coroutines touch views.
 * [showImageMenu] answers a long press on a picture with save and share, one action at a time; a
 * remote picture is saved or shared from the viewer, on the bytes it shows.
 */
class ChannelChatFragment : Fragment(), MenuProvider {

    private val chat by parentChatViewModel()
    private lateinit var chatList: RecyclerView
    private lateinit var chatTextEdit: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var imageProgress: View

    private val imagePicker = registerForActivityResult(GetContent(), ::onImagePickResult)

    private val readPermissionRequester = registerForActivityResult(RequestPermission(), ::onReadPermissionResult)

    /**
     * Named method references so tests can reach the bodies a framework-driven
     * `ActivityResultLauncher` otherwise hides. Cancelling the picker arrives as a null uri.
     */
    @VisibleForTesting
    internal fun onImagePickResult(uri: Uri?) {
        if (uri != null) onImagePicked(uri)
    }

    @VisibleForTesting
    internal fun onReadPermissionResult(granted: Boolean) {
        if (granted) {
            imagePicker.launch(IMAGE_MIME)
        } else {
            requireActivity().showPermissionDeniedSnackbar(R.string.permission_denied_storage)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentChatBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentChatBinding.bind(view)
        chatList = binding.chatList
        imageProgress = binding.chatImageProgress
        chatTextEdit = binding.chatTextEdit
        sendButton = binding.chatTextSend

        chatList.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        val adapter = ChatAdapter(
            parser = ChatContentParser(getString(R.string.chat_image_placeholder)),
            loader = ChatImageLoaders.get(requireContext()),
            thumbnailPx = resources.getDimensionPixelSize(R.dimen.chat_thumbnail_max),
            selfSessionId = ::sessionId,
            onImageClicked = ::openImageViewer,
            onImageLongPressed = ::showImageMenu,
            scope = viewLifecycleOwner.lifecycleScope,
        )
        chatList.adapter = adapter

        binding.chatImageSend.setOnClickListener { pickImage() }
        sendButton.setOnClickListener { sendMessageFromEditor() }

        chatTextEdit.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_NULL && event != null && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                sendMessageFromEditor()
                true
            } else {
                false
            }
        }
        chatTextEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton.isEnabled = chatTextEdit.text.isNotEmpty()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // android:enabled does not apply to an ImageButton, and the watcher only fires on change.
        sendButton.isEnabled = chatTextEdit.text.isNotEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            // While resumed, as the destination changes; catching up on resumption.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                chat.destination.collect(::updateChatTargetText)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { chat.imageBusy.collect { imageProgress.isVisible = it } }
                launch { chat.imageEvents.collect(::onImageEvent) }
                chat.messages.collect { submit(adapter, it) }
            }
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        chat.setShown(true)
    }

    override fun onPause() {
        chat.setShown(false)
        super.onPause()
    }

    override fun onDestroyView() {
        // An activity window: replacing the fragment (a disconnect does) would leave it on screen.
        imageMenu?.dismiss()
        imageMenu = null
        chatList.adapter = null
        super.onDestroyView()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_chat, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_clear_chat) return false
        clear()
        return true
    }

    /** Empties the session's chat log, and with it the list, offering to undo it. */
    fun clear() {
        val undo = chat.clear()
        showSnackbar(R.string.chat_cleared, R.string.undo) { undo() }
    }

    /** Updates the compose hint that shows where the next message goes. */
    private fun updateChatTargetText(target: ChatTarget?) {
        chatTextEdit.hint = when (target) {
            is ChatTarget.User -> getString(R.string.messageToUser, target.name)
            is ChatTarget.Channel -> getString(R.string.messageToChannel, target.name)
            null -> null
        }
        chatTextEdit.requestLayout() // Needed to update bounds after a hint change.
    }

    /**
     * Opens the fullscreen viewer on [source], unless one is already open.
     *
     * The tag alone does not prevent duplicates (`show` is a plain `add`), and `show` commits
     * asynchronously, so a lookup would miss a viewer shown in the same dispatch. `showNow`
     * commits synchronously, which makes the lookup reliable. Two viewers would export to the same
     * share path at once.
     */
    @VisibleForTesting
    internal fun openImageViewer(source: String, action: ImageViewerDialogFragment.Action? = null) {
        val fm = parentFragmentManager
        if (fm.findFragmentByTag(ImageViewerDialogFragment.TAG) != null) return
        ImageViewerDialogFragment.newInstance(source, action).showNow(fm, ImageViewerDialogFragment.TAG)
    }

    /** Test seam: the dispatcher image saves and share exports run on. Production code must not set this. */
    @VisibleForTesting
    internal var imageIoDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** The running save or share from the log; one at a time (debounce, and one writer per share path). */
    private var imageAction: Job? = null

    /** The open long-press menu, dismissed with the view. */
    private var imageMenu: PopupMenu? = null

    /** The long-press menu of a picture in the log, anchored to it. */
    @VisibleForTesting
    internal fun showImageMenu(source: String, anchor: View) {
        imageMenu?.dismiss()
        // android.widget.PopupMenu: Robolectric shadows only this one (ShadowPopupMenu).
        imageMenu = PopupMenu(anchor.context, anchor).apply {
            inflate(R.menu.popup_chat_image)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_chat_image_save -> { saveImage(source); true }
                    R.id.menu_chat_image_share -> { shareImage(source); true }
                    else -> false
                }
            }
            setOnDismissListener { if (imageMenu === it) imageMenu = null }
            show()
        }
    }

    /**
     * Saves the picture at [source] to the gallery. The log holds no original bytes (thumbnails
     * are scaled bitmaps). An inline picture's are in its source, so they are decoded and saved
     * here. A remote one goes to the viewer, which saves the bytes it shows: a `GET` of its own
     * here could be answered with a picture nobody saw. Ignored once the view is gone (a menu
     * choice can still be on its way).
     */
    @VisibleForTesting
    internal fun saveImage(source: String) {
        if (view == null || imageAction?.isActive == true) return
        if (!ImageSource.isInline(source)) return openImageViewer(source, ImageViewerDialogFragment.Action.SAVE)
        val context = requireContext()
        val loader = ChatImageLoaders.get(context)
        imageAction = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = loader.fetchBytes(source)
                val saved = withContext(imageIoDispatcher) { ImageGallerySaver(context).save(bytes) }
                showSnackbar(R.string.chat_image_saved, R.string.chat_image_view) { context.viewSaved(saved) }
            } catch (_: ImageFetchException) {
                showSnackbar(R.string.chat_image_save_failed)
            } catch (_: IOException) {
                showSnackbar(R.string.chat_image_save_failed)
            }
        }
    }

    /** Shares the picture at [source]; inline here, remote through the viewer, as for [saveImage]. */
    @VisibleForTesting
    internal fun shareImage(source: String) {
        if (view == null || imageAction?.isActive == true) return
        if (!ImageSource.isInline(source)) return openImageViewer(source, ImageViewerDialogFragment.Action.SHARE)
        val context = requireContext()
        val loader = ChatImageLoaders.get(context)
        imageAction = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = loader.fetchBytes(source)
                val exported = withContext(imageIoDispatcher) { ImageShareExporter(context).export(source, bytes) }
                startActivity(shareChooser(context, exported))
            } catch (_: ImageFetchException) {
                showSnackbar(R.string.chat_image_load_failed)
            } catch (_: IOException) {
                showSnackbar(R.string.chat_image_load_failed)
            }
        }
    }

    /** Shows [messages] and scrolls to the newest one. */
    private suspend fun submit(adapter: ChatAdapter, messages: List<IChatMessage>) {
        adapter.submitMessages(messages)
        chatList.post { if (adapter.itemCount > 0) chatList.scrollToPosition(adapter.itemCount - 1) }
    }

    /** Our session id, or [ChatViewModel.NO_SESSION]; the adapter asks on every bind. */
    @VisibleForTesting
    internal fun sessionId(): Int = chat.selfSession

    private fun pickImage() {
        // Android 12L and below need the storage permission for the picker.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            readPermissionRequester.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            imagePicker.launch(IMAGE_MIME)
        }
    }

    @VisibleForTesting
    internal fun onImagePicked(uri: Uri) = chat.prepareImage(uri)

    private fun onImageEvent(event: ChatViewModel.ImageEvent) {
        when (event) {
            is ChatViewModel.ImageEvent.Confirm -> confirmImage(event.bitmap)
            ChatViewModel.ImageEvent.Unreadable -> showSnackbar(R.string.image_decode_failed)
            ChatViewModel.ImageEvent.TooLarge -> showSnackbar(R.string.image_too_large)
        }
    }

    @VisibleForTesting
    internal fun confirmImage(bitmap: Bitmap) {
        val preview = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = resources.displayMetrics.heightPixels / PREVIEW_SCREEN_FRACTION
            contentDescription = getString(R.string.image_confirm_send)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.image_confirm_send)
            .setView(preview)
            .setPositiveButton(android.R.string.ok) { _, _ -> sendImage(bitmap) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Sends a confirmed image; the session may be gone by the time the dialog is dismissed. */
    @VisibleForTesting
    internal fun sendImage(bitmap: Bitmap) = chat.sendImage(bitmap)

    private fun sendMessageFromEditor() {
        if (chatTextEdit.length() == 0) return
        if (chat.send(chatTextEdit.text.toString())) chatTextEdit.setText("")
    }

    private companion object {
        const val IMAGE_MIME = "image/*"
    }
}
