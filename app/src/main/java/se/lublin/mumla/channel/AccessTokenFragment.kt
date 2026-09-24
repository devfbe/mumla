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

package se.lublin.mumla.channel

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.app.ServiceViewModel
import se.lublin.mumla.db.MumlaRepository

/** Edits the access tokens stored for a server, and sends them to it while connected. */
class AccessTokenFragment : Fragment() {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private val repository get() = MumlaRepository.get(requireContext())
    private val serverId get() = requireArguments().getLong(ARG_SERVER)

    private val tokens = mutableListOf<String>()
    private lateinit var tokenAdapter: TokenAdapter
    private lateinit var tokenList: ListView
    private lateinit var tokenField: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_tokens, container, false)
        tokenAdapter = TokenAdapter(requireContext())
        tokenList = view.findViewById(R.id.tokenList)
        tokenList.adapter = tokenAdapter
        tokenField = view.findViewById(R.id.tokenField)
        tokenField.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_SEND).also { if (it) addToken() }
        }
        view.findViewById<ImageButton>(R.id.tokenAddButton).setOnClickListener { addToken() }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val serverId = serverId
        viewLifecycleOwner.lifecycleScope.launch {
            tokens.clear()
            tokens += repository.io { getAccessTokens(serverId) }
            tokenAdapter.notifyDataSetChanged()
        }
    }

    private fun addToken() {
        val token = tokenField.text.toString().trim()
        if (token.isEmpty()) return
        tokenField.setText("")
        Log.i(TAG, "Adding a token")

        tokens += token
        tokenAdapter.notifyDataSetChanged()
        tokenList.smoothScrollToPosition(tokens.size - 1)
        val serverId = serverId
        lifecycleScope.launch { repository.io { addAccessToken(serverId, token) } }
        sendTokens()
    }

    private fun removeToken(position: Int) {
        val token = tokens.removeAt(position)
        tokenAdapter.notifyDataSetChanged()
        val serverId = serverId
        lifecycleScope.launch { repository.io { removeAccessToken(serverId, token) } }
        sendTokens()
    }

    private fun sendTokens() {
        serviceModel.service.value?.takeIf { it.isConnected }?.session?.sendAccessTokens(tokens.toList())
    }

    private inner class TokenAdapter(context: Context) : ArrayAdapter<String>(context, 0, tokens) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.token_row, parent, false)
            view.findViewById<TextView>(R.id.tokenItemTitle).text = getItem(position)
            view.findViewById<ImageButton>(R.id.tokenItemDelete).setOnClickListener { removeToken(position) }
            return view
        }
    }

    companion object {
        private val TAG: String = AccessTokenFragment::class.java.name
        private const val ARG_SERVER = "server"

        fun newInstance(serverId: Long) = AccessTokenFragment().apply { arguments = bundleOf(ARG_SERVER to serverId) }
    }
}
