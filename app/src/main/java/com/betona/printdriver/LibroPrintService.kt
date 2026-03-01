package com.betona.printdriver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android PrintService for JY-P1000 built-in 3" thermal printer.
 *
 * PrintJob methods (start/complete/fail) MUST be called on main thread.
 * Actual printing runs on background thread.
 * Cut uses ESC/POS GS V 0 command via jyPrintString (no crash).
 */
class LibroPrintService : PrintService() {

    companion object {
        private const val TAG = "LibroPrintService"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activePrinter = DevicePrinter

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return LibroDiscoverySession(this)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "onRequestCancelPrintJob")
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val attrs = printJob.info.attributes
        val media = attrs?.mediaSize
        Log.i(TAG, "onPrintJobQueued: ${printJob.info.label}, paper=${media?.id} (${media?.widthMils}x${media?.heightMils} mils)")

        if (!printJob.start()) {
            Log.e(TAG, "printJob.start() failed")
            return
        }

        val fd = printJob.document.data
        if (fd == null) {
            Log.e(TAG, "Document data is null")
            printJob.fail("인쇄 데이터가 없습니다")
            return
        }

        Log.i(TAG, "Job started, launching print thread")

        Thread {
            try {
                doPrint(fd)

                val latch = CountDownLatch(1)
                mainHandler.post {
                    printJob.complete()
                    Log.i(TAG, "Print job COMPLETED")
                    latch.countDown()
                }
                latch.await(3, TimeUnit.SECONDS)

            } catch (e: Throwable) {
                Log.e(TAG, "Print FAILED", e)
                mainHandler.post {
                    try { printJob.fail("인쇄 오류: ${e.message}") } catch (_: Exception) {}
                }
            }
        }.start()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    // ── Actual printing (background thread) ──────────────────────────────

    private fun doPrint(fd: ParcelFileDescriptor) {
        if (!activePrinter.open()) {
            throw RuntimeException("프린터 장치를 열 수 없습니다")
        }
        activePrinter.initPrinter()

        val tempFile = File.createTempFile("print_", ".pdf", cacheDir)
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            fd.close()
        } catch (e: Exception) {
            tempFile.delete()
            throw RuntimeException("PDF 임시 파일 생성 실패: ${e.message}")
        }

        val seekableFd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(seekableFd)
        val pageCount = pdfRenderer.pageCount
        Log.i(TAG, "PDF pages: $pageCount, tempFile: ${tempFile.length()} bytes")

        for (i in 0 until pageCount) {
            val page = pdfRenderer.openPage(i)

            val scale = DevicePrinter.PRINT_WIDTH_PX.toFloat() / page.width
            val bitmapWidth = DevicePrinter.PRINT_WIDTH_PX
            val bitmapHeight = (page.height * scale).toInt()
            Log.d(TAG, "Page ${i+1}/$pageCount: ${page.width}x${page.height} → ${bitmapWidth}x${bitmapHeight}")

            var bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()

            val cropped = BitmapConverter.cropWhiteBorders(bitmap)
            Log.d(TAG, "Page ${i+1}: render=${bitmap.width}x${bitmap.height} crop=${cropped.width}x${cropped.height}")
            if (cropped !== bitmap) bitmap.recycle()
            val scaled = BitmapConverter.scaleToWidth(cropped, DevicePrinter.PRINT_WIDTH_PX)
            if (scaled !== cropped) cropped.recycle()

            val monoRaw = BitmapConverter.toMonochrome(scaled)
            scaled.recycle()

            val monoData = BitmapConverter.trimTrailingWhiteRows(monoRaw)
            Log.d(TAG, "Page ${i+1}: mono=${monoRaw.size} trimmed=${monoData.size} bytes")

            val isLastPage = (i == pageCount - 1)
            if (isLastPage) {
                activePrinter.printBitmapAndCut(monoData, fullCut = AppPrefs.isFullCut(this))
            } else {
                activePrinter.printBitmap(monoData)
            }
            Log.d(TAG, "Page ${i+1} printed${if (isLastPage) " + cut" else ""}")
        }
        pdfRenderer.close()
        tempFile.delete()
        Log.d(TAG, "doPrint complete")
    }
}
