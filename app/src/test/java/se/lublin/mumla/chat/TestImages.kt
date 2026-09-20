package se.lublin.mumla.chat

import androidx.exifinterface.media.ExifInterface
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/** Generates real PNG bytes on the JVM so decoding tests never depend on checked-in fixtures. */
object TestImages {
    fun png(width: Int, height: Int, rgb: Int = 0x336699): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(rgb)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /**
     * Real JPEG bytes. The send path is the one photographs take, and a photograph is a JPEG:
     * it is the only format whose EXIF orientation the platform decoder acts on (see
     * [OutgoingImagePreparer]), so a suite that only ever hands it PNGs never reaches that corner.
     */
    fun jpeg(width: Int, height: Int, rgb: Int = 0x336699): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(rgb)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    /**
     * Rewrites [image] with `Orientation` set to [orientation], one of `ExifInterface`'s eight
     * `ORIENTATION_*` values. Goes through a temporary file because `ExifInterface.saveAttributes`
     * needs a seekable target; [suffix] picks the container (`.jpg` writes an APP1 segment, `.png`
     * an `eXIf` chunk).
     */
    fun withExifOrientation(image: ByteArray, orientation: Int, suffix: String = ".jpg"): ByteArray {
        val file = File.createTempFile("mumla-test-image", suffix)
        try {
            file.writeBytes(image)
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    fun dataUri(png: ByteArray): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
}
