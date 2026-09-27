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

private const val KEY_BITS = 2048
private const val DAY_MILLIS = 86_400_000L
private const val CA_VALID_DAYS = 365
private const val LEAF_VALID_DAYS = 30

/** Certificates for trust tests: a private CA standing in for the system store, and leaves. */
object TestCertificates {
    class Issued(val keyPair: KeyPair, val certificate: X509Certificate, val chain: Array<X509Certificate>)

    private val serial = AtomicLong(1)

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    /** Signed by [signer], or self-signed without one; valid from a day ago for [validDays]. */
    private fun issue(
        subject: X500Name,
        keyPair: KeyPair,
        signer: Issued?,
        validDays: Int,
        extend: JcaX509v3CertificateBuilder.() -> Unit,
    ): X509Certificate {
        val issuer = signer?.let { X500Name(it.certificate.subjectX500Principal.name) } ?: subject
        val signingKey = (signer?.keyPair ?: keyPair).private
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            issuer, BigInteger.valueOf(serial.getAndIncrement()), Date(now - DAY_MILLIS),
            Date(now + DAY_MILLIS * validDays), subject, keyPair.public,
        ).apply(extend)
        return JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signingKey))
        )
    }

    val ca: Issued by lazy {
        val kp = keyPair()
        val name = X500Name("CN=Test Root CA")
        val cert = issue(name, kp, signer = null, CA_VALID_DAYS) {
            addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        }
        Issued(kp, cert, arrayOf(cert))
    }

    /** A leaf signed by [ca] (or self-signed) carrying the given subjectAltNames. */
    fun leaf(
        dnsNames: List<String> = emptyList(),
        ipAddresses: List<String> = emptyList(),
        selfSigned: Boolean = false,
        cn: String = "server",
    ): Issued {
        val kp = keyPair()
        val subject = X500Name("CN=$cn")
        val names = dnsNames.map { GeneralName(GeneralName.dNSName, it) } +
            ipAddresses.map { GeneralName(GeneralName.iPAddress, it) }
        val cert = issue(subject, kp, signer = if (selfSigned) null else ca, LEAF_VALID_DAYS) {
            if (names.isNotEmpty()) {
                addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
            }
        }
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
