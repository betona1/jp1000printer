package com.betona.printdriver

import android.util.Log
import com.thirteenrain.jyndklib.jyNativeClass

/**
 * Low-level printer I/O for JY-P1000 kiosk via jyndklib native library.
 * /dev/printer requires ioctl-based access - standard FileOutputStream does not work.
 * Uses jyNativeClass native methods for all printer communication.
 */
class DevicePrinter {

    companion object {
        const val DEVICE_PATH = "/dev/printer"
        private const val TAG = "DevicePrinter"
        const val PRINT_WIDTH_PX = 576          // 573 dots, byte-aligned to 576 (72 bytes)
        const val PRINT_WIDTH_BYTES = 72        // 576 / 8
        const val DEFAULT_BRIGHTNESS = 4
    }

    private val native_ = jyNativeClass()
    private var opened = false

    val isOpen: Boolean get() = opened

    /**
     * Open printer via native driver.
     * @return true if opened successfully
     */
    @Synchronized
    fun open(): Boolean {
        if (opened) return true
        return try {
            val code = native_.jyPrinterOpen()
            opened = code == 0
            if (opened) {
                Log.i(TAG, "Printer opened (native)")
            } else {
                Log.e(TAG, "Printer open failed: code=$code")
            }
            opened
        } catch (e: Exception) {
            Log.e(TAG, "Printer open exception", e)
            false
        }
    }

    /**
     * Send ESC/POS command bytes to printer.
     * Uses jyPrintString for command-sized data.
     */
    @Synchronized
    fun write(data: ByteArray): Boolean {
        if (!opened) {
            Log.w(TAG, "write: printer not open")
            return false
        }
        return try {
            val result = native_.jyPrintString(data, 0)
            if (result != 0) {
                Log.w(TAG, "write: jyPrintString returned $result (${data.size} bytes)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "write failed (${data.size} bytes)", e)
            false
        }
    }

    /**
     * Send raw binary data to printer (for raster images).
     * Uses jyPrinterRawData for large binary payloads.
     */
    @Synchronized
    fun writeRaw(data: ByteArray): Boolean {
        if (!opened) {
            Log.w(TAG, "writeRaw: printer not open")
            return false
        }
        return try {
            val result = native_.jyPrinterRawData(data)
            if (result != 0) {
                Log.w(TAG, "writeRaw: jyPrinterRawData returned $result (${data.size} bytes)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeRaw failed (${data.size} bytes)", e)
            false
        }
    }

    /**
     * Close the printer.
     */
    @Synchronized
    fun close() {
        if (!opened) return
        try {
            native_.jyPrinterClose()
            Log.i(TAG, "Printer closed")
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        } finally {
            opened = false
        }
    }

    // ── Convenience methods ──────────────────────────────────────────────

    /** Initialize printer with default brightness. */
    fun initPrinter(brightness: Int = DEFAULT_BRIGHTNESS) {
        write(EscPosCommands.initialize())
        write(EscPosCommands.setBrightness(brightness))
    }

    /**
     * Print a monochrome bitmap in bands.
     * - Raster header (GS v 0 ...) → jyPrintString (command)
     * - Image data → jyPrinterRawData (raw image)
     */
    fun printBitmap(monoData: ByteArray, widthBytes: Int = PRINT_WIDTH_BYTES) {
        val totalHeight = monoData.size / widthBytes
        val maxBand = 255
        var y = 0
        while (y < totalHeight) {
            val bandHeight = minOf(maxBand, totalHeight - y)
            val offset = y * widthBytes
            val bandData = monoData.copyOfRange(offset, offset + bandHeight * widthBytes)
            // Header (command) via jyPrintString
            val header = EscPosCommands.rasterBitImageHeader(0, widthBytes, bandHeight)
            write(header)
            // Image data via jyPrinterRawData
            writeRaw(bandData)
            y += bandHeight
        }
    }

    /** Feed paper and auto-cut. Try multiple cut command formats. */
    fun feedAndCut() {
        // Feed first
        write(EscPosCommands.feedLines(5))
        // Try GS V 0 (full cut) via jyPrintString
        write(EscPosCommands.fullCut())
        Log.d(TAG, "feedAndCut: sent via write()")
    }

    // ── Status checks ────────────────────────────────────────────────────

    fun checkPaper(): Int = if (opened) native_.jyPrinter_PaperCheck() else -99
    fun checkCover(): Int = if (opened) native_.jyPrinter_CoverCheck() else -99
    fun checkOverheat(): Int = if (opened) native_.jyPrinter_OverheatCheck() else -99
}
