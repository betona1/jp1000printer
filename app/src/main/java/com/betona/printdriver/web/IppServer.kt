package com.betona.printdriver.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.betona.printdriver.AppPrefs
import com.betona.printdriver.BitmapConverter
import com.betona.printdriver.DevicePrinter
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * IPP (Internet Printing Protocol) server on port 631.
 * Allows other Android devices on the same WiFi to discover and print
 * to the kiosk thermal printer via the standard Android print framework.
 *
 * Flow: Android device -> mDNS discovery -> Get-Printer-Attributes ->
 *       Print-Job (PDF) -> PdfRenderer -> thermal print
 */
class IppServer(private val port: Int = 6631) {

    private var serverSocket: ServerSocket? = null
    private var listenThread: Thread? = null
    @Volatile private var running = false

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var jobIdCounter = 1000

    // Per-device UUID and name (set in start() from device serial/ID)
    private lateinit var printerUuid: String
    private lateinit var printerName: String

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION", "HardwareIds")
    fun start(context: Context) {
        // Generate per-device UUID from Android ID (unique per device)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        printerUuid = UUID.nameUUIDFromBytes("LibroPrinter-$androidId".toByteArray()).toString()

        // Device-specific name using model suffix (e.g. "LibroPrinter-P1000", "LibroPrinter-A40i")
        val model = Build.MODEL?.replace(" ", "") ?: "Unknown"
        val shortModel = if (model.length > 10) model.takeLast(10) else model
        printerName = "LibroPrinter-$shortModel"
        Log.i(TAG, "Printer name=$printerName, uuid=$printerUuid, androidId=$androidId")

        if (running) return
        running = true
        listenThread = Thread({
            try {
                serverSocket = ServerSocket(port).also { ss ->
                    ss.reuseAddress = true
                    Log.i(TAG, "IPP server listening on port $port")
                    while (running) {
                        try {
                            val client = ss.accept()
                            Log.i(TAG, "IPP client: ${client.remoteSocketAddress}")
                            Thread({ handleClient(client, context) }, "ipp-client").start()
                        } catch (e: Exception) {
                            if (running) Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start error", e)
            }
        }, "ipp-server")
        listenThread!!.start()
        registerMdns(context)
    }

    fun stop() {
        running = false
        unregisterMdns()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        listenThread = null
        Log.i(TAG, "Stopped")
    }

    // ── HTTP Layer ───────────────────────────────────────────────────────

    private fun handleClient(socket: Socket, context: Context) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // Read HTTP request line
            val requestLine = readLine(input)
            if (requestLine.isEmpty()) return
            Log.d(TAG, "HTTP: $requestLine")

            // Read headers
            var contentLength = -1
            var chunked = false
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val lower = line.lowercase()
                when {
                    lower.startsWith("content-length:") ->
                        contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
                    lower.startsWith("transfer-encoding:") && "chunked" in lower ->
                        chunked = true
                }
            }

            // Read body
            val body: ByteArray = when {
                contentLength > 0 -> ByteArray(contentLength).also { readFully(input, it) }
                chunked -> readChunked(input)
                else -> input.readBytes()
            }

            // Process IPP and send HTTP response
            val ippResponse = processIpp(body, context)
            val httpHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/ipp\r\n" +
                    "Content-Length: ${ippResponse.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            output.write(httpHeader.toByteArray(Charsets.ISO_8859_1))
            output.write(ippResponse)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── IPP Routing ──────────────────────────────────────────────────────

    private fun processIpp(body: ByteArray, context: Context): ByteArray {
        if (body.size < 8) return buildErrorResponse(0, 0x0400)

        val verMajor = body[0].toInt() and 0xFF
        val verMinor = body[1].toInt() and 0xFF
        val opId = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        val reqId = ((body[4].toInt() and 0xFF) shl 24) or
                ((body[5].toInt() and 0xFF) shl 16) or
                ((body[6].toInt() and 0xFF) shl 8) or
                (body[7].toInt() and 0xFF)

        Log.i(TAG, "IPP $verMajor.$verMinor op=0x${opId.toString(16)} reqId=$reqId (${body.size} bytes)")

        return when (opId) {
            OP_GET_PRINTER_ATTRIBUTES -> handleGetPrinterAttributes(verMajor, verMinor, reqId)
            OP_PRINT_JOB -> handlePrintJob(verMajor, verMinor, reqId, body, context)
            OP_VALIDATE_JOB -> handleValidateJob(verMajor, verMinor, reqId)
            OP_GET_JOB_ATTRIBUTES -> handleGetJobAttributes(verMajor, verMinor, reqId, body)
            else -> {
                Log.w(TAG, "Unsupported IPP operation: 0x${opId.toString(16)}")
                buildErrorResponse(reqId, 0x0501, verMajor, verMinor)
            }
        }
    }

    // ── Get-Printer-Attributes ───────────────────────────────────────────

    private fun handleGetPrinterAttributes(verMajor: Int, verMinor: Int, reqId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeIppHeader(verMajor, verMinor, STATUS_OK, reqId)
        out.writeOperationAttributes()

        // Printer attributes group
        out.write(TAG_PRINTER_ATTRIBUTES)

        val ip = getLocalIpAddress()
        val printerUri = "ipp://$ip:$port/ipp/print"

        out.writeUri("printer-uri-supported", printerUri)
        out.writeKeyword("uri-security-supported", "none")
        out.writeKeyword("uri-authentication-supported", "none")
        out.writeName("printer-name", printerName)
        out.writeText("printer-info", "$printerName Thermal Receipt Printer")
        out.writeText("printer-make-and-model", "LibroPrinter Thermal")
        out.writeUri("printer-more-info", "http://$ip:8080")
        out.writeUri("printer-uuid", "urn:uuid:$printerUuid")
        out.writeEnum("printer-state", 3) // idle
        out.writeKeyword("printer-state-reasons", "none")

        // IPP versions
        out.writeKeyword("ipp-versions-supported", "1.1")
        out.writeKeywordValue("2.0")

        // Supported operations
        out.writeEnum("operations-supported", OP_PRINT_JOB)
        out.writeEnumValue(OP_VALIDATE_JOB)
        out.writeEnumValue(OP_GET_JOB_ATTRIBUTES)
        out.writeEnumValue(OP_GET_PRINTER_ATTRIBUTES)

        out.writeBool("printer-is-accepting-jobs", true)
        out.writeInteger("queued-job-count", 0)

        // Document formats
        out.writeMimeType("document-format-supported", "application/pdf")
        out.writeMimeTypeValue("application/octet-stream")
        out.writeMimeType("document-format-default", "application/pdf")

        // Charset & language
        out.writeCharset("charset-configured", "utf-8")
        out.writeCharset("charset-supported", "utf-8")
        out.writeNaturalLanguage("natural-language-configured", "en")
        out.writeNaturalLanguage("generated-natural-language-supported", "en")

        // Media — 4x6" as default (closest standard size to 72mm thermal).
        // 4" = 101.6mm → scaled to 72mm ≈ 71%, much better than A4's 34%.
        out.writeKeyword("media-supported", "na_index-4x6_4x6in")
        out.writeKeywordValue("iso_a5_148x210mm")
        out.writeKeywordValue("iso_a4_210x297mm")
        out.writeKeywordValue("na_letter_8.5x11in")
        out.writeKeyword("media-default", "na_index-4x6_4x6in")
        out.writeKeyword("media-ready", "na_index-4x6_4x6in")

        // media-size-supported — dimensions in hundredths of mm
        out.writeMediaSize("media-size-supported", 10160, 15240)  // 4x6"
        out.writeMediaSizeValue(14800, 21000)  // A5
        out.writeMediaSizeValue(21000, 29700)  // A4
        out.writeMediaSizeValue(21590, 27940)  // Letter

        // Color
        out.writeKeyword("print-color-mode-supported", "monochrome")
        out.writeKeyword("print-color-mode-default", "monochrome")
        out.writeBool("color-supported", false)

        // Sides
        out.writeKeyword("sides-supported", "one-sided")
        out.writeKeyword("sides-default", "one-sided")

        out.writeKeyword("pdl-override-supported", "attempted")

        out.write(TAG_END_OF_ATTRIBUTES)
        return out.toByteArray()
    }

    // ── Print-Job ────────────────────────────────────────────────────────

    private fun handlePrintJob(
        verMajor: Int, verMinor: Int, reqId: Int, body: ByteArray, context: Context
    ): ByteArray {
        val docOffset = findDocumentDataOffset(body)
        if (docOffset < 0 || docOffset >= body.size) {
            Log.e(TAG, "Print-Job: no document data")
            return buildErrorResponse(reqId, 0x0400, verMajor, verMinor)
        }

        val docData = body.copyOfRange(docOffset, body.size)
        Log.i(TAG, "Print-Job: ${docData.size} bytes document data")

        val currentJobId = synchronized(this) { jobIdCounter++ }

        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("ipp_job_", ".pdf", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(docData) }
            renderAndPrint(tempFile, context)
            Log.i(TAG, "Print-Job #$currentJobId completed")
        } catch (e: Exception) {
            Log.e(TAG, "Print-Job #$currentJobId failed", e)
            return buildErrorResponse(reqId, 0x0500, verMajor, verMinor)
        } finally {
            tempFile?.delete()
        }

        return buildJobResponse(verMajor, verMinor, reqId, currentJobId, JOB_STATE_COMPLETED)
    }

    // ── Validate-Job ─────────────────────────────────────────────────────

    private fun handleValidateJob(verMajor: Int, verMinor: Int, reqId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeIppHeader(verMajor, verMinor, STATUS_OK, reqId)
        out.writeOperationAttributes()
        out.write(TAG_END_OF_ATTRIBUTES)
        return out.toByteArray()
    }

    // ── Get-Job-Attributes ───────────────────────────────────────────────

    private fun handleGetJobAttributes(
        verMajor: Int, verMinor: Int, reqId: Int, body: ByteArray
    ): ByteArray {
        val jobId = parseRequestedJobId(body) ?: (jobIdCounter - 1)
        return buildJobResponse(verMajor, verMinor, reqId, jobId, JOB_STATE_COMPLETED)
    }

    // ── PDF Rendering & Printing ─────────────────────────────────────────

    private fun renderAndPrint(pdfFile: File, context: Context) {
        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        try {
            if (!DevicePrinter.isOpen) {
                DevicePrinter.open()
                DevicePrinter.initPrinter()
            }

            val pw = DevicePrinter.PRINT_WIDTH_PX // 576
            val pageCount = renderer.pageCount
            Log.i(TAG, "Rendering $pageCount page(s)")

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)

                // Render at 3x printer width for quality, then crop+scale
                val renderWidth = pw * 3 // 1728px
                val renderHeight = maxOf(1, (renderWidth.toDouble() * page.height / page.width).toInt())

                var bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                // Crop white margins → scale to printer width
                val cropped = BitmapConverter.cropWhiteBorders(bitmap)
                if (cropped !== bitmap) bitmap.recycle()
                val scaled = BitmapConverter.scaleToWidth(cropped, pw)
                if (scaled !== cropped) cropped.recycle()

                val mono = BitmapConverter.toMonochrome(scaled)
                val trimmed = BitmapConverter.trimTrailingWhiteRows(mono)
                scaled.recycle()

                if (i == pageCount - 1) {
                    DevicePrinter.printBitmapAndCut(trimmed, fullCut = AppPrefs.isFullCut(context))
                } else {
                    DevicePrinter.printBitmap(trimmed)
                }
                Log.i(TAG, "Page ${i + 1}/$pageCount: ${trimmed.size} bytes")
            }
        } finally {
            renderer.close()
        }
    }

    // ── IPP Binary Parsing ───────────────────────────────────────────────

    /** Find offset of document data (after end-of-attributes 0x03 tag). */
    private fun findDocumentDataOffset(body: ByteArray): Int {
        var i = 8 // skip version(2) + operation(2) + requestId(4)
        while (i < body.size) {
            val tag = body[i].toInt() and 0xFF
            if (tag == TAG_END_OF_ATTRIBUTES) return i + 1
            if (tag in 0x00..0x0F) { i++; continue } // group tag
            i++ // value tag
            if (i + 2 > body.size) break
            val nameLen = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            i += 2 + nameLen
            if (i + 2 > body.size) break
            val valueLen = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            i += 2 + valueLen
        }
        return -1
    }

    /** Parse job-id integer attribute from IPP request body. */
    private fun parseRequestedJobId(body: ByteArray): Int? {
        var i = 8
        while (i < body.size) {
            val tag = body[i].toInt() and 0xFF
            if (tag == TAG_END_OF_ATTRIBUTES) break
            if (tag in 0x00..0x0F) { i++; continue }
            val valueTag = tag
            i++
            if (i + 2 > body.size) break
            val nameLen = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            i += 2
            val name = if (nameLen > 0 && i + nameLen <= body.size) {
                String(body, i, nameLen, Charsets.UTF_8)
            } else ""
            i += nameLen
            if (i + 2 > body.size) break
            val valueLen = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            i += 2
            if (valueTag == 0x21 && name == "job-id" && valueLen == 4 && i + 4 <= body.size) {
                return ((body[i].toInt() and 0xFF) shl 24) or
                        ((body[i + 1].toInt() and 0xFF) shl 16) or
                        ((body[i + 2].toInt() and 0xFF) shl 8) or
                        (body[i + 3].toInt() and 0xFF)
            }
            i += valueLen
        }
        return null
    }

    // ── IPP Response Builders ────────────────────────────────────────────

    private fun buildJobResponse(
        verMajor: Int, verMinor: Int, reqId: Int, jobId: Int, jobState: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeIppHeader(verMajor, verMinor, STATUS_OK, reqId)
        out.writeOperationAttributes()
        out.write(TAG_JOB_ATTRIBUTES)
        out.writeInteger("job-id", jobId)
        val ip = getLocalIpAddress()
        out.writeUri("job-uri", "ipp://$ip:$port/ipp/print/jobs/$jobId")
        out.writeEnum("job-state", jobState)
        out.writeKeyword("job-state-reasons",
            if (jobState == JOB_STATE_COMPLETED) "job-completed-successfully" else "none")
        out.write(TAG_END_OF_ATTRIBUTES)
        return out.toByteArray()
    }

    private fun buildErrorResponse(
        reqId: Int, status: Int, verMajor: Int = 1, verMinor: Int = 1
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeIppHeader(verMajor, verMinor, status, reqId)
        out.writeOperationAttributes()
        out.write(TAG_END_OF_ATTRIBUTES)
        return out.toByteArray()
    }

    // ── ByteArrayOutputStream IPP Extensions ─────────────────────────────

    private fun ByteArrayOutputStream.writeIppHeader(
        verMajor: Int, verMinor: Int, status: Int, reqId: Int
    ) {
        write(verMajor)
        write(verMinor)
        writeShortBE(status)
        writeIntBE(reqId)
    }

    private fun ByteArrayOutputStream.writeOperationAttributes() {
        write(TAG_OPERATION_ATTRIBUTES)
        writeCharset("attributes-charset", "utf-8")
        writeNaturalLanguage("attributes-natural-language", "en")
    }

    private fun ByteArrayOutputStream.writeShortBE(v: Int) {
        write((v shr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntBE(v: Int) {
        write((v shr 24) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeAttr(tag: Int, name: String, value: ByteArray) {
        write(tag)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        writeShortBE(nameBytes.size)
        write(nameBytes)
        writeShortBE(value.size)
        write(value)
    }

    private fun ByteArrayOutputStream.writeAttrValue(tag: Int, value: ByteArray) {
        write(tag)
        writeShortBE(0) // additional value (no name)
        writeShortBE(value.size)
        write(value)
    }

    private fun ByteArrayOutputStream.writeStringAttr(tag: Int, name: String, value: String) =
        writeAttr(tag, name, value.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.writeStringValue(tag: Int, value: String) =
        writeAttrValue(tag, value.toByteArray(Charsets.UTF_8))

    // Typed attribute writers
    private fun ByteArrayOutputStream.writeCharset(n: String, v: String) = writeStringAttr(0x47, n, v)
    private fun ByteArrayOutputStream.writeNaturalLanguage(n: String, v: String) = writeStringAttr(0x48, n, v)
    private fun ByteArrayOutputStream.writeKeyword(n: String, v: String) = writeStringAttr(0x44, n, v)
    private fun ByteArrayOutputStream.writeKeywordValue(v: String) = writeStringValue(0x44, v)
    private fun ByteArrayOutputStream.writeUri(n: String, v: String) = writeStringAttr(0x45, n, v)
    private fun ByteArrayOutputStream.writeName(n: String, v: String) = writeStringAttr(0x42, n, v)
    private fun ByteArrayOutputStream.writeText(n: String, v: String) = writeStringAttr(0x41, n, v)
    private fun ByteArrayOutputStream.writeMimeType(n: String, v: String) = writeStringAttr(0x49, n, v)
    private fun ByteArrayOutputStream.writeMimeTypeValue(v: String) = writeStringValue(0x49, v)

    private fun ByteArrayOutputStream.writeInteger(name: String, value: Int) {
        val buf = ByteArray(4)
        buf[0] = ((value shr 24) and 0xFF).toByte()
        buf[1] = ((value shr 16) and 0xFF).toByte()
        buf[2] = ((value shr 8) and 0xFF).toByte()
        buf[3] = (value and 0xFF).toByte()
        writeAttr(0x21, name, buf)
    }

    private fun ByteArrayOutputStream.writeEnum(name: String, value: Int) {
        val buf = ByteArray(4)
        buf[0] = ((value shr 24) and 0xFF).toByte()
        buf[1] = ((value shr 16) and 0xFF).toByte()
        buf[2] = ((value shr 8) and 0xFF).toByte()
        buf[3] = (value and 0xFF).toByte()
        writeAttr(0x23, name, buf)
    }

    private fun ByteArrayOutputStream.writeEnumValue(value: Int) {
        val buf = ByteArray(4)
        buf[0] = ((value shr 24) and 0xFF).toByte()
        buf[1] = ((value shr 16) and 0xFF).toByte()
        buf[2] = ((value shr 8) and 0xFF).toByte()
        buf[3] = (value and 0xFF).toByte()
        writeAttrValue(0x23, buf)
    }

    private fun ByteArrayOutputStream.writeBool(name: String, value: Boolean) =
        writeAttr(0x22, name, byteArrayOf(if (value) 1 else 0))

    // ── IPP Collection Helpers (for media-size-supported etc.) ───────────

    private fun ByteArrayOutputStream.writeBeginCollection(name: String) {
        write(0x34) // begCollection
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        writeShortBE(nameBytes.size)
        write(nameBytes)
        writeShortBE(0) // value-length always 0
    }

    private fun ByteArrayOutputStream.writeBeginCollectionValue() {
        write(0x34) // begCollection (additional value)
        writeShortBE(0) // name-length = 0
        writeShortBE(0)
    }

    private fun ByteArrayOutputStream.writeMemberName(memberName: String) {
        write(0x4A) // memberAttrName
        writeShortBE(0)
        val nameBytes = memberName.toByteArray(Charsets.UTF_8)
        writeShortBE(nameBytes.size)
        write(nameBytes)
    }

    private fun ByteArrayOutputStream.writeMemberInteger(value: Int) {
        write(0x21) // integer
        writeShortBE(0)
        writeShortBE(4)
        writeIntBE(value)
    }

    private fun ByteArrayOutputStream.writeEndCollection() {
        write(0x37) // endCollection
        writeShortBE(0)
        writeShortBE(0)
    }

    /** Write a media-size collection: { x-dimension, y-dimension } in hundredths of mm */
    private fun ByteArrayOutputStream.writeMediaSize(name: String, widthHmm: Int, heightHmm: Int) {
        writeBeginCollection(name)
        writeMemberName("x-dimension")
        writeMemberInteger(widthHmm)
        writeMemberName("y-dimension")
        writeMemberInteger(heightHmm)
        writeEndCollection()
    }

    /** Additional media-size value (same attribute) */
    private fun ByteArrayOutputStream.writeMediaSizeValue(widthHmm: Int, heightHmm: Int) {
        writeBeginCollectionValue()
        writeMemberName("x-dimension")
        writeMemberInteger(widthHmm)
        writeMemberName("y-dimension")
        writeMemberInteger(heightHmm)
        writeEndCollection()
    }

    /** Write a media-col entry: { media-size { x, y }, margins = 0 } */
    private fun ByteArrayOutputStream.writeMediaColEntry(name: String, widthHmm: Int, heightHmm: Int) {
        writeBeginCollection(name)
        writeMemberName("media-size")
        writeBeginCollectionValue() // nested collection
        writeMemberName("x-dimension")
        writeMemberInteger(widthHmm)
        writeMemberName("y-dimension")
        writeMemberInteger(heightHmm)
        writeEndCollection() // end media-size
        writeMemberName("media-left-margin")
        writeMemberInteger(0)
        writeMemberName("media-right-margin")
        writeMemberInteger(0)
        writeMemberName("media-top-margin")
        writeMemberInteger(0)
        writeMemberName("media-bottom-margin")
        writeMemberInteger(0)
        writeEndCollection() // end media-col entry
    }

    /** Additional media-col entry (same attribute) */
    private fun ByteArrayOutputStream.writeMediaColEntryValue(widthHmm: Int, heightHmm: Int) {
        writeBeginCollectionValue()
        writeMemberName("media-size")
        writeBeginCollectionValue()
        writeMemberName("x-dimension")
        writeMemberInteger(widthHmm)
        writeMemberName("y-dimension")
        writeMemberInteger(heightHmm)
        writeEndCollection()
        writeMemberName("media-left-margin")
        writeMemberInteger(0)
        writeMemberName("media-right-margin")
        writeMemberInteger(0)
        writeMemberName("media-top-margin")
        writeMemberInteger(0)
        writeMemberName("media-bottom-margin")
        writeMemberInteger(0)
        writeEndCollection()
    }

    // ── HTTP Helpers ─────────────────────────────────────────────────────

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b == '\r'.code) continue
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        var emptyCount = 0
        while (true) {
            val sizeLine = readLine(input).trim()
            if (sizeLine.isEmpty()) {
                if (++emptyCount > 10) break
                continue
            }
            emptyCount = 0
            val size = sizeLine.toIntOrNull(16) ?: 0
            if (size == 0) break
            val chunk = ByteArray(size)
            readFully(input, chunk)
            result.write(chunk)
            readLine(input) // consume trailing CRLF
        }
        return result.toByteArray()
    }

    // ── Network ──────────────────────────────────────────────────────────

    private fun getLocalIpAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return "127.0.0.1"
    }

    // ── mDNS Registration ────────────────────────────────────────────────

    private fun registerMdns(context: Context) {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = printerName
                serviceType = "_ipp._tcp"
                port = this@IppServer.port
                setAttribute("txtvers", "1")
                setAttribute("pdl", "application/pdf")
                setAttribute("rp", "ipp/print")
                setAttribute("ty", printerName)
                setAttribute("UUID", printerUuid)
                setAttribute("product", "(LibroPrinter Thermal)")
                setAttribute("note", "Thermal Receipt Printer")
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
                    Log.e(TAG, "mDNS registration failed: $err")
                }
                override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {
                    Log.e(TAG, "mDNS unregistration failed: $err")
                }
                override fun onServiceRegistered(si: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered: ${si.serviceName}")
                }
                override fun onServiceUnregistered(si: NsdServiceInfo) {
                    Log.i(TAG, "mDNS unregistered")
                }
            }
            registrationListener = listener

            nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).also {
                it.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "mDNS registration error", e)
        }
    }

    private fun unregisterMdns() {
        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.e(TAG, "mDNS unregister error", e)
            }
        }
        registrationListener = null
        nsdManager = null
    }

    companion object {
        private const val TAG = "IppServer"

        // IPP Group Tags
        private const val TAG_OPERATION_ATTRIBUTES = 0x01
        private const val TAG_JOB_ATTRIBUTES = 0x02
        private const val TAG_END_OF_ATTRIBUTES = 0x03
        private const val TAG_PRINTER_ATTRIBUTES = 0x04

        // IPP Operations
        private const val OP_PRINT_JOB = 0x0002
        private const val OP_VALIDATE_JOB = 0x0004
        private const val OP_GET_JOB_ATTRIBUTES = 0x0009
        private const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

        // IPP Status Codes
        private const val STATUS_OK = 0x0000

        // IPP Job States
        private const val JOB_STATE_COMPLETED = 9
    }
}
