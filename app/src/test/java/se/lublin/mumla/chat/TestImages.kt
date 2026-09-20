package se.lublin.mumla.chat

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
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

    fun dataUri(png: ByteArray): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
}
