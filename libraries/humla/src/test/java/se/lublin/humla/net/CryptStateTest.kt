package se.lublin.humla.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** OCB2-AES128 behaviour as Mumble's `CryptStateOCB2` and its `TestCrypt` suite define it. */
class CryptStateTest {

    private val rawKey = ByteArray(16) { it.toByte() }
    private val nonce = byteArrayOf(
        0xff.toByte(), 0xee.toByte(), 0xdd.toByte(), 0xcc.toByte(), 0xbb.toByte(), 0xaa.toByte(),
        0x99.toByte(), 0x88.toByte(), 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00,
    )
    private val secret = "abcdefghi\u0000".toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    /** A sender and receiver sharing one key, the sender's encrypt IV forced to 0x55.. as upstream does. */
    private fun pair(): Pair<CryptState, CryptState> {
        val encIv = ByteArray(16) { 0x55 }
        val decIv = ByteArray(16)
        val enc = CryptState().apply { setKeys(rawKey, encIv, decIv) }
        val dec = CryptState().apply { setKeys(rawKey, decIv, encIv) }
        return enc to dec
    }

    private fun CryptState.accepts(packet: ByteArray) = decrypt(packet, packet.size) != null

    @Test
    fun `matches the OCB draft test vectors`() {
        val cs = CryptState().apply { setKeys(rawKey, rawKey, rawKey) }
        val tag = ByteArray(16)
        assertThat(cs.ocbEncrypt(ByteArray(0), ByteArray(0), 0, rawKey, tag)).isTrue()
        assertThat(tag).isEqualTo(
            bytes(0xBF, 0x31, 0x08, 0x13, 0x07, 0x73, 0xAD, 0x5E, 0xC7, 0x0E, 0xC6, 0x9E, 0x78, 0x75, 0xA7, 0xB0)
        )

        val source = ByteArray(40) { it.toByte() }
        val crypt = ByteArray(40)
        assertThat(cs.ocbEncrypt(source, crypt, 40, rawKey, tag)).isTrue()
        assertThat(tag).isEqualTo(
            bytes(0x9D, 0xB0, 0xCD, 0xF8, 0x80, 0xF7, 0x3E, 0x3E, 0x10, 0xD4, 0xEB, 0x32, 0x17, 0x76, 0x66, 0x88)
        )
        assertThat(crypt).isEqualTo(
            bytes(
                0xF7, 0x5D, 0x6B, 0xC8, 0xB4, 0xDC, 0x8D, 0x66, 0xB8, 0x36, 0xA2, 0xB0, 0x8B, 0x32,
                0xA6, 0x36, 0x9F, 0x1C, 0xD3, 0xC5, 0x22, 0x8D, 0x79, 0xFD, 0x6C, 0x26, 0x7F, 0x5F,
                0x6A, 0xA7, 0xB2, 0x31, 0xC7, 0xDF, 0xB9, 0xD5, 0x99, 0x51, 0xAE, 0x9C,
            )
        )
    }

    @Test
    fun `encrypt and decrypt round trip for every length below 128`() {
        for (len in 0 until 128) {
            val cs = CryptState().apply { setKeys(rawKey, nonce, nonce) }
            val src = ByteArray(len) { (it + 1).toByte() }
            val encrypted = ByteArray(len)
            val decrypted = ByteArray(len)
            val encTag = ByteArray(16)
            val decTag = ByteArray(16)
            assertWithMessage("encrypt len=$len").that(cs.ocbEncrypt(src, encrypted, len, nonce, encTag)).isTrue()
            assertWithMessage("decrypt len=$len").that(cs.ocbDecrypt(encrypted, decrypted, len, nonce, decTag)).isTrue()
            assertWithMessage("tag len=$len").that(decTag).isEqualTo(encTag)
            assertWithMessage("plain len=$len").that(decrypted).isEqualTo(src)
        }
    }

    @Test
    fun `detects and prevents the XEX star attack`() {
        val cs = CryptState().apply { setKeys(rawKey, nonce, nonce) }
        val src = ByteArray(32)
        src[15] = (16 * 8).toByte()
        src.fill(42, 16, 32)
        val encrypted = ByteArray(32)
        val decrypted = ByteArray(32)
        val encTag = ByteArray(16)
        val decTag = ByteArray(16)

        val encryptOk = cs.ocbEncrypt(src, encrypted, 32, nonce, encTag, false)

        encrypted[15] = (encrypted[15].toInt() xor (16 * 8)).toByte()
        for (i in 0 until 16) encTag[i] = (src[16 + i].toInt() xor encrypted[16 + i].toInt()).toByte()
        val decryptOk = cs.ocbDecrypt(encrypted, decrypted, 16, nonce, decTag)

        assertWithMessage("the forgery itself works").that(decTag).isEqualTo(encTag)
        assertWithMessage("encrypt refuses the critical block").that(encryptOk).isFalse()
        assertWithMessage("decrypt detects the forgery").that(decryptOk).isFalse()

        assertThat(cs.ocbEncrypt(src, encrypted, 32, nonce, encTag)).isTrue()
        assertThat(cs.ocbDecrypt(encrypted, decrypted, 32, nonce, decTag)).isTrue()
        assertThat(decTag).isEqualTo(encTag)
        assertThat(src[0]).isEqualTo(0.toByte())
        assertWithMessage("a bit of the critical block is flipped").that(decrypted[0]).isEqualTo(1.toByte())
    }

    @Test
    fun `recovers the IV after loss and wraparound and refuses reuse`() {
        val (enc, dec) = pair()
        var crypted = enc.encrypt(secret, 10)
        assertThat(dec.decrypt(crypted, 14)).isEqualTo(secret)
        assertWithMessage("same IV twice").that(dec.accepts(crypted)).isFalse()

        repeat(16) { crypted = enc.encrypt(secret, 10) }
        assertThat(dec.accepts(crypted)).isTrue()

        repeat(128) { round ->
            dec.mUiLost = 0
            repeat(15) { crypted = enc.encrypt(secret, 10) }
            assertWithMessage("round $round").that(dec.accepts(crypted)).isTrue()
            assertWithMessage("lost in round $round").that(dec.mUiLost).isEqualTo(14)
        }
        assertThat(dec.decryptIV).isEqualTo(enc.encryptIV)

        repeat(257) { crypted = enc.encrypt(secret, 10) }
        assertWithMessage("wrapped too far").that(dec.accepts(crypted)).isFalse()

        dec.setDecryptIV(enc.encryptIV)
        crypted = enc.encrypt(secret, 10)
        assertThat(dec.accepts(crypted)).isTrue()
    }

    @Test
    fun `accepts late packets within 30 once and rejects replays`() {
        val (enc, dec) = pair()
        val first = List(128) { enc.encrypt(secret, 10) }
        for (i in 0 until 30) assertWithMessage("late $i").that(dec.accepts(first[127 - i])).isTrue()
        for (i in 30 until 128) assertWithMessage("too late $i").that(dec.accepts(first[127 - i])).isFalse()
        for (i in 0 until 30) assertWithMessage("replayed late $i").that(dec.accepts(first[127 - i])).isFalse()

        val second = List(512) { enc.encrypt(secret, 10) }
        for (i in 0 until 512) assertWithMessage("in order $i").that(dec.accepts(second[i])).isTrue()
        for (i in 0 until 512) assertWithMessage("replay $i").that(dec.accepts(second[i])).isFalse()
    }

    @Test
    fun `counts lost packets as unsigned IV distance`() {
        val (enc, dec) = pair()
        // Move both sides past 0x80 so the low IV byte is negative as a signed byte.
        var crypted = enc.encrypt(secret, 10)
        repeat(0x30) { crypted = enc.encrypt(secret, 10) }
        assertThat(dec.accepts(crypted)).isTrue()
        dec.mUiLost = 0
        repeat(5) { crypted = enc.encrypt(secret, 10) }
        assertThat(crypted[0].toInt() and 0xFF).isGreaterThan(0x80)
        assertThat(dec.accepts(crypted)).isTrue()
        assertThat(dec.mUiLost).isEqualTo(4)
    }

    @Test
    fun `rejects every single bit flip`() {
        val cs = CryptState().apply { setKeys(rawKey, nonce, nonce) }
        val msg = "It was a funky funky town!\u0000".toByteArray(Charsets.US_ASCII)
        val encrypted = cs.encrypt(msg, msg.size)
        for (i in 0 until msg.size * 8) {
            encrypted[i / 8] = (encrypted[i / 8].toInt() xor (1 shl (i % 8))).toByte()
            assertWithMessage("bit $i").that(cs.accepts(encrypted)).isFalse()
            encrypted[i / 8] = (encrypted[i / 8].toInt() xor (1 shl (i % 8))).toByte()
        }
        assertThat(cs.accepts(encrypted)).isTrue()
    }
}
