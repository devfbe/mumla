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

package se.lublin.humla.net

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

object HumlaCertificateGenerator {
    private const val ISSUER = "CN=Humla Client"
    private const val ALIAS = "Humla Key"
    private const val YEARS_VALID = 20

    /**
     * PBE iterations for the store's MAC. The PKCS#12 is written with an empty password and kept
     * in the app's private database, so iterating the key derivation protects nothing while
     * costing real time on the thread that connects: BouncyCastle 1.86 defaults to 1,200,000 for
     * the MAC and 600,000 for an encrypted bag, which measured 0.8-0.9 s per store and per load on
     * a desktop JVM and would be several seconds on a phone. 2048 is what BouncyCastle used before
     * 1.86, what OpenSSL's PKCS12_create defaults to, and therefore what existing Mumla and Mumble
     * certificates already carry.
     */
    private const val MAC_ITERATIONS = 2048

    @JvmStatic
    @Throws(
        NoSuchAlgorithmException::class,
        OperatorCreationException::class,
        CertificateException::class,
        KeyStoreException::class,
        NoSuchProviderException::class,
        IOException::class,
    )
    fun generateCertificate(output: OutputStream): X509Certificate {
        // BouncyCastle provider instance: supports creating X509 certs and PKCS#12 stores.
        val provider = BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())

        val keyPair = generator.generateKeyPair()

        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        val signer = JcaContentSignerBuilder("SHA1withRSA").setProvider(provider).build(keyPair.private)

        val startDate = Date()
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        calendar.add(Calendar.YEAR, YEARS_VALID)
        val endDate = calendar.time

        val certBuilder = X509v3CertificateBuilder(
            X500Name(ISSUER),
            BigInteger.ONE,
            startDate, endDate, X500Name(ISSUER),
            publicKeyInfo,
        )

        val certificateHolder = certBuilder.build(signer)

        val certificate = JcaX509CertificateConverter().setProvider(provider)
            .getCertificate(certificateHolder)

        // Built bag by bag rather than through KeyStore.store(), which offers no way to set the
        // iteration count other than the JVM-wide org.bouncycastle.pkcs12.store_it_count system
        // property. That property is global mutable state read at store time: any other code in
        // the process can set, clear or overwrite it, and it would silently change the iteration
        // count of every other PKCS#12 the process writes. Building the PFX here is local and
        // deterministic, and it produces exactly the shape Mumble itself writes -- an unencrypted
        // keyBag and certBag, both tagged with the alias, MAC over the empty password -- which
        // Pkcs12Certificates already loads and has a test for.
        val friendlyName = DERBMPString(ALIAS)
        val localKeyId = DEROctetString(
            MessageDigest.getInstance("SHA-1").digest(keyPair.public.encoded)
        )
        val keyBag = PKCS12SafeBagBuilder(PrivateKeyInfo.getInstance(keyPair.private.encoded))
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val certBag = JcaPKCS12SafeBagBuilder(certificate)
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val pfx = PKCS12PfxPduBuilder()
            .addData(keyBag)
            .addData(certBag)
            .build(
                JcePKCS12MacCalculatorBuilder().setProvider(provider)
                    .setIterationCount(MAC_ITERATIONS),
                CharArray(0),
            )

        output.write(pfx.getEncoded(ASN1Encoding.DL))

        return certificate
    }
}
