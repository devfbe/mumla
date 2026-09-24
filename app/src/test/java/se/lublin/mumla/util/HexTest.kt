package se.lublin.mumla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HexTest {
    @Test
    fun encodesEachByteAsTwoLowerCaseDigits() {
        assertThat(byteArrayOf(0x00, 0x0f, 0x10, 0x7f, 0x80.toByte(), 0xff.toByte()).toHex())
            .isEqualTo("000f107f80ff")
    }

    @Test
    fun anEmptyArrayIsAnEmptyString() {
        assertThat(ByteArray(0).toHex()).isEmpty()
    }
}
