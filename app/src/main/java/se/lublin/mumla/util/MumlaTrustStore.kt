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
package se.lublin.mumla.util

import android.content.Context
import se.lublin.humla.net.CertificatePins
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.cert.X509Certificate

/** The app's store of server certificates the user chose to trust, one pin per host. */
object MumlaTrustStore {
    private const val STORE_FILE = "mumla-store.bks"
    const val STORE_PASSWORD = ""
    const val STORE_FORMAT = "BKS"

    /** The stored certificates; an empty store if none were saved yet. */
    fun getTrustStore(context: Context): KeyStore {
        val store = KeyStore.getInstance(STORE_FORMAT)
        try {
            // Closed on every path, a store that fails to load included.
            context.openFileInput(STORE_FILE).use { store.load(it, STORE_PASSWORD.toCharArray()) }
        } catch (_: FileNotFoundException) {
            store.load(null, null)
        }
        return store
    }

    fun saveTrustStore(context: Context, store: KeyStore) {
        context.openFileOutput(STORE_FILE, Context.MODE_PRIVATE).use { store.store(it, STORE_PASSWORD.toCharArray()) }
    }

    /**
     * Makes [certificate] the only trusted certificate for [host], replacing any earlier one
     * whatever the case of its alias.
     */
    fun pinCertificate(context: Context, host: String, certificate: X509Certificate) {
        val store = getTrustStore(context)
        val alias = CertificatePins.aliasFor(host)
        store.aliases().toList()
            .filter { CertificatePins.aliasFor(it) == alias }
            .forEach(store::deleteEntry)
        store.setCertificateEntry(alias, certificate)
        saveTrustStore(context, store)
    }

    fun clearTrustStore(context: Context) {
        context.deleteFile(STORE_FILE)
    }

    /** The store's absolute path, or null if nothing was saved yet. */
    fun getTrustStorePath(context: Context): String? =
        File(context.filesDir, STORE_FILE).takeIf { it.exists() }?.absolutePath
}
