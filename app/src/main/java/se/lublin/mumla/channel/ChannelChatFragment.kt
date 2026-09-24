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
import android.util.Log
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.mumla.R
import se.lublin.mumla.app.ServiceClient
import se.lublin.mumla.app.ServiceViewModel
import se.lublin.mumla.app.bindClient
import se.lublin.mumla.chat.ChatAdapter
import se.lublin.mumla.chat.ChatContentParser
import se.lublin.mumla.chat.ChatImageLoaders
import se.lublin.mumla.chat.ImageViewerDialogFragment
import se.lublin.mumla.chat.OutgoingImageEncoder
import se.lublin.mumla.chat.OutgoingImagePreparer
import se.lublin.mumla.databinding.FragmentChatBinding
import se.lublin.mumla.service.IChatMessage
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HtmlUtils

/**
 * The chat tab: a [RecyclerView] of [IChatMessage]s plus the compose row. Parsing and rendering
 * live in [ChatAdapter], images in `ChatImageLoader`/[OutgoingImagePreparer]. [openImageViewer]
 * is the uniqueness gate `ChatAdapter.onImageClicked` requires, [sessionId] never throws, and the
 * adapter gets a `lifecycleScope` (`Dispatchers.Main.immediate`) because its coroutines touch views.
 */
class ChannelChatFragment : Fragment(), ServiceClient, MenuProvider {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private val service: IMumlaService? get() = serviceModel.service.value
    private var bound = false

    private val chatTargets by parentChatTargets()
    private lateinit var chatList: RecyclerView
    private lateinit var chatTextEdit: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var imageProgress: View

    /** The bound service, or null between an unbind and the next bind (the list then stays as it is). */
    private val boundService = MutableStateFlow<IMumlaService?>(null)

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
            Toast.makeText(requireContext(), R.string.permission_denied_storage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onServiceEvent(event: HumlaEvent) {
        if (event !is HumlaEvent.UserJoinedChannel) return
        val session = service?.takeIf { it.isConnected }?.session ?: return
        if (event.user == session.sessionUser && chatTargets.target.value == null) {
            // The user changed channels without a target: follow them.
            updateChatTargetText(null)
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

        updateChatTargetText(chatTargets.target.value)
        viewLifecycleOwner.lifecycleScope.launch {
            // While resumed, as the target changes; catching up on resumption.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                chatTargets.target.collect(::updateChatTargetText)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                boundService.collectLatest { service ->
                    service?.messageLog?.collect { submit(adapter, it) }
                }
            }
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        if (!bound) {
            bound = true
            serviceModel.bindClient(this, this)
        }
    }

    override fun onDestroyView() {
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

    /** Empties the service's chat log, and with it the list. */
    fun clear() {
        service?.clearMessageLog()
    }

    override fun onServiceBound(service: IMumlaService) {
        boundService.value = service
        // onCreateView may have run before the service was bound, so set the hint here too.
        updateChatTargetText(chatTargets.target.value)
    }

    override fun onServiceUnbound() {
        boundService.value = null
    }

    /** Updates the compose hint that shows where the next message goes. */
    fun updateChatTargetText(target: ChatTarget?) {
        if (!this::chatTextEdit.isInitialized) return
        val session = service?.takeIf { it.isConnected }?.session ?: return
        val sessionChannel = session.sessionChannel
        val hint = when (target) {
            is ChatTarget.User -> getString(R.string.messageToUser, target.name)
            is ChatTarget.Channel -> getString(R.string.messageToChannel, target.name)
            null -> sessionChannel?.let { getString(R.string.messageToChannel, it.name) }
        }
        chatTextEdit.hint = hint
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
    internal fun openImageViewer(source: String) {
        val fm = parentFragmentManager
        if (fm.findFragmentByTag(ImageViewerDialogFragment.TAG) != null) return
        ImageViewerDialogFragment.newInstance(source).showNow(fm, ImageViewerDialogFragment.TAG)
    }

    /** Shows [messages] and scrolls to the newest one. */
    private suspend fun submit(adapter: ChatAdapter, messages: List<IChatMessage>) {
        adapter.submitMessages(messages)
        chatList.post { if (adapter.itemCount > 0) chatList.scrollToPosition(adapter.itemCount - 1) }
    }

    /**
     * The local session id, or [NO_SESSION] when there is none. Must not throw (the adapter calls
     * this on every bind, also after a disconnect).
     */
    @VisibleForTesting
    internal fun sessionId(): Int = try {
        service?.takeIf { it.isConnected }?.session?.sessionId ?: NO_SESSION
    } catch (e: HumlaDisconnectedException) {
        NO_SESSION
    }

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
    internal fun onImagePicked(uri: Uri) {
        val service = service ?: return
        if (!service.isConnected) return
        imageProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = try {
                OutgoingImagePreparer(requireContext()).prepare(uri)
            } finally {
                imageProgress.visibility = View.GONE
            }
            if (bitmap == null) {
                Toast.makeText(requireContext(), R.string.image_decode_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            confirmImage(bitmap)
        }
    }

    @VisibleForTesting
    internal fun confirmImage(bitmap: Bitmap) {
        val preview = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = resources.displayMetrics.heightPixels / 3
            contentDescription = getString(R.string.image_confirm_send)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.image_confirm_send)
            .setView(preview)
            .setPositiveButton(android.R.string.ok) { _, _ -> sendImage(bitmap) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Encodes and sends a confirmed image. The service is fetched again because the session can be
     * gone by the time the confirmation dialog is dismissed.
     */
    @VisibleForTesting
    internal fun sendImage(bitmap: Bitmap) {
        val service = service ?: return
        imageProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val html = try {
                // Without the server's limit there is nothing to fit the image to.
                val maxLength = service.session.serverSettings?.imageMessageLength
                if (maxLength == null) null
                else withContext(Dispatchers.Default) { OutgoingImageEncoder.encode(bitmap, maxLength) }
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "disconnected while encoding an image: $e")
                null
            } finally {
                imageProgress.visibility = View.GONE
            }
            if (html == null) {
                Toast.makeText(requireContext(), R.string.image_too_large, Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                sendMessage(html)
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception from sendMessage: $e")
            }
        }
    }

    private fun sendMessageFromEditor() {
        if (chatTextEdit.length() == 0) return
        try {
            sendMessage(chatTextEdit.text.toString())
            chatTextEdit.setText("")
        } catch (e: HumlaDisconnectedException) {
            Log.d(TAG, "exception from sendMessage: $e")
        }
    }

    @Throws(HumlaDisconnectedException::class)
    private fun sendMessage(message: String) {
        val service = service
        if (service == null) {
            Log.d(TAG, "service==null in sendMessage")
            return
        }
        val session = service.session
        val formatted = HtmlUtils.markupOutgoingMessage(message)
        // The service adds the sent message to its log, which the list shows.
        when (val target = chatTargets.target.value) {
            is ChatTarget.User -> session.sendUserTextMessage(target.user.session, formatted)
            is ChatTarget.Channel -> session.sendChannelTextMessage(target.channel.id, formatted, false)
            null -> session.sessionChannel?.let { session.sendChannelTextMessage(it.id, formatted, false) }
        }
    }

    private companion object {
        val TAG: String = ChannelChatFragment::class.java.name
        const val IMAGE_MIME = "image/*"

        /**
         * What [sessionId] answers with when there is no session. Not -1: `Message(String)` uses
         * -1 as its actor, which would render actorless messages as our own. Mumble session ids are
         * unsigned, so `Int.MIN_VALUE` never collides.
         */
        const val NO_SESSION = Int.MIN_VALUE
    }
}
