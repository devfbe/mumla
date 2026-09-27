/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.mumla.testing

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession

/** A resumed [ServiceHostActivity] with [session] as the current session. */
fun hostWith(session: IHumlaSession): ActivityController<ServiceHostActivity> =
    Robolectric.buildActivity(ServiceHostActivity::class.java).setup().also { it.get().bind(session) }

/** Adds [fragment] under [tag], into the content view if [inContent], else without a view slot. */
fun <F : Fragment> FragmentActivity.addNow(fragment: F, tag: String, inContent: Boolean = false): F {
    supportFragmentManager.beginTransaction().apply {
        if (inContent) add(android.R.id.content, fragment, tag) else add(fragment, tag)
    }.commitNow()
    return fragment
}

/**
 * Adds [child] under a new [ChatTargetParentFragment], which holds the chat target as
 * `ChannelFragment` does; into the content view if [inContent].
 */
fun FragmentActivity.addUnderChatParent(
    child: Fragment,
    childTag: String,
    parentTag: String = "parent",
    inContent: Boolean = false,
): ChatTargetParentFragment {
    val parent = addNow(ChatTargetParentFragment(), parentTag, inContent)
    parent.childFragmentManager.beginTransaction().apply {
        if (inContent) add(ChatTargetParentFragment.CONTAINER_ID, child, childTag) else add(child, childTag)
    }.commitNow()
    return parent
}
