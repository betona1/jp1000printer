package com.betona.printdriver.web

import android.util.Log
import com.betona.printdriver.DevicePrinter
import java.io.InputStream
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
        var totalBytes = 0L
        try {
            // Ensure printer is open
            if (!DevicePrinter.isOpen) {
                DevicePrinter.open()
                DevicePrinter.initPrinter()
            }

            val input: InputStream = socket.getInputStream()
            val buf = ByteArray(65536) // 64KB buffer
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                DevicePrinter.write(buf.copyOf(n))
                totalBytes += n
            }

            // Print job finished — feed & cut
            if (totalBytes > 0) {
                DevicePrinter.feedAndCut()
                Log.i(TAG, "Job done: $totalBytes bytes, cut sent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error after $totalBytes bytes", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "RawPrintServer"
    }
}
