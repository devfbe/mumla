package se.lublin.humla.net

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Certificates for trust tests: a private CA standing in for the system store, and leaves. */
object TestCertificates {
    class Issued(val keyPair: KeyPair, val certificate: X509Certificate, val chain: Array<X509Certificate>)

    private val serial = AtomicLong(1)

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val ca: Issued by lazy {
        val kp = keyPair()
        val name = X500Name("CN=Test Root CA")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(serial.getAndIncrement()), Date(System.currentTimeMillis() - 86_400_000),
            Date(System.currentTimeMillis() + 86_400_000L * 365), name, kp.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        val cert = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        )
        Issued(kp, cert, arrayOf(cert))
    }

    /** A leaf signed by [ca] (or self-signed) carrying the given subjectAltNames. */
    fun leaf(dnsNames: List<String> = emptyList(), ipAddresses: List<String> = emptyList(), selfSigned: Boolean = false, cn: String = "server"): Issued {
        val kp = keyPair()
        val subject = X500Name("CN=$cn")
        val issuer = if (selfSigned) subject else X500Name(ca.certificate.subjectX500Principal.name)
        val builder = JcaX509v3CertificateBuilder(
            issuer, BigInteger.valueOf(serial.getAndIncrement()), Date(System.currentTimeMillis() - 86_400_000),
            Date(System.currentTimeMillis() + 86_400_000L * 30), subject, kp.public,
        )
        val names = dnsNames.map { GeneralName(GeneralName.dNSName, it) } +
            ipAddresses.map { GeneralName(GeneralName.iPAddress, it) }
        if (names.isNotEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        }
        val signingKey = if (selfSigned) kp.private else ca.keyPair.private
        val cert = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signingKey))
        )
        return Issued(kp, cert, if (selfSigned) arrayOf(cert) else arrayOf(cert, ca.certificate))
    }

    /** A trust manager that trusts exactly [ca], like the system store trusts public CAs. */
    fun systemTrust(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", ca.certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(store)
        return tmf.trustManagers.single() as X509TrustManager
    }

    /** A trust store of pinned certificates, keyed by host alias as the app stores them. */
    fun pinStore(vararg entries: Pair<String, X509Certificate>): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            entries.forEach { (alias, cert) -> setCertificateEntry(alias, cert) }
        }
}
