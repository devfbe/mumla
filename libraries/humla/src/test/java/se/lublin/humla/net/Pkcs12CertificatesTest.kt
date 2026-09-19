package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.Pfx
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagBuilder
import org.bouncycastle.pkcs.PKCS12SafeBagFactory
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.util.Date

class Pkcs12CertificatesTest {

    @Test
    fun `loads a mumble style pkcs12 whose private key sits in an unencrypted keyBag`() {
        val keyPair = rsaKeyPair()
        val cert = selfSigned(keyPair, "CN=Mumble Test")
        val bytes = mumbleStylePkcs12(keyPair, cert)
        assertIsUnencryptedKeyBagShape(bytes)

        val store = Pkcs12Certificates.load(bytes, null)

        val alias = store.aliases().toList().single()
        assertThat(store.isKeyEntry(alias)).isTrue()
        assertThat(store.getKey(alias, CharArray(0))).isInstanceOf(RSAPrivateKey::class.java)
        val loadedCert = store.getCertificate(alias) as X509Certificate
        assertThat(loadedCert.subjectX500Principal.name).isEqualTo("CN=Mumble Test")
        assertThat(loadedCert.encoded).isEqualTo(cert.encoded)
    }

    @Test
    fun `a certificate written by HumlaCertificateGenerator loads back with its key`() {
        val out = ByteArrayOutputStream()
        val generated = HumlaCertificateGenerator.generateCertificate(out)

        val store = Pkcs12Certificates.load(out.toByteArray(), "")

        assertThat(store.getCertificate("Humla Key").encoded).isEqualTo(generated.encoded)
        assertThat(store.getKey("Humla Key", CharArray(0))).isInstanceOf(RSAPrivateKey::class.java)
    }

    /**
     * BouncyCastle 1.86 raised its PKCS#12 defaults to NIST levels: a MAC at 1,200,000 iterations
     * and an encrypted certificate bag at 600,000. Deriving those costs the better part of a
     * second per store and per load on a desktop, and several seconds on a phone -- once on every
     * connection, on the thread that calls connect(). The key is kept with an empty password in
     * the app's private database, so the iterations buy nothing against any realistic attacker.
     *
     * This pins the counts so a future BouncyCastle bump cannot quietly put them back.
     */
    @Test
    fun `a generated certificate uses a cheap iteration count`() {
        val out = ByteArrayOutputStream()
        HumlaCertificateGenerator.generateCertificate(out)
        val bytes = out.toByteArray()

        assertThat(Pfx.getInstance(bytes).macData.iterationCount.toInt()).isEqualTo(2048)
        // No encrypted bag at all: nothing else can carry a key derivation.
        assertThat(PKCS12PfxPdu(bytes).contentInfos.map { it.contentType })
            .doesNotContain(PKCSObjectIdentifiers.encryptedData)
    }

    /**
     * The generated store must keep the shape [Pkcs12Certificates] and Mumble itself read: an
     * unencrypted keyBag and certBag, both tagged with the alias the rest of the app looks up.
     */
    @Test
    fun `a generated certificate has the same shape as a mumble written one`() {
        val out = ByteArrayOutputStream()
        HumlaCertificateGenerator.generateCertificate(out)

        assertIsUnencryptedKeyBagShape(out.toByteArray())
    }

    @Test
    fun `a null password is treated as the empty password`() {
        val keyPair = rsaKeyPair()
        val cert = selfSigned(keyPair, "CN=Mumble Test")
        val bytes = mumbleStylePkcs12(keyPair, cert)

        val viaNull = Pkcs12Certificates.load(bytes, null)
        val viaEmpty = Pkcs12Certificates.load(bytes, "")

        assertThat(viaNull.aliases().toList()).isEqualTo(viaEmpty.aliases().toList())
    }

    @Test
    fun `a wrong password is rejected instead of yielding a half loaded keystore`() {
        val keyPair = rsaKeyPair()
        val bytes = mumbleStylePkcs12(keyPair, selfSigned(keyPair, "CN=Mumble Test"))

        val thrown = assertThrows(IOException::class.java) {
            Pkcs12Certificates.load(bytes, "not the password")
        }

        assertThat(thrown).hasMessageThat().contains("mac invalid")
    }

    @Test
    fun `a corrupted file is rejected instead of yielding a half loaded keystore`() {
        val keyPair = rsaKeyPair()
        val bytes = mumbleStylePkcs12(keyPair, selfSigned(keyPair, "CN=Mumble Test"))
        // Flip a bit inside the authenticated safe, well past the outer ASN.1 headers, so the
        // structure still parses and it is the MAC that catches the damage.
        val corrupted = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }

        val thrown = assertThrows(IOException::class.java) { Pkcs12Certificates.load(corrupted, null) }

        assertThat(thrown).hasMessageThat().contains("mac invalid")
    }

    /**
     * Pins the fixture itself: if a future BouncyCastle changed what [PKCS12SafeBagBuilder] or
     * [PKCS12PfxPduBuilder] emit by default, the load test above would keep passing while
     * silently no longer covering the shape Mumble writes.
     */
    private fun assertIsUnencryptedKeyBagShape(bytes: ByteArray) {
        val pfx = PKCS12PfxPdu(bytes)
        val bagTypes = pfx.contentInfos.flatMap { PKCS12SafeBagFactory(it).safeBags.toList() }
            .map { it.type }
        assertThat(bagTypes).contains(PKCSObjectIdentifiers.keyBag)
        assertThat(bagTypes).doesNotContain(PKCSObjectIdentifiers.pkcs8ShroudedKeyBag)
        assertThat(bagTypes).contains(PKCSObjectIdentifiers.certBag)
        assertThat(pfx.contentInfos.map { it.contentType })
            .doesNotContain(PKCSObjectIdentifiers.encryptedData)
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun selfSigned(keyPair: KeyPair, dn: String): X509Certificate {
        val provider = BouncyCastleProvider()
        val name = X500Name(dn)
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 24L * 60 * 60 * 1000)
        val holder = X509v3CertificateBuilder(
            name, BigInteger.ONE, notBefore, notAfter, name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private))
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
    }

    /**
     * Mirrors what Mumble's Cert.cpp writes with
     * PKCS12_create("", "Mumble Identity", pkey, x509, certs, -1, -1, 0, 0, 0):
     * an unencrypted keyBag and certBag in plain `data` ContentInfos, both tagged with
     * friendlyName and localKeyId, and a MAC computed over the empty password.
     */
    private fun mumbleStylePkcs12(keyPair: KeyPair, cert: X509Certificate): ByteArray {
        val provider = BouncyCastleProvider()
        val friendlyName = DERBMPString("Mumble Identity")
        val localKeyId = DEROctetString(MessageDigest.getInstance("SHA-1").digest(keyPair.public.encoded))
        val keyBag = PKCS12SafeBagBuilder(PrivateKeyInfo.getInstance(keyPair.private.encoded))
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val certBag = JcaPKCS12SafeBagBuilder(cert)
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, friendlyName)
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, localKeyId)
            .build()
        val pfx = PKCS12PfxPduBuilder()
            .addData(keyBag)
            .addData(certBag)
            .build(JcePKCS12MacCalculatorBuilder().setProvider(provider), CharArray(0))
        return pfx.getEncoded(ASN1Encoding.DL)
    }
}
