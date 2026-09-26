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

package se.lublin.mumla.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AES_KEY_BITS = 256

/** Authenticated encryption of small secrets. Both calls throw when the key is unusable. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key, created on first use. The output is
 * the 12-byte IV followed by ciphertext and tag. The key never leaves the device, so data sealed
 * with it cannot be read after a device-to-device transfer.
 */
object KeystoreSecretCipher : SecretCipher {
    private const val TAG = "KeystoreSecretCipher"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "mumla_db_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    @Volatile private var key: SecretKey? = null
    private var failureLogged = false

    /**
     * Loads or creates the key; creation is serialized so two callers cannot mint two keys. A
     * failure is not remembered, so a transient Keystore error does not hide secrets for the
     * rest of the process.
     */
    @Synchronized
    private fun key(): SecretKey {
        key?.let { return it }
        try {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val existing = store.getKey(ALIAS, null) as? SecretKey
            val created = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_KEY_BITS)
                        .build()
                )
                generateKey()
            }
            key = created
            return created
        } catch (e: Exception) {
            val failure = e as? GeneralSecurityException ?: GeneralSecurityException("Android Keystore unavailable", e)
            if (!failureLogged) {
                Log.e(TAG, "Android Keystore unavailable", failure)
                failureLogged = true
            }
            throw failure
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected GCM IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed.size < IV_LENGTH) throw GeneralSecurityException("sealed secret too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH))
        return cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
    }
}

/**
 * How secrets are stored in the database columns. Sealed values carry a marker, so rows written
 * before encryption (or while the key was unavailable) are still read as plain text.
 *
 * Failure policy: a value that cannot be sealed is stored in plain text rather than lost, and a
 * sealed value that cannot be opened reads as absent (null) rather than crashing.
 */
class SecretCodec(private val cipher: SecretCipher) {

    fun sealString(plain: String?): String? {
        if (plain == null) return null
        return try {
            STRING_PREFIX + Base64.getEncoder().encodeToString(cipher.encrypt(plain.toByteArray(Charsets.UTF_8)))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Storing a secret unencrypted", e)
            plain
        }
    }

    fun openString(stored: String?): String? {
        if (stored == null || !isSealed(stored)) return stored
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored.substring(STRING_PREFIX.length))), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt a stored secret", e)
            null
        }
    }

    fun sealBlob(plain: ByteArray): ByteArray = try {
        BLOB_MAGIC + cipher.encrypt(plain)
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "Storing a secret unencrypted", e)
        plain
    }

    fun openBlob(stored: ByteArray?): ByteArray? {
        if (stored == null || !isSealed(stored)) return stored
        return try {
            cipher.decrypt(stored.copyOfRange(BLOB_MAGIC.size, stored.size))
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt a stored secret", e)
            null
        }
    }

    companion object {
        private const val TAG = "SecretCodec"

        /** U+0001 cannot be typed into a password field, so no legacy plain text starts with it. */
        const val STRING_PREFIX = "\u0001enc1:"

        /** PKCS#12 blobs start with an ASN.1 SEQUENCE (0x30), never with this. */
        val BLOB_MAGIC = byteArrayOf(0x01, 'E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 0x01)

        fun isSealed(stored: String): Boolean = stored.startsWith(STRING_PREFIX)

        fun isSealed(stored: ByteArray): Boolean =
            stored.size > BLOB_MAGIC.size && stored.copyOfRange(0, BLOB_MAGIC.size).contentEquals(BLOB_MAGIC)
    }
}
