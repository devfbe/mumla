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
import android.webkit.WebView
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import se.lublin.humla.IHumlaService
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.app.ServiceViewModel
import se.lublin.mumla.databinding.DialogCommentBinding
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
        val binding = DialogCommentBinding.inflate(layoutInflater)
        val commentView = binding.commentView.also { this.commentView = it }
        commentView.configureForUntrustedHtml()
        commentEdit = binding.commentEdit

        val known = comment
        if (known == null) {
            commentView.loadData("Loading...", null, null)
            serviceModel.service.value?.let(::requestComment)
        } else {
            loadComment(known)
        }

        val tabs = binding.commentTabs
        tabs.addTab(tabs.newTab().setText(R.string.comment_view))
        val sourceLabel = if (isEditing) R.string.comment_edit_source else R.string.comment_view_source
        tabs.addTab(tabs.newTab().setText(sourceLabel))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showSource = tab.position == TAB_SOURCE
                commentView.isVisible = !showSource
                commentEdit.isVisible = showSource
                if (!showSource) {
                    // Show the user's HTML changes.
                    commentView.loadData(commentEdit.text.toString(), "text/html", "UTF-8")
                } else if (commentEdit.text.isEmpty()) {
                    // Filled on first view only, which is faster with long comments.
                    commentEdit.setText(comment)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        if (isEditing) tabs.selectTab(tabs.getTabAt(TAB_SOURCE))

        val builder = MaterialAlertDialogBuilder(requireActivity())
            .setView(binding.root)
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
        const val TAB_SOURCE = 1
    }
}
