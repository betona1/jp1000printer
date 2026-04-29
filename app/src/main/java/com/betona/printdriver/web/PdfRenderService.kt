package com.betona.printdriver.web

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import com.betona.printdriver.BitmapConverter
import java.io.File
import java.io.FileOutputStream

/**
 * Renders PDFs to monochrome byte arrays in an isolated process (`:renderer`).
 *
 * Why isolated: on Android 7 (A40i), libpdfium has bugs where `PdfRenderer.close()`
 * crashes with SIGABRT. Skipping close() leaks native memory; after ~5 prints, the
 * leak causes `PdfRenderer.openPage()` or `Page.render()` to hang forever. By
 * running rendering in a separate process, the main app can kill `:renderer` on
 * hang and rebind to a fresh process — leaks are contained and don't accumulate.
 *
 * Communicates via Messenger (one-shot per request). Rendered pages are written
 * to files in the shared cache dir; the main process reads them and feeds the
 * thermal printer.
 */
class PdfRenderService : Service() {

    companion object {
        private const val TAG = "PdfRenderSvc"

        // Message identifiers
        const val MSG_RENDER_PDF = 1
        const val MSG_RENDER_COMPLETE = 2

        // Request bundle keys
        const val KEY_PDF_PATH = "pdf_path"
        const val KEY_PRINT_WIDTH_PX = "print_width_px"
        const val KEY_WIDTH_BYTES = "width_bytes"
        const val KEY_ZOOM = "zoom"
        const val KEY_OUTPUT_DIR = "output_dir"

        // Response bundle keys
        const val KEY_SUCCESS = "success"
        const val KEY_PAGE_FILES = "page_files"
        const val KEY_PAGE_HEIGHTS = "page_heights"
        const val KEY_ERROR = "error"
    }

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_RENDER_PDF -> {
                val replyTo = msg.replyTo
                val data = msg.data
                Thread({
                    val result = renderPdf(
                        data.getString(KEY_PDF_PATH).orEmpty(),
                        data.getInt(KEY_PRINT_WIDTH_PX),
                        data.getInt(KEY_WIDTH_BYTES),
                        data.getFloat(KEY_ZOOM, 1.0f),
                        data.getString(KEY_OUTPUT_DIR).orEmpty()
                    )
                    val reply = Message.obtain().apply {
                        what = MSG_RENDER_COMPLETE
                        this.data = Bundle().apply {
                            putBoolean(KEY_SUCCESS, result.success)
                            putStringArrayList(KEY_PAGE_FILES, ArrayList(result.pageFiles))
                            putIntArray(KEY_PAGE_HEIGHTS, result.pageHeights)
                            result.error?.let { putString(KEY_ERROR, it) }
                        }
                    }
                    try {
                        replyTo?.send(reply)
                    } catch (e: Exception) {
                        Log.e(TAG, "Reply send failed", e)
                    }
                }, "pdf-render-worker").start()
                true
            }
            else -> false
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PdfRenderService created (pid=${android.os.Process.myPid()})")
    }

    override fun onDestroy() {
        Log.i(TAG, "PdfRenderService destroyed")
        super.onDestroy()
    }

    private data class RenderResult(
        val success: Boolean,
        val pageFiles: List<String> = emptyList(),
        val pageHeights: IntArray = IntArray(0),
        val error: String? = null
    )

    private fun renderPdf(
        pdfPath: String,
        printWidthPx: Int,
        widthBytes: Int,
        zoomFactor: Float,
        outputDir: String
    ): RenderResult {
        val pdfFile = File(pdfPath)
        if (!pdfFile.exists()) return RenderResult(false, error = "PDF file not found: $pdfPath")

        val outDir = File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            val renderer = PdfRenderer(fd)
            try {
                val pageCount = renderer.pageCount
                val pageFiles = mutableListOf<String>()
                val pageHeights = mutableListOf<Int>()
                Log.i(TAG, "Rendering $pageCount page(s) at ${printWidthPx}px zoom=$zoomFactor")

                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        val renderWidth = printWidthPx * 3
                        val renderHeight = maxOf(
                            1,
                            (renderWidth.toDouble() * page.height / page.width).toInt()
                        )

                        var bitmap: Bitmap? = null
                        var cropped: Bitmap? = null
                        var scaled: Bitmap? = null
                        try {
                            bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            cropped = BitmapConverter.cropWhiteBorders(bitmap)
                            if (cropped !== bitmap) { bitmap.recycle(); bitmap = null }

                            scaled = BitmapConverter.scaleToWidth(cropped, printWidthPx)
                            if (scaled !== cropped) { cropped.recycle(); cropped = null }

                            val shrunkH = maxOf(1, (scaled!!.height * zoomFactor).toInt())
                            val shrunk = Bitmap.createScaledBitmap(scaled, printWidthPx, shrunkH, true)
                            if (shrunk !== scaled) { scaled.recycle() }
                            scaled = shrunk

                            val mono = BitmapConverter.toMonochrome(scaled!!)
                            val trimmed = BitmapConverter.trimTrailingWhiteRows(mono)
                            scaled.recycle(); scaled = null

                            val pageFile = File(outDir, "render_${System.nanoTime()}_p$i.bin")
                            FileOutputStream(pageFile).use { it.write(trimmed) }
                            pageFiles.add(pageFile.absolutePath)
                            pageHeights.add(trimmed.size / widthBytes)

                            Log.i(TAG, "Page ${i + 1}/$pageCount: ${trimmed.size} bytes -> ${pageFile.name}")
                        } finally {
                            bitmap?.recycle()
                            cropped?.recycle()
                            scaled?.recycle()
                        }
                    } finally {
                        page.close()
                    }
                }

                RenderResult(true, pageFiles, pageHeights.toIntArray())
            } finally {
                // Same Android 7 libpdfium bug as before — skip close() on API < 26
                // to avoid SIGABRT. Native memory does leak, but the whole `:renderer`
                // process gets killed on hang or after a session of prints, so the leak
                // is contained.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    renderer.close()
                } else {
                    Log.w(TAG, "Skipping PdfRenderer.close() on API ${Build.VERSION.SDK_INT}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Render failed", e)
            RenderResult(false, error = e.message ?: e.javaClass.simpleName)
        } finally {
            try { fd.close() } catch (_: Exception) {}
        }
    }
}
