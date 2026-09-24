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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import se.lublin.mumla.app.ServiceViewModel
import se.lublin.mumla.databinding.FragmentTokensBinding
import se.lublin.mumla.databinding.TokenRowBinding
import se.lublin.mumla.db.MumlaRepository

/** Edits the access tokens stored for a server, and sends them to it while connected. */
class AccessTokenFragment : Fragment() {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private val repository get() = MumlaRepository.get(requireContext())
    private val serverId get() = requireArguments().getLong(ARG_SERVER)

    private val tokens = mutableListOf<String>()
    private val tokenAdapter = TokenAdapter()
    private var binding: FragmentTokensBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentTokensBinding.inflate(inflater, container, false)
        this.binding = binding
        binding.tokenList.layoutManager = LinearLayoutManager(requireContext())
        binding.tokenList.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        binding.tokenList.adapter = tokenAdapter
        binding.tokenField.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_SEND).also { if (it) addToken() }
        }
        binding.tokenAddButton.setOnClickListener { addToken() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val serverId = serverId
        viewLifecycleOwner.lifecycleScope.launch {
            tokens.clear()
            tokens += repository.io { getAccessTokens(serverId) }
            showTokens()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun showTokens(then: Runnable? = null) = tokenAdapter.submitList(tokens.toList(), then)

    private fun addToken() {
        val field = binding?.tokenField ?: return
        val token = field.text.toString().trim()
        if (token.isEmpty()) return
        field.setText("")
        Log.i(TAG, "Adding a token")

        tokens += token
        showTokens { binding?.tokenList?.smoothScrollToPosition(tokens.size - 1) }
        val serverId = serverId
        lifecycleScope.launch { repository.io { addAccessToken(serverId, token) } }
        sendTokens()
    }

    private fun removeToken(token: String) {
        if (!tokens.remove(token)) return
        showTokens()
        val serverId = serverId
        lifecycleScope.launch { repository.io { removeAccessToken(serverId, token) } }
        sendTokens()
    }

    private fun sendTokens() {
        serviceModel.service.value?.takeIf { it.isConnected }?.session?.sendAccessTokens(tokens.toList())
    }

    private class TokenHolder(val binding: TokenRowBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class TokenAdapter : ListAdapter<String, TokenHolder>(TOKEN_DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenHolder {
            val holder = TokenHolder(TokenRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.binding.tokenItemDelete.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) removeToken(getItem(position))
            }
            return holder
        }

        override fun onBindViewHolder(holder: TokenHolder, position: Int) {
            holder.binding.tokenItemTitle.text = getItem(position)
        }
    }

    companion object {
        private val TAG: String = AccessTokenFragment::class.java.name
        private const val ARG_SERVER = "server"

        private val TOKEN_DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        }

        fun newInstance(serverId: Long) = AccessTokenFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERVER, serverId) }
        }
    }
}
