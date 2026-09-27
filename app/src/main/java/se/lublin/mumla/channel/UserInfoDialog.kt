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
package se.lublin.mumla.channel

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import se.lublin.humla.model.UserStats
import se.lublin.mumla.R
import se.lublin.mumla.databinding.DialogUserInfoBinding
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal
import kotlin.math.sqrt

/**
 * Shows the connection statistics of the user called [name] as [stats] delivers them, until the
 * dialog is closed.
 */
fun showUserInfoDialog(context: Context, name: String?, stats: Flow<UserStats>): AlertDialog {
    val binding = DialogUserInfoBinding.inflate(LayoutInflater.from(context))
    binding.userInfoText.setText(R.string.user_info_loading)
    val formatter = UserInfoFormatter(context)
    val updates = MainScope().launch { stats.collect { binding.userInfoText.text = formatter.format(it) } }
    return MaterialAlertDialogBuilder(context)
        .setTitle(name)
        .setView(binding.root)
        .setPositiveButton(android.R.string.ok, null)
        .setOnDismissListener { updates.cancel() }
        .show()
}

/** Phrases [UserStats] as labelled rows; values the server did not send are left out. */
class UserInfoFormatter(private val context: Context) {

    /** The rows as bold labels and values, one per line. */
    fun format(stats: UserStats): CharSequence {
        val rows = rows(stats)
        if (rows.isEmpty()) return context.getString(R.string.user_info_unavailable)
        val text = SpannableStringBuilder()
        for ((label, value) in rows) {
            if (text.isNotEmpty()) text.append('\n')
            text.inSpans(StyleSpan(Typeface.BOLD)) { append(label).append(": ") }
            text.append(value)
        }
        return text
    }

    fun rows(stats: UserStats): List<Pair<String, String>> = buildList {
        stats.version?.let { version ->
            val release = stats.release
            add(
                string(R.string.user_info_version) to
                    if (release == null || release == version) version
                    else string(R.string.user_info_version_release, version, release)
            )
        }
        listOfNotNull(stats.os, stats.osVersion).takeIf { it.isNotEmpty() }?.let {
            add(string(R.string.user_info_os) to it.joinToString(" "))
        }
        stats.onlineSeconds?.let { add(string(R.string.user_info_online) to DateUtils.formatElapsedTime(it.toLong())) }
        stats.idleSeconds?.let { add(string(R.string.user_info_idle) to DateUtils.formatElapsedTime(it.toLong())) }
        stats.address?.let { add(string(R.string.user_info_address) to it) }
        stats.tcpPing?.let { add(string(R.string.user_info_tcp_ping) to ping(it)) }
        stats.udpPing?.let { add(string(R.string.user_info_udp_ping) to ping(it)) }
        stats.fromClient?.let { add(string(R.string.user_info_from_client) to packets(it)) }
        stats.fromServer?.let { add(string(R.string.user_info_from_server) to packets(it)) }
        stats.bandwidth?.let {
            add(string(R.string.user_info_bandwidth) to string(R.string.user_info_bandwidth_value, it / BYTES_PER_KBIT))
        }
        stats.certificates.firstOrNull()?.let { add(string(R.string.user_info_certificate) to certificate(it, stats)) }
    }

    /** The average and the standard deviation, as desktop Mumble shows them. */
    private fun ping(ping: UserStats.Ping): String =
        string(R.string.user_info_ping_value, ping.averageMillis, sqrt(ping.varianceMillis.coerceAtLeast(0f)))

    private fun packets(p: UserStats.Packets): String =
        string(R.string.user_info_packets_value, p.good, p.late, p.lost, p.resync)

    private fun certificate(leaf: X509Certificate, stats: UserStats): String {
        val name = commonName(leaf.subjectX500Principal)
        return string(
            if (stats.strongCertificate) R.string.user_info_certificate_strong else R.string.user_info_certificate_weak,
            name,
        )
    }

    private fun commonName(principal: X500Principal): String {
        val dn = principal.getName(X500Principal.RFC2253)
        return COMMON_NAME.find(dn)?.groupValues?.get(1) ?: dn
    }

    private fun string(id: Int, vararg args: Any?): String = context.getString(id, *args)

    private companion object {
        /** `UserStats.bandwidth` is in bytes per second. */
        const val BYTES_PER_KBIT = 125f
        val COMMON_NAME = Regex("(?:^|,)CN=((?:[^,\\\\]|\\\\.)*)")
    }
}
