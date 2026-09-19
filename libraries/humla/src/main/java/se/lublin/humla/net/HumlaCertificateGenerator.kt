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

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
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

        val keyStore = KeyStore.getInstance("PKCS12", provider)
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, null, arrayOf(certificate))

        keyStore.store(output, "".toCharArray())

        return certificate
    }
}
