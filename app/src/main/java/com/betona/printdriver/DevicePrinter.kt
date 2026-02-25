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

    fun initPrinter(brightness: Int = DEFAULT_BRIGHTNESS) {
        write(EscPosCommands.initialize())
        write(EscPosCommands.setBrightness(brightness))
    }

    fun printBitmap(monoData: ByteArray, widthBytes: Int = PRINT_WIDTH_BYTES) {
        val totalHeight = monoData.size / widthBytes
        Log.d(TAG, "printBitmap: ${widthBytes}x${totalHeight} = ${monoData.size} bytes")

        val header = EscPosCommands.rasterBitImageHeader(0, widthBytes, totalHeight)
        write(header)

        val chunkSize = 4096
        var offset = 0
        while (offset < monoData.size) {
            val end = minOf(offset + chunkSize, monoData.size)
            write(monoData.copyOfRange(offset, end))
            offset = end
        }
        Log.d(TAG, "printBitmap: done")
    }

    /**
     * Feed paper and cut using ESC/POS GS V 0 command.
     * No jyPrinterClose() needed - no crash.
     */
    fun feedAndCut() {
        write(EscPosCommands.feedLines(4))
        write(EscPosCommands.fullCut())
        Log.d(TAG, "feedAndCut: feed + GS V 0 sent")
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

    fun checkPaper(): Int = if (opened) native_.jyPrinter_PaperCheck() else -99
    fun checkCover(): Int = if (opened) native_.jyPrinter_CoverCheck() else -99
    fun checkOverheat(): Int = if (opened) native_.jyPrinter_OverheatCheck() else -99
}
