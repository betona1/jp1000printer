package com.betona.printdriver

import android.util.Log
import com.thirteenrain.jyndklib.jyNativeClass

/**
 * Low-level printer I/O for JY-P1000 kiosk via jyndklib native library.
 * Singleton - native fd is opened once and shared across the process.
 */
object DevicePrinter {

    const val DEVICE_PATH = "/dev/printer"
    private const val TAG = "DevicePrinter"
    const val PRINT_WIDTH_PX = 576
    const val PRINT_WIDTH_BYTES = 72
    const val DEFAULT_BRIGHTNESS = 4

    private val native_ = jyNativeClass()
    private var opened = false

    val isOpen: Boolean get() = opened

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

    @Synchronized
    fun writeSync(data: ByteArray): Boolean {
        if (!opened) return false
        return try {
            val result = native_.jyPrintString(data, 1)
            Log.d(TAG, "writeSync: result=$result (${data.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeSync failed", e)
            false
        }
    }

    // ── Convenience methods ──────────────────────────────────────────────

    /**
     * Initialize printer. Combined into single jyPrintString call.
     */
    @Synchronized
    fun initPrinter(brightness: Int = DEFAULT_BRIGHTNESS) {
        write(EscPosCommands.initialize() + EscPosCommands.setBrightness(brightness))
    }

    /**
     * Print a monochrome bitmap + feed + cut in minimal jyPrintString calls.
     * Combines raster header with image data, and feed with cut command.
     * Minimizing calls reduces heap corruption from native library bug.
     */
    @Synchronized
    fun printBitmapAndCut(monoData: ByteArray, widthBytes: Int = PRINT_WIDTH_BYTES, fullCut: Boolean = true) {
        if (monoData.isEmpty() || widthBytes <= 0) {
            Log.w(TAG, "printBitmapAndCut: empty data or invalid widthBytes")
            return
        }
        val totalHeight = monoData.size / widthBytes
        Log.d(TAG, "printBitmapAndCut: ${widthBytes}x${totalHeight} = ${monoData.size} bytes")

        // Combine header + image data into one large buffer, send in big chunks
        val header = EscPosCommands.rasterBitImageHeader(0, widthBytes, totalHeight)
        val payload = header + monoData
        val chunkSize = 65536  // 64KB chunks - fewer calls
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            write(payload.copyOfRange(offset, end))
            offset = end
        }

        // Feed + cut via sync write to ensure cut is fully processed
        val cutCmd = if (fullCut) EscPosCommands.fullCut() else EscPosCommands.partialCut()
        writeSync(EscPosCommands.feedLines(4) + cutCmd)
        Log.d(TAG, "printBitmapAndCut: done (${if (fullCut) "full" else "partial"} cut)")
    }

    /**
     * Print bitmap without cut (for multi-page, cut after last page).
     */
    @Synchronized
    fun printBitmap(monoData: ByteArray, widthBytes: Int = PRINT_WIDTH_BYTES) {
        if (monoData.isEmpty() || widthBytes <= 0) {
            Log.w(TAG, "printBitmap: empty data or invalid widthBytes")
            return
        }
        val totalHeight = monoData.size / widthBytes
        Log.d(TAG, "printBitmap: ${widthBytes}x${totalHeight} = ${monoData.size} bytes")

        val header = EscPosCommands.rasterBitImageHeader(0, widthBytes, totalHeight)
        val payload = header + monoData
        val chunkSize = 65536
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            write(payload.copyOfRange(offset, end))
            offset = end
        }
        Log.d(TAG, "printBitmap: done")
    }

    /**
     * Feed paper and cut. Combined into single jyPrintString call.
     */
    @Synchronized
    fun feedAndCut(fullCut: Boolean = true) {
        val cutCmd = if (fullCut) EscPosCommands.fullCut() else EscPosCommands.partialCut()
        writeSync(EscPosCommands.feedLines(4) + cutCmd)
        Log.d(TAG, "feedAndCut: ${if (fullCut) "full" else "partial"} cut sent")
    }

    fun nativeFeed(fd: Int, direction: Int): Int {
        if (!opened) return -99
        return try {
            native_.jyPrinterFeed(fd, direction)
        } catch (e: Exception) {
            Log.e(TAG, "nativeFeed error", e)
            -98
        }
    }

    // ── Status checks ────────────────────────────────────────────────────

    @Synchronized fun checkPaper(): Int = if (opened) native_.jyPrinter_PaperCheck() else -99
    @Synchronized fun checkCover(): Int = if (opened) native_.jyPrinter_CoverCheck() else -99
    @Synchronized fun checkOverheat(): Int = if (opened) native_.jyPrinter_OverheatCheck() else -99
}
