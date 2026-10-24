package com.jd.jobbot.actions

import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A full-page screenshot of a long form must become a photo Telegram accepts: width+height <= 10,000, ratio <= 20, <= 10 MB. */
class TelegramPhotoTest {
    @TempDir lateinit var dir: Path

    /** A page-like image: white with horizontal bands, so it compresses like a real screenshot. */
    private fun page(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, w, h)
        g.color = Color(40, 90, 200)
        var y = 20
        while (y < h) { g.fillRect(20, y, w / 2, 14); y += 60 }
        g.dispose()
    }

    private fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    private fun size(bytes: ByteArray): Pair<Int, Int> = ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }
    private fun telegramAccepts(bytes: ByteArray): Boolean {
        val (w, h) = size(bytes)
        return w + h <= 10_000 && maxOf(w, h) <= 20 * minOf(w, h) && bytes.size <= 10 * 1024 * 1024
    }

    @Test
    fun `the exact screenshot that Telegram refused now fits`() {
        val original = png(page(2834, 7278))          // 2834 + 7278 = 10,112 > 10,000: Photo_invalid_dimensions
        assertEquals(false, telegramAccepts(original), "this is the size that failed in production")
        val fitted = TelegramPhoto.fit(original)
        assertTrue(telegramAccepts(fitted), "fitted to ${size(fitted)}")
        val (w, h) = size(fitted)
        assertTrue(w + h <= TelegramPhoto.MAX_SUM, "$w x $h")
        assertEquals(2834.0 / 7278.0, w.toDouble() / h, 0.01, "the shape is kept")
    }

    @Test
    fun `a screenshot already within the limits is returned byte for byte`() {
        val ok = png(page(1280, 4000))
        assertContentEquals(ok, TelegramPhoto.fit(ok))
        val small = png(page(800, 600))
        assertContentEquals(small, TelegramPhoto.fit(small))
    }

    @Test
    fun `the boundary is respected on both sides`() {
        val at = png(page(2000, TelegramPhoto.MAX_SUM - 2000))
        assertContentEquals(at, TelegramPhoto.fit(at), "exactly at the margin is left alone")
        val over = TelegramPhoto.fit(png(page(2000, TelegramPhoto.MAX_SUM - 2000 + 1)))
        assertTrue(size(over).let { it.first + it.second } <= TelegramPhoto.MAX_SUM)
    }

    @Test
    fun `a very tall page keeps its top, within the ratio limit`() {
        val fitted = TelegramPhoto.fit(png(page(600, 12_000)))
        val (w, h) = size(fitted)
        assertTrue(h <= TelegramPhoto.MAX_RATIO * w, "$w x $h")
        assertTrue(telegramAccepts(fitted))
        // The top of the page (the blue band at y=20) survives; it was cropped, not squashed.
        val img = ImageIO.read(ByteArrayInputStream(fitted))
        assertEquals(Color(40, 90, 200).rgb and 0xFFFFFF, img.getRGB(30, 25) and 0xFFFFFF)
    }

    @Test
    fun `a very wide image is handled the same way`() {
        val fitted = TelegramPhoto.fit(png(page(12_000, 500)))
        assertTrue(telegramAccepts(fitted), size(fitted).toString())
    }

    @Test
    fun `an image too big in both directions is scaled, not cropped`() {
        val (w, h) = size(TelegramPhoto.fit(png(page(6000, 6000))))
        assertEquals(w, h)
        assertTrue(w + h <= TelegramPhoto.MAX_SUM)
        assertTrue(w > 4000, "scaled down only as far as needed: $w")
    }

    @Test
    fun `a file that is too heavy is re-encoded until it fits`() {
        // Noise does not compress: 3000x3000 random pixels is far more than 9 MB as a PNG.
        val noise = BufferedImage(3000, 3000, BufferedImage.TYPE_INT_RGB)
        val r = Random(7)
        for (y in 0 until 3000) for (x in 0 until 3000) noise.setRGB(x, y, r.nextInt(0xFFFFFF))
        val heavy = png(noise)
        assertTrue(heavy.size > TelegramPhoto.MAX_BYTES, "test image is ${heavy.size} bytes")
        val fitted = TelegramPhoto.fit(heavy)
        assertTrue(fitted.size <= TelegramPhoto.MAX_BYTES, "${fitted.size} bytes")
        assertTrue(telegramAccepts(fitted))
    }

    @Test
    fun `bytes that are not an image come back unchanged`() {
        val junk = "not a picture".toByteArray()
        assertContentEquals(junk, TelegramPhoto.fit(junk))
        assertContentEquals(ByteArray(0), TelegramPhoto.fit(ByteArray(0)))
    }

    @Test
    fun `a transparent image does not turn black`() {
        val clear = BufferedImage(3000, 8000, BufferedImage.TYPE_INT_ARGB)
        val fitted = ImageIO.read(ByteArrayInputStream(TelegramPhoto.fit(png(clear))))
        assertEquals(0xFFFFFF, fitted.getRGB(5, 5) and 0xFFFFFF, "transparent areas become white")
    }

    @Test
    fun `the outbox applies it when a screenshot is queued, and keeps small ones as they were`() {
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            val tall = o.post("Ready to submit", photo = png(page(2834, 7278)))
            val small = png(page(800, 600))
            val ok = o.post("Small", photo = small)
            assertTrue(telegramAccepts(o.photo(tall)!!), "what the plugin downloads is postable")
            assertContentEquals(small, o.photo(ok))
        }
    }
}
