package com.betona.libroprintplugin

import android.os.Handler
import android.os.Looper
import android.print.PrinterId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Network PrintService plugin for LibroPrinter kiosks.
 * Discovers printers via mDNS and sends PDF print jobs over IPP.
 */
class LibroNetPrintService : PrintService() {

    companion object {
        private const val TAG = "LibroNetPrintSvc"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoverySession: LibroNetDiscoverySession? = null

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return LibroNetDiscoverySession(this).also { discoverySession = it }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "onRequestCancelPrintJob")
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val info = printJob.info
        val media = info.attributes.mediaSize
        Log.i(TAG, "onPrintJobQueued: ${info.label}, paper=${media?.id}")

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

        // Find the target printer endpoint
        val printerId = info.printerId
        if (printerId == null) {
            Log.e(TAG, "Printer ID is null")
            printJob.fail("프린터 ID가 없습니다")
            fd.close()
            return
        }
        val endpoint = discoverySession?.getEndpoint(printerId)
        if (endpoint == null) {
            Log.e(TAG, "Printer endpoint not found for ${printerId.localId}")
            printJob.fail("프린터를 찾을 수 없습니다")
            fd.close()
            return
        }

        Log.i(TAG, "Sending to ${endpoint.host}:${endpoint.port}")

        Thread {
            try {
                // Read PDF data from the file descriptor
                val pdfBytes = ByteArrayOutputStream().use { baos ->
                    fd.fileDescriptor.let { fdesc ->
                        java.io.FileInputStream(fdesc).use { input ->
                            input.copyTo(baos)
                        }
                    }
                    baos.toByteArray()
                }
                fd.close()

                Log.i(TAG, "PDF data: ${pdfBytes.size} bytes")

                val success = IppClient.sendPrintJob(endpoint.host, endpoint.port, pdfBytes)

                val latch = CountDownLatch(1)
                mainHandler.post {
                    if (success) {
                        printJob.complete()
                        Log.i(TAG, "Print job COMPLETED")
                    } else {
                        printJob.fail("IPP 인쇄 요청 실패")
                        Log.e(TAG, "Print job FAILED")
                    }
                    latch.countDown()
                }
                latch.await(5, TimeUnit.SECONDS)

            } catch (e: Throwable) {
                Log.e(TAG, "Print FAILED", e)
                mainHandler.post {
                    try {
                        printJob.fail("인쇄 오류: ${e.message}")
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        discoverySession = null
        super.onDestroy()
    }
}
