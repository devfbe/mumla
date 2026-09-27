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
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import java.io.OutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

private const val RSA_KEY_BITS = 2048

object HumlaCertificateGenerator {
    private const val ISSUER = "CN=Humla Client"
    private const val ALIAS = "Humla Key"
    private const val YEARS_VALID = 20

    /**
     * PBE iterations for the store's MAC. The store has an empty password and lives in the app's
     * private database, so a high count protects nothing but costs seconds per load on a phone.
     * 2048 matches OpenSSL's PKCS12_create default and existing Mumble certificates.
     */
    private const val MAC_ITERATIONS = 2048

    fun generateCertificate(output: OutputStream): X509Certificate {
        // BouncyCastle provider instance: supports creating X509 certs and PKCS#12 stores.
        val provider = BouncyCastleProvider()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_BITS, SecureRandom())

        val keyPair = generator.generateKeyPair()

        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        val signer = try {
            JcaContentSignerBuilder("SHA1withRSA").setProvider(provider).build(keyPair.private)
        } catch (e: OperatorCreationException) {
            // Keeps BouncyCastle types out of this library's public API.
            throw GeneralSecurityException(e)
        }

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

        // Built bag by bag rather than through KeyStore.store(): the only other way to set the
        // iteration count is the JVM-wide org.bouncycastle.pkcs12.store_it_count property. This
        // produces the shape Mumble writes (unencrypted keyBag and certBag tagged with the alias,
        // MAC over the empty password).
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
