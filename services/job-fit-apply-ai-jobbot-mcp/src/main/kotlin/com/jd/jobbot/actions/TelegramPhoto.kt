package com.jd.jobbot.actions

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Makes a screenshot something Telegram's sendPhoto will accept. A full-page capture of a long application form is
 * thousands of pixels tall, and Telegram refuses a photo whose width plus height is over 10,000, whose sides differ
 * by more than 20:1, or whose file is over 10 MB (`Photo_invalid_dimensions`). Margins are kept below each limit.
 * An image already within them is returned untouched; one that cannot be decoded is returned as it came.
 */
object TelegramPhoto {
    const val MAX_SUM = 9_000
    const val MAX_RATIO = 15
    const val MAX_BYTES = 9L * 1024 * 1024

    fun fit(image: ByteArray): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(image)) }.getOrNull() ?: return image
        if (withinLimits(decoded.width, decoded.height) && image.size <= MAX_BYTES) return image

        var img = toRgb(decoded)
        img = cropToRatio(img)
        if (img.width + img.height > MAX_SUM) img = cropToRatio(scaleTo(img, MAX_SUM.toDouble() / (img.width + img.height)))

        // Still too heavy as a PNG: a JPEG of the same picture, shrinking until it fits.
        var out = encode(img, "png")
        var attempts = 0
        while (out.size > MAX_BYTES && attempts++ < 5) {
            out = encode(img, "jpg")
            if (out.size > MAX_BYTES) img = scaleTo(img, 0.75)
        }
        return out
    }

    fun withinLimits(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width + height <= MAX_SUM && maxOf(width, height) <= MAX_RATIO * minOf(width, height)

    /**
     * A page far taller (or wider) than it is thick keeps its top, which is where the form starts. Applied again after
     * scaling, because whole-pixel rounding can nudge a shape a hair over the limit.
     */
    private fun cropToRatio(img: BufferedImage): BufferedImage = when {
        img.height > MAX_RATIO * img.width -> img.getSubimage(0, 0, img.width, MAX_RATIO * img.width)
        img.width > MAX_RATIO * img.height -> img.getSubimage(0, 0, MAX_RATIO * img.height, img.height)
        else -> img
    }

    private fun toRgb(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, src.width, src.height)
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun scaleTo(src: BufferedImage, factor: Double): BufferedImage {
        val w = maxOf(1, (src.width * factor).toInt())
        val h = maxOf(1, (src.height * factor).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun encode(img: BufferedImage, format: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        if (format == "jpg") {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            ImageIO.createImageOutputStream(bytes).use { out ->
                writer.output = out
                writer.write(null, IIOImage(img, null, null), params)
            }
            writer.dispose()
        } else {
            ImageIO.write(img, "png", bytes)
        }
        return bytes.toByteArray()
    }
}
