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
 *
 * A capture can be 46 million pixels (2834 x 16384), about 180 MB decoded, and resizing it needs a second copy, which
 * is more than jobbot-mcp's heap holds. So only the top part Telegram can show is read, and an image over
 * [MAX_DECODE_PIXELS] is read at every [ReadPlan.step]-th pixel, so memory is bounded however tall the page is.
 */
object TelegramPhoto {
    const val MAX_SUM = 9_000
    const val MAX_RATIO = 15
    const val MAX_BYTES = 9L * 1024 * 1024
    /** About 64 MB as pixels, and the resize below briefly holds two copies: well inside a 384 MB heap. */
    const val MAX_DECODE_PIXELS = 16_000_000L

    /** The part of an image worth reading: its top-left [regionWidth] x [regionHeight], taking every [step]-th pixel. */
    data class ReadPlan(val regionWidth: Int, val regionHeight: Int, val step: Int)

    fun readPlan(width: Int, height: Int): ReadPlan {
        // What Telegram can show is bounded by the ratio, and the top of a form is the part worth keeping.
        val regionWidth = minOf(width, MAX_RATIO * height)
        val regionHeight = minOf(height, MAX_RATIO * width)
        var step = 1
        while (ceilDiv(regionWidth, step).toLong() * ceilDiv(regionHeight, step) > MAX_DECODE_PIXELS) step++
        return ReadPlan(regionWidth, regionHeight, step)
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    fun fit(image: ByteArray): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val (width, height) = runCatching { dimensions(image) }.getOrNull() ?: return image
        if (withinLimits(width, height) && image.size <= MAX_BYTES) return image

        var img = runCatching { decode(image, readPlan(width, height)) }.getOrNull() ?: return image
        img = toRgb(img)
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

    /** Width and height from the image header, without decoding any pixels. */
    private fun dimensions(image: ByteArray): Pair<Int, Int>? = withReader(image) { reader -> reader.getWidth(0) to reader.getHeight(0) }

    private fun decode(image: ByteArray, plan: ReadPlan): BufferedImage? = withReader(image) { reader ->
        val param = reader.defaultReadParam.apply {
            sourceRegion = java.awt.Rectangle(0, 0, plan.regionWidth, plan.regionHeight)
            setSourceSubsampling(plan.step, plan.step, 0, 0)
        }
        reader.read(0, param)
    }

    private fun <T> withReader(image: ByteArray, use: (javax.imageio.ImageReader) -> T): T? =
        ImageIO.createImageInputStream(ByteArrayInputStream(image))?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                use(reader)
            } finally {
                reader.dispose()
            }
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
