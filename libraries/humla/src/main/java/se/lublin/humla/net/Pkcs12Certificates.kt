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

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Pfx
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.OutputEncryptor
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Reads PKCS#12 client certificates: the ones Mumble writes (unencrypted keyBag and certBag,
 * MAC over the empty password) and the ones [HumlaCertificateGenerator] writes.
 *
 * The bundled BouncyCastle provider is passed explicitly rather than registered globally, so the
 * parse never depends on whatever "BC" provider the device ROM ships.
 */
object Pkcs12Certificates {

    /**
     * Kept around: constructing a provider is expensive and this runs on every connection attempt.
     */
    private val PROVIDER = BouncyCastleProvider()

    fun load(pkcs12: ByteArray, password: String?): KeyStore =
        load(ByteArrayInputStream(pkcs12), password?.toCharArray() ?: CharArray(0))

    fun load(input: InputStream, password: CharArray): KeyStore {
        val store = KeyStore.getInstance("PKCS12", PROVIDER)
        store.load(input, password)
        return store
    }

    /** True if [bytes] parse as a PKCS#12 PFX structure, whether or not its password is known. */
    fun isPkcs12(bytes: ByteArray): Boolean = try {
        Pfx.getInstance(ASN1Primitive.fromByteArray(bytes))
        true
    } catch (e: Exception) {
        false
    }

    /** PBKDF2 and MAC iterations for a password-protected export; it leaves the device, unlike the stored copy. */
    private const val EXPORT_ITERATIONS = 100_000

    /**
     * Re-encrypts a stored (empty-password) PKCS#12 under [password]: private keys as PBES2
     * AES-256-CBC shrouded key bags, certificates in PBES2 AES-256-CBC encrypted data, and a
     * SHA-256 MAC, the layout OpenSSL 3 writes by default.
     */
    fun exportWithPassword(stored: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "an export needs a password" }
        val source = load(stored, null)
        val keyBags = mutableListOf<PKCS12SafeBag>()
        val certBags = mutableListOf<PKCS12SafeBag>()
        for (alias in source.aliases().toList()) {
            val chain = source.getCertificateChain(alias)?.map { it as X509Certificate }
                ?: listOfNotNull(source.getCertificate(alias) as? X509Certificate)
            val key = if (source.isKeyEntry(alias)) source.getKey(alias, CharArray(0)) as? PrivateKey else null
            val friendlyName = DERBMPString(alias)
            val localKeyId = chain.firstOrNull()?.let {
                DEROctetString(MessageDigest.getInstance("SHA-1").digest(it.publicKey.encoded))
            }
            if (key != null) {
                val bag = JcaPKCS12SafeBagBuilder(key, aesEncryptor(password))
                    .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
                if (localKeyId != null) bag.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
                keyBags += bag.build()
            }
            chain.forEachIndexed { index, certificate ->
                val bag = JcaPKCS12SafeBagBuilder(certificate)
                if (index == 0) {
                    bag.addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
                    if (localKeyId != null) bag.addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
                }
                certBags += bag.build()
            }
        }
        val builder = PKCS12PfxPduBuilder()
        if (certBags.isNotEmpty()) builder.addEncryptedData(aesEncryptor(password), certBags.toTypedArray())
        keyBags.forEach { builder.addData(it) }
        val pfx = try {
            builder.build(
                JcePKCS12MacCalculatorBuilder(NISTObjectIdentifiers.id_sha256)
                    .setProvider(PROVIDER).setIterationCount(EXPORT_ITERATIONS),
                password,
            )
        } catch (e: org.bouncycastle.pkcs.PKCSException) {
            throw KeyStoreException(e)
        }
        return pfx.getEncoded(ASN1Encoding.DL)
    }

    /** A fresh encryptor per use, so no two encryptions share a salt and IV. */
    private fun aesEncryptor(password: CharArray): OutputEncryptor =
        JcePKCSPBEOutputEncryptorBuilder(NISTObjectIdentifiers.id_aes256_CBC)
            .setProvider(PROVIDER)
            .setPRF(AlgorithmIdentifier(PKCSObjectIdentifiers.id_hmacWithSHA256, DERNull.INSTANCE))
            .setIterationCount(EXPORT_ITERATIONS)
            .build(password)
}
