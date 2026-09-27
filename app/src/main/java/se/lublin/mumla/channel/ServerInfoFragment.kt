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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.databinding.FragmentServerInfoBinding
import se.lublin.mumla.session.SessionManager

private const val POLL_INTERVAL_MS = 1000L
private const val MICROS_TO_MILLIS = 1e-3
private const val KILO = 1000f

/** Displays what is known about the connected server, refreshed every second. */
class ServerInfoFragment : Fragment() {

    private var polling: Job? = null

    private lateinit var protocolView: TextView
    private lateinit var osVersionView: TextView
    private lateinit var tcpLatencyView: TextView
    private lateinit var udpLatencyView: TextView
    private lateinit var hostView: TextView
    private lateinit var codecView: TextView
    private lateinit var maxBandwidthView: TextView
    private lateinit var currentBandwidthView: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentServerInfoBinding.inflate(inflater, container, false)
        protocolView = binding.serverInfoProtocol
        osVersionView = binding.serverInfoOsVersion
        tcpLatencyView = binding.serverInfoTcpLatency
        udpLatencyView = binding.serverInfoUdpLatency
        hostView = binding.serverInfoHost
        maxBandwidthView = binding.serverInfoMaxBandwidth
        currentBandwidthView = binding.serverInfoCurrentBandwidth
        codecView = binding.serverInfoCodec
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (polling == null) {
            val sessions = SessionManager.get(requireContext())
            polling = lifecycleScope.launch {
                while (isActive) {
                    val session = sessions.connected
                    if (isVisible && session != null) updateData(session)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun updateData(session: IHumlaSession) {
        val info = session.serverInfo ?: return
        val latency = session.latency ?: return

        protocolView.text = getString(R.string.server_info_protocol, info.release)
        osVersionView.text = getString(R.string.server_info_version, info.osName, info.osVersion)
        tcpLatencyView.text = getString(R.string.server_info_latency, latency.tcpMicros * MICROS_TO_MILLIS)
        udpLatencyView.text = getString(R.string.server_info_latency, latency.udpMicros * MICROS_TO_MILLIS)
        hostView.text = getString(R.string.server_info_host, info.host, info.port)
        maxBandwidthView.text = getString(R.string.server_info_max_bandwidth, info.maxBandwidth / KILO)
        currentBandwidthView.text =
            getString(R.string.server_info_current_bandwidth, session.audio.currentBandwidth / KILO)
        val codecName = if (info.opus) "Opus" else "<null>"
        codecView.text = getString(R.string.server_info_codec, codecName)
    }
}
