package com.betona.printdriver

/**
 * ESC/POS command builder for JY-P1000 3" thermal receipt printer.
 * All commands return ByteArray for binary-safe transmission to /dev/printer.
 */
object EscPosCommands {

    // Control bytes
    private const val LF: Byte = 0x0A
    private const val FF: Byte = 0x0C
    private const val ESC: Byte = 0x1B
    private const val FS: Byte = 0x1C
    private const val GS: Byte = 0x1D

    // ── Initialization ───────────────────────────────────────────────────

    /** ESC @ - Initialize printer */
    fun initialize(): ByteArray = byteArrayOf(ESC, 0x40)

    // ── Feed / Line ──────────────────────────────────────────────────────

    /** LF */
    fun lineFeed(): ByteArray = byteArrayOf(LF)

    /** ESC d n - Print and feed n lines */
    fun feedLines(n: Int): ByteArray = byteArrayOf(ESC, 0x64, n.toByte())

    /** ESC J n - Print and feed n dots (0-255) */
    fun feedDots(n: Int): ByteArray = byteArrayOf(ESC, 0x4A, n.toByte())

    // ── Cut ──────────────────────────────────────────────────────────────

    /** GS V 0 - Full cut */
    fun fullCut(): ByteArray = byteArrayOf(GS, 0x56, 0x00)

    /** GS V 1 - Partial cut */
    fun partialCut(): ByteArray = byteArrayOf(GS, 0x56, 0x01)

    /** GS V 66 n - Partial cut with n-dot feed */
    fun partialCutWithFeed(dots: Int = 0): ByteArray =
        byteArrayOf(GS, 0x56, 0x42, dots.toByte())

    // ── Alignment ────────────────────────────────────────────────────────

    /** ESC a n - 0=left, 1=center, 2=right */
    fun justify(n: Int): ByteArray = byteArrayOf(ESC, 0x61, n.toByte())
    fun justifyLeft(): ByteArray = justify(0)
    fun justifyCenter(): ByteArray = justify(1)
    fun justifyRight(): ByteArray = justify(2)

    // ── Text Style ───────────────────────────────────────────────────────

    /** ESC E n - Bold on/off */
    fun bold(on: Boolean): ByteArray = byteArrayOf(ESC, 0x45, if (on) 0x01 else 0x00)

    /** ESC - n - Underline: 0=off, 1=1dot, 2=2dot */
    fun underline(n: Int): ByteArray = byteArrayOf(ESC, 0x2D, n.toByte())

    /** GS ! n - Character size (upper=width multiplier, lower=height multiplier) */
    fun charSize(width: Int, height: Int): ByteArray =
        byteArrayOf(GS, 0x21, ((width - 1) shl 4 or (height - 1)).toByte())

    /** Reset character size to normal (1x1) */
    fun charSizeNormal(): ByteArray = byteArrayOf(GS, 0x21, 0x00)

    // ── Line Spacing ─────────────────────────────────────────────────────

    /** ESC 2 - Default line spacing */
    fun defaultLineSpacing(): ByteArray = byteArrayOf(ESC, 0x32)

    /** ESC 3 n - Set line spacing to n dots */
    fun setLineSpacing(n: Int): ByteArray = byteArrayOf(ESC, 0x33, n.toByte())

    // ── Brightness ───────────────────────────────────────────────────────

    /** FS B n - Set brightness/density (1-8) */
    fun setBrightness(n: Int): ByteArray = byteArrayOf(FS, 0x42, n.toByte())

    // ── Raster Bit Image ─────────────────────────────────────────────────

    /**
     * GS v 0 m xL xH yL yH d1...dk - Print raster bit image.
     *
     * @param m 0=normal, 1=double-width, 2=double-height, 3=quadruple
     * @param widthBytes image width in bytes (pixels / 8)
     * @param heightDots image height in dots (rows)
     * @param imageData 1bpp monochrome data, MSB first
     */
    fun rasterBitImage(m: Int, widthBytes: Int, heightDots: Int, imageData: ByteArray): ByteArray {
        return rasterBitImageHeader(m, widthBytes, heightDots) + imageData
    }

    /** GS v 0 header only (without image data). */
    fun rasterBitImageHeader(m: Int, widthBytes: Int, heightDots: Int): ByteArray {
        return byteArrayOf(
            GS, 0x76, 0x30, m.toByte(),
            (widthBytes and 0xFF).toByte(),
            ((widthBytes shr 8) and 0xFF).toByte(),
            (heightDots and 0xFF).toByte(),
            ((heightDots shr 8) and 0xFF).toByte()
        )
    }

    // ── QR Code ──────────────────────────────────────────────────────────

    /**
     * Print QR code via GS ( k commands.
     * @param moduleSize module pixel size (1-16)
     * @param data QR content string
     */
    fun printQrCode(moduleSize: Int, data: String): ByteArray {
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        val storeLen = dataBytes.size + 3

        val buf = mutableListOf<Byte>()

        // Select model 2
        buf.addAll(byteArrayOf(GS, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00).toList())

        // Set module size
        buf.addAll(byteArrayOf(GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, moduleSize.toByte()).toList())

        // Set error correction (level L = 48)
        buf.addAll(byteArrayOf(GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x30).toList())

        // Store data
        buf.addAll(byteArrayOf(
            GS, 0x28, 0x6B,
            (storeLen and 0xFF).toByte(), ((storeLen shr 8) and 0xFF).toByte(),
            0x31, 0x50, 0x30
        ).toList())
        buf.addAll(dataBytes.toList())

        // Print
        buf.addAll(byteArrayOf(GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30).toList())

        return buf.toByteArray()
    }

    // ── Barcode ──────────────────────────────────────────────────────────

    /**
     * Print barcode.
     * @param mode 0=UPC-A, 2=JAN13/EAN13, 4=CODE39, 65=CODE128, etc.
     * @param data barcode data
     * @param height barcode height in dots
     * @param hriPosition 0=none, 1=above, 2=below, 3=both
     */
    fun printBarcode(mode: Int, data: String, height: Int = 80, hriPosition: Int = 2): ByteArray {
        val dataBytes = data.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(GS, 0x48, hriPosition.toByte()) +        // HRI position
               byteArrayOf(GS, 0x68, height.toByte()) +             // height
               byteArrayOf(GS, 0x6B, mode.toByte()) +               // GS k m
               dataBytes + byteArrayOf(0x00)                         // data + NUL
    }

    // ── Cash Drawer ──────────────────────────────────────────────────────

    /** ESC p m t1 t2 - Pulse cash drawer */
    fun cashDrawerPulse(m: Int = 0, t1: Int = 25, t2: Int = 250): ByteArray =
        byteArrayOf(ESC, 0x70, m.toByte(), t1.toByte(), t2.toByte())

    // ── Utility ──────────────────────────────────────────────────────────

    /** Combine multiple commands into one ByteArray */
    fun combine(vararg commands: ByteArray): ByteArray {
        val total = commands.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for (cmd in commands) {
            cmd.copyInto(result, offset)
            offset += cmd.size
        }
        return result
    }
}
