package se.lublin.mumla.util

private const val DIGITS = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F

/** Lower-case hexadecimal, two digits per byte. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and BYTE_MASK
        out[i * 2] = DIGITS[v ushr NIBBLE_BITS]
        out[i * 2 + 1] = DIGITS[v and NIBBLE_MASK]
    }
    return String(out)
}
