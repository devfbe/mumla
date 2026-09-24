@file:JvmName("Hex")

package se.lublin.mumla.util

private const val DIGITS = "0123456789abcdef"

/** Lower-case hexadecimal, two digits per byte. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        out[i * 2] = DIGITS[v ushr 4]
        out[i * 2 + 1] = DIGITS[v and 0x0F]
    }
    return String(out)
}
