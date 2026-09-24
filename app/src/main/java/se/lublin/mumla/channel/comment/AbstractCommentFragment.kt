/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.mumla.channel.comment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.EditText
import android.widget.TabHost
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import se.lublin.humla.IHumlaService
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.app.ServiceViewModel
import se.lublin.mumla.util.collectEvents
import se.lublin.mumla.util.configureForUntrustedHtml

/** Shows a comment as HTML and its source; the source can be edited if argument "editing" is set. */
abstract class AbstractCommentFragment : DialogFragment() {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private var commentView: WebView? = null
    private lateinit var commentEdit: EditText
    private var comment: String? = null

    /** Waits for the requested comment; see [observeComment]. */
    private var commentUpdates: Job? = null

    val isEditing: Boolean get() = requireArguments().getBoolean("editing")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        comment = requireArguments().getString("comment")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_comment, null, false)
        val commentView = view.findViewById<WebView>(R.id.comment_view).also { this.commentView = it }
        commentView.configureForUntrustedHtml()
        commentEdit = view.findViewById(R.id.comment_edit)

        val tabHost = view.findViewById<TabHost>(R.id.comment_tabhost)
        tabHost.setup()

        val known = comment
        if (known == null) {
            commentView.loadData("Loading...", null, null)
            serviceModel.service.value?.let(::requestComment)
        } else {
            loadComment(known)
        }

        val viewLabel = getString(R.string.comment_view)
        tabHost.addTab(tabHost.newTabSpec(TAB_VIEW).setIndicator(viewLabel).setContent(R.id.comment_tab_view))
        val editLabel = getString(if (isEditing) R.string.comment_edit_source else R.string.comment_view_source)
        tabHost.addTab(tabHost.newTabSpec(TAB_EDIT).setIndicator(editLabel).setContent(R.id.comment_tab_edit))
        tabHost.setOnTabChangedListener { tabId ->
            if (tabId == TAB_VIEW) {
                // Show the user's HTML changes.
                commentView.loadData(commentEdit.text.toString(), "text/html", "UTF-8")
            } else if (tabId == TAB_EDIT && commentEdit.text.isEmpty()) {
                // Filled on first view only, which is faster with long comments.
                commentEdit.setText(comment)
            }
        }
        tabHost.currentTab = if (isEditing) 1 else 0

        val builder = MaterialAlertDialogBuilder(requireActivity())
            .setView(view)
            .setNegativeButton(R.string.close, null)
        if (isEditing) {
            builder.setPositiveButton(R.string.save) { _, _ ->
                serviceModel.service.value?.let { editComment(it, commentEdit.text.toString()) }
            }
        }
        return builder.create()
    }

    override fun onDestroy() {
        stopObservingComment()
        super.onDestroy()
    }

    /**
     * Loads the first comment [extractor] finds in the service's events. Stops listening once it
     * has one, and at the latest in onDestroy.
     */
    protected fun observeComment(service: IHumlaService, extractor: (HumlaEvent) -> String?) {
        stopObservingComment()
        commentUpdates = collectEvents(lifecycleScope, service) { event ->
            extractor(event)?.let {
                loadComment(it)
                stopObservingComment()
            }
        }
    }

    /** Stops what [observeComment] started, if anything. */
    protected fun stopObservingComment() {
        commentUpdates?.cancel()
        commentUpdates = null
    }

    protected fun loadComment(comment: String) {
        val view = commentView ?: return
        view.loadData(comment, "text/html", "UTF-8")
        this.comment = comment
    }

    /**
     * Requests the comment from [service], which is expected to arrive in [loadComment]. Not
     * called if the comment came with the arguments.
     */
    abstract fun requestComment(service: IHumlaService)

    /** Asks [service] to replace the comment with [comment]. */
    abstract fun editComment(service: IHumlaService, comment: String)

    private companion object {
        const val TAB_VIEW = "View"
        const val TAB_EDIT = "Edit"
    }
}
