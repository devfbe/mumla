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
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.mumla.databinding.FragmentServerInfoBinding
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.ui.bindClient
import se.lublin.mumla.ui.ServiceClient
import se.lublin.mumla.ui.ServiceViewModel

/** Displays what is known about the connected server, refreshed every second. */
class ServerInfoFragment : Fragment(), ServiceClient {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private var polling: Job? = null
    private var bound = false

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
        if (!bound) {
            bound = true
            serviceModel.bindClient(this, this)
        }
    }

    override fun onServiceBound(service: IMumlaService) {
        polling = lifecycleScope.launch {
            while (isActive) {
                if (isVisible) updateData(service)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun onServiceUnbound() {
        polling?.cancel()
        polling = null
    }

    private fun updateData(service: IMumlaService) {
        if (!service.isConnected) return
        val session = service.session
        val server = service.targetServer

        protocolView.text = getString(R.string.server_info_protocol, session.serverRelease)
        osVersionView.text = getString(R.string.server_info_version, session.serverOSName, session.serverOSVersion)
        tcpLatencyView.text = getString(R.string.server_info_latency, session.tcpLatency * MICROS_TO_MILLIS)
        udpLatencyView.text = getString(R.string.server_info_latency, session.udpLatency * MICROS_TO_MILLIS)
        hostView.text = getString(R.string.server_info_host, server?.srvHost, server?.srvPort)
        maxBandwidthView.text = getString(R.string.server_info_max_bandwidth, session.maxBandwidth / KILO)
        currentBandwidthView.text = getString(R.string.server_info_current_bandwidth, session.currentBandwidth / KILO)
        // Opus is the only codec; null means the server offers none this client can use.
        val codecName = if (session.codec == HumlaUDPMessageType.UDPVoiceOpus) "Opus" else "<null>"
        codecView.text = getString(R.string.server_info_codec, codecName)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val MICROS_TO_MILLIS = 1e-3
        const val KILO = 1000f
    }
}
