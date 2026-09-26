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

package se.lublin.mumla.preference

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRepository

private const val TEXT_SIZE_SP = 16f

/** Lets the user pick the default client certificate, or none. */
class CertificateSelectActivity : AppCompatActivity() {

    private class Choice(val label: String, val isDefault: Boolean, val activate: () -> Unit) {
        override fun toString() = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings.getInstance(this)
        lifecycleScope.launch {
            val certificates = MumlaRepository.get(this@CertificateSelectActivity).io { getCertificates() }
            val none = Choice(getString(R.string.no_certificate), !settings.isUsingCertificate) {
                settings.disableCertificate()
            }
            val choices = listOf(none) + certificates.map { certificate ->
                Choice(certificate.name, settings.defaultCertificateId == certificate.id) {
                    settings.defaultCertificateId = certificate.id
                }
            }
            showSelectionDialog(choices)
        }
    }

    private fun showSelectionDialog(choices: List<Choice>) {
        val adapter = object : ArrayAdapter<Choice>(this, android.R.layout.select_dialog_singlechoice, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    it.findViewById<TextView>(android.R.id.text1).setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
                }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_certificate_title)
            .setSingleChoiceItems(adapter, choices.indexOfFirst { it.isDefault }) { _, which ->
                choices[which].activate()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .setOnDismissListener { finish() }
    }
}
