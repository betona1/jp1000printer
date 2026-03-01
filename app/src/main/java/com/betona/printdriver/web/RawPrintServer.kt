package com.betona.printdriver.web

import android.util.Log
import com.betona.printdriver.DevicePrinter
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * RAW TCP print server on port 9100.
 * Accepts connections from Windows "Generic / Text Only" driver
 * and forwards received data directly to the thermal printer.
 */
class RawPrintServer(private val port: Int = 9100) {

    private var serverSocket: ServerSocket? = null
    private var listenThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        listenThread = Thread({
            try {
                serverSocket = ServerSocket(port).also { ss ->
                    ss.reuseAddress = true
                    Log.i(TAG, "Listening on port $port")
                    while (running) {
                        try {
                            val client = ss.accept()
                            Log.i(TAG, "Client connected: ${client.remoteSocketAddress}")
                            Thread({ handleClient(client) }, "raw-print-client").start()
                        } catch (e: Exception) {
                            if (running) Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start error", e)
            }
        }, "raw-print-server")
        listenThread!!.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        listenThread = null
        Log.i(TAG, "Stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            // Ensure printer is open
            if (!DevicePrinter.isOpen) {
                DevicePrinter.open()
                DevicePrinter.initPrinter()
            }

            // Buffer entire job to allow post-processing
            val buffer = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                buffer.write(buf, 0, n)
            }

            val raw = buffer.toByteArray()
            if (raw.isEmpty()) return

            val data = trimRawJob(raw)
            if (data.isEmpty()) return

            // Send to printer in chunks
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + 65536, data.size)
                DevicePrinter.write(data.copyOfRange(offset, end))
                offset = end
            }

            DevicePrinter.feedAndCut()
            Log.i(TAG, "Job done: ${raw.size} -> ${data.size} bytes (trimmed), cut sent")
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Trim Windows "Generic / Text Only" driver padding:
     * - Replace form feed (0x0C) with 2 newlines (page separator)
     * - Strip trailing whitespace (SP, CR, LF, FF, NUL)
     * This prevents A4-page-length paper waste on thermal printer.
     */
    private fun trimRawJob(raw: ByteArray): ByteArray {
        // Replace FF (0x0C) with 2 newlines as page separator
        val out = ByteArrayOutputStream(raw.size)
        for (b in raw) {
            if (b.toInt() and 0xFF == 0x0C) {
                out.write(0x0A) // LF
                out.write(0x0A) // LF
            } else {
                out.write(b.toInt())
            }
        }
        var data = out.toByteArray()

        // Strip trailing whitespace (SP, CR, LF, NUL)
        var end = data.size
        while (end > 0) {
            val c = data[end - 1].toInt() and 0xFF
            if (c == 0x20 || c == 0x0D || c == 0x0A || c == 0x00) {
                end--
            } else {
                break
            }
        }
        return if (end == data.size) data else data.copyOfRange(0, end)
    }

    companion object {
        private const val TAG = "RawPrintServer"
    }
}
