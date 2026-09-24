package se.lublin.mumla.chat

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Native graphics: under legacy graphics `Bitmap.compress` is a different JPEG encoder from the
 * device's, and every number here is about JPEG sizes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutgoingImageEncoderTest {

    /**
     * Noise, not a flat fill: a flat 600x400 bitmap compresses to within three bytes at every rung
     * of the quality ladder, while noise spans 276 933 -> 14 809 bytes, strictly decreasing.
     */
    private fun noisyBitmap(width: Int = 600, height: Int = 400): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun bitmap() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    /** The test's own oracle for "what the encoder would have produced at this rung". */
    private fun jpegAt(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        assertThat(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)).isTrue()
        return stream.toByteArray()
    }

    private fun rungs() = generateSequence(OutgoingImageEncoder.START_QUALITY) {
        (it - OutgoingImageEncoder.QUALITY_STEP).takeIf { next -> next > 0 }
    }.toList()

    @Test
    fun percentEncodesTheBase64PayloadLikeMumbleClientsDo() {
        assertThat(OutgoingImageEncoder.imageHtml(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
            .isEqualTo("<img src=\"data:image/jpeg;base64,%2F9g%3D\"/>")
    }

    @Test
    fun theEncodedImageRoundTripsThroughTheParserAndImageSource() {
        val jpeg = OutgoingImageEncoder.compressToFit(bitmap(), 0)!!
        val html = OutgoingImageEncoder.imageHtml(jpeg)

        val content = ChatContentParser("[image]").parse(html) as ChatContent.Image
        val source = ImageSource.parse(content.source) as ImageSource.Data
        assertThat(source.bytes).isEqualTo(jpeg)
    }

    @Test
    fun zeroMeansTheServerDeclaredNoLimit() {
        val jpeg = OutgoingImageEncoder.compressToFit(noisyBitmap(), 0)
        assertThat(jpeg).isNotNull()
        // Not merely non-null: no rung was climbed down, so it is the best quality the ladder has.
        assertThat(jpeg!!.size).isEqualTo(jpegAt(noisyBitmap(), OutgoingImageEncoder.START_QUALITY).size)
    }

    @Test
    fun aNegativeLimitIsTreatedAsNoLimitRatherThanAsUnsendable() {
        val jpeg = OutgoingImageEncoder.compressToFit(noisyBitmap(), -1)
        assertThat(jpeg).isNotNull()
        assertThat(jpeg!!.size).isEqualTo(jpegAt(noisyBitmap(), OutgoingImageEncoder.START_QUALITY).size)
    }

    /**
     * Murmur limits `QString::length()` of the whole message, markup and percent-encoding included
     * (`Server::isTextAllowed`), not the raw base64 length.
     */
    @Test
    fun theResultFitsTheLimitTheServerActuallyMeasures() {
        val bitmap = noisyBitmap()
        val best = jpegAt(bitmap, OutgoingImageEncoder.START_QUALITY)
        // One character short of what the best rung needs.
        val limit = OutgoingImageEncoder.imageHtml(best).length - 1
        assertThat(4 * (best.size / 3) + 4).isLessThan(limit)

        val html = OutgoingImageEncoder.encode(bitmap, limit)!!
        assertThat(html.length).isAtMost(limit)
        assertThat(OutgoingImageEncoder.compressToFit(bitmap, limit)!!.size)
            .isEqualTo(jpegAt(bitmap, OutgoingImageEncoder.START_QUALITY - OutgoingImageEncoder.QUALITY_STEP).size)
    }

    /** The other side of the same boundary: Murmur rejects `>` the limit, so `==` is sendable. */
    @Test
    fun aMessageExactlyOnTheLimitIsSent() {
        val bitmap = noisyBitmap()
        val best = jpegAt(bitmap, OutgoingImageEncoder.START_QUALITY)
        val limit = OutgoingImageEncoder.imageHtml(best).length

        assertThat(OutgoingImageEncoder.compressToFit(bitmap, limit)!!.size).isEqualTo(best.size)
        assertThat(OutgoingImageEncoder.encode(bitmap, limit)!!.length).isEqualTo(limit)
    }

    /**
     * Every rung of the ladder, including the last one. The fixture is checked first: a bitmap whose
     * rungs do not differ in size could not tell which rung was taken, and a flat one does not.
     */
    @Test
    fun everyRungOfTheQualityLadderIsReachable() {
        val bitmap = noisyBitmap()
        val sizes = rungs().map { jpegAt(bitmap, it).size }
        assertThat(rungs()).isEqualTo(listOf(97, 87, 77, 67, 57, 47, 37, 27, 17, 7))
        assertThat(sizes).isInStrictOrder(Comparator<Int> { a, b -> b.compareTo(a) })

        for ((index, quality) in rungs().withIndex()) {
            val limit = OutgoingImageEncoder.imageHtml(jpegAt(bitmap, quality)).length
            assertThat(OutgoingImageEncoder.compressToFit(bitmap, limit)!!.size).isEqualTo(sizes[index])
        }
    }

    @Test
    fun anUnreachableLimitGivesUp() {
        assertThat(OutgoingImageEncoder.compressToFit(bitmap(), 8)).isNull()
        assertThat(OutgoingImageEncoder.encode(bitmap(), 8)).isNull()
    }

    /**
     * `Bitmap.compress` returns `false` and writes nothing for an `ALPHA_8` bitmap; ignoring that
     * would send an empty `data:` image.
     */
    @Test
    fun aBitmapTheJpegEncoderRefusesIsNotSentAsAnEmptyImage() {
        val refused = Bitmap.createBitmap(32, 32, Bitmap.Config.ALPHA_8)
        assertThat(refused.compress(Bitmap.CompressFormat.JPEG, 97, ByteArrayOutputStream())).isFalse()

        assertThat(OutgoingImageEncoder.compressToFit(refused, 0)).isNull()
        assertThat(OutgoingImageEncoder.encode(refused, 0)).isNull()
    }

    @Test
    fun encodeIsCompressToFitFollowedByImageHtml() {
        val bitmap = noisyBitmap()
        val limit = 200_000
        val jpeg = OutgoingImageEncoder.compressToFit(bitmap, limit)!!
        assertThat(OutgoingImageEncoder.encode(bitmap, limit)).isEqualTo(OutgoingImageEncoder.imageHtml(jpeg))
    }
}
