package com.betona.libroprintplugin

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * IPP client that sends Print-Job requests to a LibroPrinter kiosk.
 * Builds IPP 2.0 binary requests and wraps them in HTTP POST.
 */
object IppClient {

    private const val TAG = "IppClient"
    private var requestIdCounter = 1

    /**
     * Send a PDF document as an IPP Print-Job request.
     * @return true if the printer accepted the job (status 0x0000)
     */
    fun sendPrintJob(host: String, port: Int, pdfData: ByteArray): Boolean {
        val printerUri = "ipp://$host:$port/ipp/print"
        val requestId = synchronized(this) { requestIdCounter++ }

        Log.i(TAG, "Sending Print-Job to $printerUri (${pdfData.size} bytes, reqId=$requestId)")

        val ippRequest = buildPrintJobRequest(printerUri, requestId, pdfData)

        // Use HTTP POST to ipp endpoint
        val httpUrl = "http://$host:$port/ipp/print"
        val connection = URL(httpUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/ipp")
            connection.setRequestProperty("Content-Length", ippRequest.size.toString())
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000

            connection.outputStream.use { it.write(ippRequest) }

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP response: $responseCode")

            val responseStream: InputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                Log.e(TAG, "HTTP error: $responseCode")
                return false
            }

            val responseBody = responseStream.use { it.readBytes() }
            return parseIppResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Print-Job request failed", e)
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPrintJobRequest(printerUri: String, requestId: Int, pdfData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // IPP version 2.0
        out.write(2) // major
        out.write(0) // minor

        // Operation: Print-Job (0x0002)
        out.writeShortBE(0x0002)

        // Request ID
        out.writeIntBE(requestId)

        // Operation attributes group
        out.write(0x01)

        // attributes-charset = utf-8
        out.writeStringAttr(0x47, "attributes-charset", "utf-8")

        // attributes-natural-language = en
        out.writeStringAttr(0x48, "attributes-natural-language", "en")

        // printer-uri
        out.writeStringAttr(0x45, "printer-uri", printerUri)

        // requesting-user-name
        out.writeStringAttr(0x42, "requesting-user-name", "LibroPrintPlugin")

        // job-name
        out.writeStringAttr(0x42, "job-name", "Print Job")

        // document-format = application/pdf
        out.writeStringAttr(0x49, "document-format", "application/pdf")

        // End of attributes
        out.write(0x03)

        // Document data
        out.write(pdfData)

        return out.toByteArray()
    }

    private fun parseIppResponse(body: ByteArray): Boolean {
        if (body.size < 4) {
            Log.e(TAG, "IPP response too short: ${body.size} bytes")
            return false
        }

        val verMajor = body[0].toInt() and 0xFF
        val verMinor = body[1].toInt() and 0xFF
        val statusCode = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)

        Log.i(TAG, "IPP response: v$verMajor.$verMinor status=0x${statusCode.toString(16).padStart(4, '0')}")

        // Status codes 0x0000-0x00FF are successful
        return statusCode <= 0x00FF
    }

    // ── ByteArrayOutputStream helpers ────────────────────────────────────

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

    private fun ByteArrayOutputStream.writeStringAttr(tag: Int, name: String, value: String) {
        write(tag)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        writeShortBE(nameBytes.size)
        write(nameBytes)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        writeShortBE(valueBytes.size)
        write(valueBytes)
    }
}
