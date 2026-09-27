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

package se.lublin.mumla.channel.comment

import androidx.core.os.bundleOf
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent

/** Shows (and for the own user, edits) a user's comment. */
class UserCommentFragment : AbstractCommentFragment() {

    /** The session id of the user whose comment this is. */
    val user: Int get() = requireArguments().getInt(ARG_SESSION)

    override fun requestComment(session: IHumlaSession) {
        observeComment(session) { event ->
            (event as? HumlaEvent.UserStateUpdated)?.user?.takeIf { it.session == user }?.comment
        }
        session.requestComment(user)
    }

    override fun editComment(session: IHumlaSession, comment: String) = session.setUserComment(user, comment)

    companion object {
        private const val ARG_SESSION = "session"

        /** The comment of the user with [session]; [comment] is null until it is fetched. */
        fun newInstance(session: Int, comment: String?, editing: Boolean) = UserCommentFragment().apply {
            arguments = bundleOf(ARG_SESSION to session, ARG_COMMENT to comment, ARG_EDITING to editing)
        }
    }
}
