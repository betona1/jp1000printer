package com.betona.printdriver.web

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.betona.printdriver.AppPrefs
import com.betona.printdriver.DevicePrinter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridges main process and the isolated `:renderer` process.
 *
 * For each print, binds the [PdfRenderService], sends a render request,
 * waits with a hard timeout, and forwards the rendered pages to [DevicePrinter].
 * If rendering hangs (PdfRenderer leak on Android 7), the renderer process is
 * killed; the next request rebinds and gets a fresh process with clean native
 * memory.
 */
object PdfRenderClient {

    private const val TAG = "PdfRenderClient"
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val RENDER_TIMEOUT_MS = 30_000L

    /**
     * Render the given PDF file in the isolated renderer process and print
     * each page to the thermal printer. Last page triggers a paper cut.
     *
     * @return true on success, false on bind failure / render failure / timeout.
     */
    @Synchronized
    fun renderAndPrint(context: Context, pdfFile: File, fullCut: Boolean): Boolean {
        // Phone→kiosk IPP prints have their own setting (default 80%) so the
        // kiosk's "크게(100%)" UI option doesn't surprise phone users.
        val zoomSetting = AppPrefs.getRenderQualityIpp(context)
        val zoomFactor = when (zoomSetting) { 1 -> 0.65f; 2 -> 0.8f; else -> 1.0f }
        val printWidthPx = DevicePrinter.PRINT_WIDTH_PX
        val widthBytes = DevicePrinter.PRINT_WIDTH_BYTES
        val outputDir = context.cacheDir.absolutePath

        val resultLatch = CountDownLatch(1)
        val resultBundle = arrayOfNulls<Bundle>(1)

        val replyHandler = Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == PdfRenderService.MSG_RENDER_COMPLETE) {
                resultBundle[0] = Bundle(msg.data) // copy before recycle
                resultLatch.countDown()
            }
            true
        }
        val replyMessenger = Messenger(replyHandler)

        val connectionLatch = CountDownLatch(1)
        val serviceMessengerHolder = arrayOfNulls<Messenger>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                serviceMessengerHolder[0] = Messenger(service)
                connectionLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                serviceMessengerHolder[0] = null
            }
        }

        val intent = Intent(context, PdfRenderService::class.java)
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService threw", e)
            false
        }
        if (!bound) {
            Log.e(TAG, "Failed to bind PdfRenderService")
            try { context.unbindService(connection) } catch (_: Exception) {}
            return false
        }

        try {
            if (!connectionLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Bind timeout — killing :renderer just in case")
                killRendererProcess(context)
                return false
            }

            val serviceMessenger = serviceMessengerHolder[0] ?: run {
                Log.e(TAG, "Service messenger null after connect")
                return false
            }

            val msg = Message.obtain().apply {
                what = PdfRenderService.MSG_RENDER_PDF
                replyTo = replyMessenger
                data = Bundle().apply {
                    putString(PdfRenderService.KEY_PDF_PATH, pdfFile.absolutePath)
                    putInt(PdfRenderService.KEY_PRINT_WIDTH_PX, printWidthPx)
                    putInt(PdfRenderService.KEY_WIDTH_BYTES, widthBytes)
                    putFloat(PdfRenderService.KEY_ZOOM, zoomFactor)
                    putString(PdfRenderService.KEY_OUTPUT_DIR, outputDir)
                }
            }

            try {
                serviceMessenger.send(msg)
            } catch (e: RemoteException) {
                Log.e(TAG, "Send failed", e)
                killRendererProcess(context)
                return false
            }

            val gotReply = resultLatch.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!gotReply) {
                Log.e(TAG, "Render timeout (${RENDER_TIMEOUT_MS}ms) — killing :renderer process")
                killRendererProcess(context)
                return false
            }

            val bundle = resultBundle[0] ?: return false
            val success = bundle.getBoolean(PdfRenderService.KEY_SUCCESS, false)
            if (!success) {
                val error = bundle.getString(PdfRenderService.KEY_ERROR) ?: "(unknown)"
                Log.e(TAG, "Render failed: $error")
                return false
            }

            val pageFiles: ArrayList<String> =
                bundle.getStringArrayList(PdfRenderService.KEY_PAGE_FILES) ?: arrayListOf()
            if (pageFiles.isEmpty()) {
                Log.w(TAG, "No pages rendered")
                return false
            }

            // Print pages in main process — DevicePrinter (jyndklib) must stay here.
            if (!DevicePrinter.isOpen) {
                DevicePrinter.open()
                DevicePrinter.initPrinter()
            }

            for ((index, pagePath) in pageFiles.withIndex()) {
                val pageFile = File(pagePath)
                try {
                    val monoData = pageFile.readBytes()
                    val isLast = index == pageFiles.size - 1
                    if (isLast) {
                        DevicePrinter.printBitmapAndCut(monoData, widthBytes, fullCut)
                    } else {
                        DevicePrinter.printBitmap(monoData, widthBytes)
                    }
                    Log.i(TAG, "Printed page ${index + 1}/${pageFiles.size}: ${monoData.size} bytes")
                } finally {
                    try { pageFile.delete() } catch (_: Exception) {}
                }
            }

            return true
        } finally {
            try { context.unbindService(connection) } catch (_: Exception) {}
        }
    }

    /**
     * Force-kill the `:renderer` process. Same UID as caller, so allowed without
     * special permissions. The next bindService will spawn a fresh process.
     */
    private fun killRendererProcess(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val target = am.runningAppProcesses?.firstOrNull { it.processName.endsWith(":renderer") }
            if (target != null) {
                Log.w(TAG, "Killing :renderer pid=${target.pid}")
                Process.killProcess(target.pid)
            } else {
                Log.w(TAG, ":renderer process not found in running list")
            }
        } catch (e: Exception) {
            Log.e(TAG, "killRendererProcess failed", e)
        }
    }
}
