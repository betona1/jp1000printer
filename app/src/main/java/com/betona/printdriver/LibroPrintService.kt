package com.betona.printdriver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.print.PrintJobId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android PrintService for JY-P1000 built-in 3" thermal printer.
 *
 * Registered in Settings → Print → LibroPrintDriver.
 * Receives PDF print jobs from any app, renders pages to bitmap,
 * converts to ESC/POS raster format, and sends to /dev/printer.
 */
class LibroPrintService : PrintService() {

    companion object {
        private const val TAG = "LibroPrintService"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val activePrinter = DevicePrinter()

    // ── PrintService callbacks ───────────────────────────────────────────

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return LibroDiscoverySession(this)
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "onRequestCancelPrintJob: ${printJob.id}")
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        Log.i(TAG, "onPrintJobQueued: ${printJob.id}, doc=${printJob.info.label}")
        executor.submit { handlePrintJob(printJob) }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        executor.shutdown()
        activePrinter.close()
        super.onDestroy()
    }

    // ── Print job handling ───────────────────────────────────────────────

    private fun handlePrintJob(printJob: PrintJob) {
        printJob.start()
        Log.i(TAG, "Started print job: ${printJob.id}")

        try {
            // Open printer
            if (!activePrinter.open()) {
                failJob(printJob, "프린터 장치를 열 수 없습니다 (${DevicePrinter.DEVICE_PATH})")
                return
            }

            // Initialize
            activePrinter.initPrinter()

            // Get document data (PDF)
            val fd = printJob.document.data
            if (fd == null) {
                failJob(printJob, "인쇄 데이터가 없습니다")
                return
            }

            // Render and print each PDF page
            val pdfRenderer = PdfRenderer(fd)
            val pageCount = pdfRenderer.pageCount
            Log.i(TAG, "PDF pages: $pageCount")

            for (i in 0 until pageCount) {
                Log.d(TAG, "Rendering page ${i + 1}/$pageCount")
                val page = pdfRenderer.openPage(i)

                // Scale PDF page to fit printer width (576px)
                val scale = DevicePrinter.PRINT_WIDTH_PX.toFloat() / page.width
                val bitmapWidth = DevicePrinter.PRINT_WIDTH_PX
                val bitmapHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                // Convert to monochrome and send to printer
                val monoData = BitmapConverter.toMonochrome(bitmap)
                bitmap.recycle()
                activePrinter.printBitmap(monoData)

                Log.d(TAG, "Page ${i + 1} sent: ${monoData.size} bytes")
            }
            pdfRenderer.close()

            // Feed and auto-cut
            activePrinter.feedAndCut()
            activePrinter.close()

            printJob.complete()
            Log.i(TAG, "Print job completed: ${printJob.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Print job failed: ${printJob.id}", e)
            failJob(printJob, "인쇄 오류: ${e.message}")
            activePrinter.close()
        }
    }

    private fun failJob(printJob: PrintJob, reason: String) {
        Log.e(TAG, "Job ${printJob.id} failed: $reason")
        printJob.fail(reason)
        activePrinter.close()
    }
}
