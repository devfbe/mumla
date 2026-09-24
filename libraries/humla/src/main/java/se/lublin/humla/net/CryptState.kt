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

import androidx.annotation.VisibleForTesting
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OCB2-AES128 for the voice channel, after Mumble's `CryptStateOCB2`. The OCB patent is licensed
 * free of charge for OSI-certified open source software (http://www.cs.ucdavis.edu/~rogaway/ocb/license.htm).
 */
class CryptState {
    private var encryptIv = ByteArray(AES_BLOCK_SIZE)
    private var decryptIv = ByteArray(AES_BLOCK_SIZE)
    private val decryptHistory = ByteArray(IV_RANGE)
    private lateinit var encryptCipher: Cipher
    private lateinit var decryptCipher: Cipher
    private var lastGoodStart = 0L
    private var lastRequestStart = 0L

    /** Packets this side decrypted, received late, lost, and resyncs it asked for. */
    internal var good = 0
    internal var late = 0
    internal var lost = 0
    internal var resync = 0

    /** The same counters as the server reported them for its side. */
    internal var remoteGood = 0
    internal var remoteLate = 0
    internal var remoteLost = 0
    internal var remoteResync = 0

    /** True once [setKeys] has run. */
    var isValid = false
        private set

    /** Microseconds since the last packet that decrypted. */
    val lastGoodElapsed: Long
        get() = (System.nanoTime() - lastGoodStart) / NANOS_PER_MICRO

    /** Microseconds since the last [resetLastRequestTime]. */
    val lastRequestElapsed: Long
        get() = (System.nanoTime() - lastRequestStart) / NANOS_PER_MICRO

    fun resetLastRequestTime() {
        lastRequestStart = System.nanoTime()
    }

    /** A copy of the current encrypt IV. */
    val encryptIV: ByteArray
        @Synchronized get() = encryptIv.clone()

    /** A copy of the current decrypt IV. */
    val decryptIV: ByteArray
        @Synchronized get() = decryptIv.clone()

    @Synchronized
    fun setDecryptIV(iv: ByteArray) {
        decryptIv = iv.clone()
    }

    /** Keys both directions; the arrays are copied. */
    @Synchronized
    fun setKeys(rawKey: ByteArray, encryptIv: ByteArray, decryptIv: ByteArray) {
        val key = SecretKeySpec(rawKey, "AES")
        this.encryptIv = encryptIv.copyOf(AES_BLOCK_SIZE)
        this.decryptIv = decryptIv.copyOf(AES_BLOCK_SIZE)
        encryptCipher = Cipher.getInstance(AES_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        decryptCipher = Cipher.getInstance(AES_TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key) }
        isValid = true
    }

    /**
     * Decrypts one voice packet: an IV byte, three tag bytes, then the cipher text.
     * @return the plain text, or null for a packet that is too short, forged, replayed or too late.
     */
    @Synchronized
    fun decrypt(source: ByteArray, length: Int): ByteArray? {
        if (length < HEADER_SIZE) return null
        val saveIv = decryptIv.copyOf()
        val lostNow = advanceDecryptIv(source[0].toInt() and BYTE_MASK)
        val plain = if (lostNow == REJECTED) null else open(source, length)
        if (plain == null || lostNow == LATE) saveIv.copyInto(decryptIv)
        if (plain != null) {
            good++
            if (lostNow == LATE) late++
            lost += lostNow
            lastGoodStart = System.nanoTime()
        }
        return plain
    }

    /**
     * Moves the decrypt IV to the packet's IV byte.
     * @return the packets lost since the last one, [LATE] for a late packet (the IV must be restored
     *         afterwards), or [REJECTED] for a repeat or a packet too far out of order.
     */
    private fun advanceDecryptIv(ivByte: Int): Int {
        val current = decryptIv[0].toInt() and BYTE_MASK
        if (((current + 1) and BYTE_MASK) != ivByte) return advanceOutOfOrder(ivByte, current)
        decryptIv[0] = ivByte.toByte()
        if (ivByte < current) increment(decryptIv, from = 1)
        return 0
    }

    /** [advanceDecryptIv] for a packet that is not the next one: late, after a loss, or a repeat. */
    private fun advanceOutOfOrder(ivByte: Int, current: Int): Int {
        var diff = ivByte - current
        if (diff > IV_HALF_RANGE) diff -= IV_RANGE else if (diff < -IV_HALF_RANGE) diff += IV_RANGE
        // Past the low byte's wrap when the IV byte moved against the direction of diff.
        val wrapped = (ivByte > current) != (diff > 0)
        val lostNow = when {
            diff > -LATE_WINDOW && diff < 0 -> {
                // Late: last was 0x02, here comes 0xff from before the wrap.
                if (wrapped) decrementAboveLowByte(decryptIv)
                LATE
            }
            diff > 0 -> {
                // Lost a few packets, maybe wrapping around.
                if (wrapped) increment(decryptIv, from = 1)
                (ivByte - current - 1).mod(IV_RANGE)
            }
            else -> REJECTED
        }
        if (lostNow == REJECTED) return REJECTED
        decryptIv[0] = ivByte.toByte()
        return if (decryptHistory[ivByte] == decryptIv[1]) REJECTED else lostNow
    }

    /** Authenticates and decrypts [source] under the current decrypt IV, recording it as seen. */
    private fun open(source: ByteArray, length: Int): ByteArray? {
        val plainLength = length - HEADER_SIZE
        val plain = ByteArray(plainLength)
        val tag = ByteArray(AES_BLOCK_SIZE)
        val ocbOk = ocbDecrypt(source.copyOfRange(HEADER_SIZE, length), plain, plainLength, decryptIv, tag)
        if (!ocbOk || (0 until HEADER_SIZE - 1).any { tag[it] != source[it + 1] }) return null
        decryptHistory[decryptIv[0].toInt() and BYTE_MASK] = decryptIv[1]
        return plain
    }

    /** Encrypts one voice packet into the layout [decrypt] reads, advancing the encrypt IV. */
    @Synchronized
    fun encrypt(source: ByteArray, length: Int): ByteArray {
        increment(encryptIv, from = 0)
        val tag = ByteArray(AES_BLOCK_SIZE)
        val dst = ByteArray(length + HEADER_SIZE)
        ocbEncrypt(source, dst, length, encryptIv, tag)
        dst.copyInto(dst, destinationOffset = HEADER_SIZE, startIndex = 0, endIndex = length)
        dst[0] = encryptIv[0]
        tag.copyInto(dst, destinationOffset = 1, endIndex = HEADER_SIZE - 1)
        return dst
    }

    /** @return false if the input looks like the XEX* forgery, whose last block decrypts to delta ^ len. */
    @VisibleForTesting
    internal fun ocbDecrypt(
        encrypted: ByteArray,
        plain: ByteArray,
        length: Int,
        nonce: ByteArray,
        tag: ByteArray,
    ): Boolean {
        val checksum = ByteArray(AES_BLOCK_SIZE)
        val tmp = ByteArray(AES_BLOCK_SIZE)
        val buffer = ByteArray(AES_BLOCK_SIZE)
        val delta = encryptCipher.doFinal(nonce)

        var offset = 0
        var len = length
        while (len > AES_BLOCK_SIZE) {
            s2(delta)
            encrypted.copyInto(buffer, startIndex = offset, endIndex = offset + AES_BLOCK_SIZE)
            xor(tmp, delta, buffer)
            decryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tmp)
            xor(buffer, delta, tmp)
            buffer.copyInto(plain, destinationOffset = offset)
            xor(checksum, checksum, buffer)
            len -= AES_BLOCK_SIZE
            offset += AES_BLOCK_SIZE
        }

        s2(delta)
        tmp.fill(0)
        putBitLength(tmp, len)
        xor(tmp, tmp, delta)
        val pad = encryptCipher.doFinal(tmp)
        tmp.fill(0)
        encrypted.copyInto(tmp, startIndex = offset, endIndex = offset + len)
        xor(tmp, tmp, pad)
        xor(checksum, checksum, tmp)
        tmp.copyInto(plain, destinationOffset = offset, endIndex = len)

        // XEX* forgery (https://eprint.iacr.org/2019/311, section 9): len only touches the last byte.
        var success = false
        for (i in 0 until AES_BLOCK_SIZE - 1) {
            if (tmp[i] != delta[i]) {
                success = true
                break
            }
        }

        s3(delta)
        xor(tmp, delta, checksum)
        encryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tag)
        return success
    }

    /**
     * @param modifyPlainOnXexStarAttack flip a bit of a block the XEX* attack needs instead of
     *        refusing it: digital silence produces such blocks.
     * @return false if a critical block was found and not modified.
     */
    @VisibleForTesting
    @Suppress("LongParameterList")
    internal fun ocbEncrypt(
        plain: ByteArray,
        encrypted: ByteArray,
        plainLength: Int,
        nonce: ByteArray,
        tag: ByteArray,
        modifyPlainOnXexStarAttack: Boolean = true,
    ): Boolean {
        val checksum = ByteArray(AES_BLOCK_SIZE)
        val tmp = ByteArray(AES_BLOCK_SIZE)
        val buffer = ByteArray(AES_BLOCK_SIZE)
        val delta = encryptCipher.doFinal(nonce)

        var success = true
        var offset = 0
        var len = plainLength
        while (len > AES_BLOCK_SIZE) {
            // XEX* counter-measure (https://eprint.iacr.org/2019/311, section 9): the attack needs
            // the second to last block to be all zero except its last byte.
            val critical = len - AES_BLOCK_SIZE <= AES_BLOCK_SIZE && isXexStarCritical(plain, offset)
            val flipABit = critical && modifyPlainOnXexStarAttack
            if (critical && !modifyPlainOnXexStarAttack) success = false

            s2(delta)
            plain.copyInto(buffer, startIndex = offset, endIndex = offset + AES_BLOCK_SIZE)
            xor(tmp, delta, buffer)
            if (flipABit) tmp[0] = (tmp[0].toInt() xor 1).toByte()
            encryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tmp)
            xor(checksum, checksum, buffer)
            if (flipABit) checksum[0] = (checksum[0].toInt() xor 1).toByte()
            xor(buffer, delta, tmp)
            buffer.copyInto(encrypted, destinationOffset = offset)
            len -= AES_BLOCK_SIZE
            offset += AES_BLOCK_SIZE
        }

        s2(delta)
        tmp.fill(0)
        putBitLength(tmp, len)
        xor(tmp, tmp, delta)
        val pad = encryptCipher.doFinal(tmp)
        plain.copyInto(tmp, startIndex = offset, endIndex = offset + len)
        pad.copyInto(tmp, destinationOffset = len, startIndex = len)
        xor(checksum, checksum, tmp)
        xor(tmp, pad, tmp)
        tmp.copyInto(encrypted, destinationOffset = offset, endIndex = len)

        s3(delta)
        xor(tmp, delta, checksum)
        encryptCipher.doFinal(tmp, 0, AES_BLOCK_SIZE, tag)
        return success
    }

    companion object {
        const val AES_BLOCK_SIZE = 16
        private const val AES_TRANSFORMATION = "AES/ECB/NoPadding"
        private const val SHIFT_BITS = 7
        /** The GF(2^128) reduction polynomial's low byte. */
        private const val GF_REDUCTION = 0x87
        private const val BYTE_MASK = 0xFF
        /** The IV byte and three tag bytes in front of the cipher text. */
        private const val HEADER_SIZE = 4
        private const val IV_RANGE = 256
        private const val IV_HALF_RANGE = IV_RANGE / 2
        /** How far behind the newest packet a late one is still accepted. */
        private const val LATE_WINDOW = 30
        private const val LATE = -1
        private const val REJECTED = Int.MIN_VALUE
        private const val NANOS_PER_MICRO = 1000

        private fun xor(dst: ByteArray, a: ByteArray, b: ByteArray) {
            for (i in 0 until AES_BLOCK_SIZE) dst[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }

        /** Multiplies [block] by 2 in GF(2^128). */
        private fun s2(block: ByteArray) {
            val carry = (block[0].toInt() shr SHIFT_BITS) and 1
            for (i in 0 until AES_BLOCK_SIZE - 1) {
                block[i] = ((block[i].toInt() shl 1) or ((block[i + 1].toInt() shr SHIFT_BITS) and 1)).toByte()
            }
            block[AES_BLOCK_SIZE - 1] = ((block[AES_BLOCK_SIZE - 1].toInt() shl 1) xor (carry * GF_REDUCTION)).toByte()
        }

        /** Multiplies [block] by 3 in GF(2^128). */
        private fun s3(block: ByteArray) {
            val carry = (block[0].toInt() shr SHIFT_BITS) and 1
            for (i in 0 until AES_BLOCK_SIZE - 1) {
                val shifted = (block[i].toInt() shl 1) or ((block[i + 1].toInt() shr SHIFT_BITS) and 1)
                block[i] = (block[i].toInt() xor shifted).toByte()
            }
            val last = block[AES_BLOCK_SIZE - 1].toInt()
            block[AES_BLOCK_SIZE - 1] = (last xor ((last shl 1) xor (carry * GF_REDUCTION))).toByte()
        }

        /** Writes the bit length of the final partial block into the last two bytes. */
        private fun putBitLength(block: ByteArray, len: Int) {
            val bits = len * Byte.SIZE_BITS
            block[AES_BLOCK_SIZE - 2] = (bits shr Byte.SIZE_BITS).toByte()
            block[AES_BLOCK_SIZE - 1] = bits.toByte()
        }

        /** Increments the little-endian counter [iv], starting at byte [from]. */
        private fun increment(iv: ByteArray, from: Int) {
            for (i in from until AES_BLOCK_SIZE) {
                iv[i]++
                if (iv[i] != 0.toByte()) break
            }
        }

        /** True for a block of zeros but for its last byte, the block the XEX* attack needs. */
        private fun isXexStarCritical(plain: ByteArray, offset: Int): Boolean =
            (0 until AES_BLOCK_SIZE - 1).all { plain[offset + it] == 0.toByte() }

        /** Decrements [iv] above its low byte, borrowing upwards. */
        private fun decrementAboveLowByte(iv: ByteArray) {
            for (i in 1 until AES_BLOCK_SIZE) {
                val old = iv[i]
                iv[i]--
                if (old != 0.toByte()) break
            }
        }
    }
}
