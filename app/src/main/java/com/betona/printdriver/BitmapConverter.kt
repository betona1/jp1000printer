package com.betona.printdriver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Bitmap conversion utilities for thermal printing.
 * - Text → Bitmap rendering with Korean font support
 * - ARGB Bitmap → 1bpp monochrome conversion (luminance-based)
 */
object BitmapConverter {

    private const val DEFAULT_TEXT_SIZE = 32f

    /**
     * Convert ARGB Bitmap to 1-bit-per-pixel monochrome byte array.
     * Uses luminance thresholding: dark pixels → 1 (print dot), light → 0 (no dot).
     * Transparent pixels are treated as white (no dot).
     *
     * @param bitmap source ARGB bitmap (width must be multiple of 8)
     * @return monochrome byte array, [width/8] bytes per row
     */
    fun toMonochrome(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = width / 8
        val result = ByteArray(bytesPerRow * height)

        val pixels = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (byteIdx in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0 until 8) {
                    val px = byteIdx * 8 + bit
                    if (px < width) {
                        val pixel = pixels[px]
                        val alpha = (pixel ushr 24) and 0xFF
                        if (alpha > 0) {
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val blue = pixel and 0xFF
                            val luminance = (0.299 * r + 0.587 * g + 0.114 * blue).toInt()
                            if (luminance < 128) {
                                b = b or (0x80 shr bit)
                            }
                        }
                    }
                }
                result[y * bytesPerRow + byteIdx] = b.toByte()
            }
        }
        return result
    }

    /**
     * Render text to a monochrome-ready Bitmap (white background, black text).
     * Uses Nanum Gothic font for Korean support. Auto-wraps long lines.
     *
     * @param context Android context for asset loading
     * @param text text to render (supports \n for line breaks)
     * @param textSize font size in pixels
     * @param printWidth target width in pixels (default 576 for 80mm)
     * @return rendered Bitmap, or null if text is empty
     */
    fun textToBitmap(
        context: Context,
        text: String?,
        textSize: Float = DEFAULT_TEXT_SIZE,
        printWidth: Int = DevicePrinter.PRINT_WIDTH_PX
    ): Bitmap? {
        if (text.isNullOrEmpty()) return null

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = Color.BLACK
            typeface = loadKoreanFont(context)
        }

        val maxWidth = printWidth.toFloat()
        val linePad = 6f

        // Split by newlines, then wrap long lines
        val wrappedLines = mutableListOf<String>()
        for (line in text.split("\n")) {
            if (line.isEmpty()) {
                wrappedLines.add("")
                continue
            }
            var remaining = line
            while (remaining.isNotEmpty()) {
                val measured = paint.breakText(remaining, true, maxWidth, null)
                wrappedLines.add(remaining.substring(0, measured))
                remaining = remaining.substring(measured)
            }
        }
        if (wrappedLines.isEmpty()) return null

        val fm = paint.fontMetrics
        val lineHeight = fm.descent - fm.ascent + linePad
        val totalHeight = (lineHeight * wrappedLines.size + linePad).toInt()

        val bitmap = Bitmap.createBitmap(printWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = -fm.ascent + linePad / 2
        for (line in wrappedLines) {
            canvas.drawText(line, 0f, y, paint)
            y += lineHeight
        }
        return bitmap
    }

    /**
     * Trim trailing blank (all-zero) rows from monochrome data.
     * Keeps a small margin (marginRows) after the last row with content.
     *
     * @param monoData 1bpp monochrome byte array
     * @param widthBytes bytes per row (e.g. 72 for 576px)
     * @param marginRows extra blank rows to keep after content (default 16 ≈ 2mm)
     * @return trimmed monochrome data, or original if no trimming needed
     */
    fun trimTrailingWhiteRows(
        monoData: ByteArray,
        widthBytes: Int = DevicePrinter.PRINT_WIDTH_BYTES,
        marginRows: Int = 16
    ): ByteArray {
        val totalRows = monoData.size / widthBytes
        if (totalRows == 0) return monoData

        // Scan from bottom to find last row with content
        var lastContentRow = -1
        for (row in totalRows - 1 downTo 0) {
            val offset = row * widthBytes
            for (col in 0 until widthBytes) {
                if (monoData[offset + col] != 0.toByte()) {
                    lastContentRow = row
                    break
                }
            }
            if (lastContentRow >= 0) break
        }

        if (lastContentRow < 0) {
            // Entire image is blank - return minimal data
            return ByteArray(widthBytes)
        }

        val keepRows = minOf(lastContentRow + 1 + marginRows, totalRows)
        if (keepRows >= totalRows) return monoData

        return monoData.copyOfRange(0, keepRows * widthBytes)
    }

    /**
     * Crop white borders from bitmap (removes Chrome margins).
     * Scans for content bounds and returns cropped bitmap.
     *
     * @param bitmap source bitmap
     * @param padding extra pixels to keep around content
     * @return cropped bitmap, or original if no white borders found
     */
    fun cropWhiteBorders(bitmap: Bitmap, padding: Int = 4): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        var top = height
        var bottom = 0
        var left = width
        var right = 0

        val pixels = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = pixels[x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum < 240) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }

        if (top > bottom || left > right) return bitmap

        top = maxOf(0, top - padding)
        bottom = minOf(height - 1, bottom + padding)
        left = maxOf(0, left - padding)
        right = minOf(width - 1, right + padding)

        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * Scale bitmap to target width, maintaining aspect ratio.
     * Returns the same bitmap if already the correct width.
     */
    fun scaleToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val ratio = targetWidth.toFloat() / bitmap.width
        val newHeight = maxOf(1, (bitmap.height * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, targetWidth, newHeight, true)
    }

    @Volatile
    private var cachedFont: Typeface? = null

    private fun loadKoreanFont(context: Context): Typeface {
        cachedFont?.let { return it }
        return try {
            Typeface.createFromAsset(context.assets, "nanum_gothic.ttf").also { cachedFont = it }
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }
}
