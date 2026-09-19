/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.net

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException

/**
 * Reads PKCS#12 client certificates: the ones Mumble writes (unencrypted keyBag and certBag,
 * MAC over the empty password) and the ones [HumlaCertificateGenerator] writes.
 *
 * The bundled BouncyCastle provider is passed explicitly rather than registered globally, so the
 * parse is always done by the BouncyCastle version this app ships instead of whatever the device
 * ROM happens to provide under the name "BC", and the result does not depend on the order in
 * which providers were registered.
 */
object Pkcs12Certificates {

    /**
     * Constructing a provider registers on the order of a thousand algorithm entries, and this
     * runs on every connection attempt and every certificate import, so keep one around.
     */
    private val PROVIDER = BouncyCastleProvider()

    @JvmStatic
    @Throws(KeyStoreException::class, IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
    fun load(pkcs12: ByteArray, password: String?): KeyStore =
        load(ByteArrayInputStream(pkcs12), password?.toCharArray() ?: CharArray(0))

    @JvmStatic
    @Throws(KeyStoreException::class, IOException::class, NoSuchAlgorithmException::class, CertificateException::class)
    fun load(input: InputStream, password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12", PROVIDER)
        store.load(input, password)
        return store
    }
}
