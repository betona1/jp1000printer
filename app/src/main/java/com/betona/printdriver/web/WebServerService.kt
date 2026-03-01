package com.betona.printdriver.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that hosts the web management server.
 * Shows a persistent notification while the server is running.
 */
class WebServerService : Service() {

    private var server: WebManagementServer? = null
    private var rawPrintServer: RawPrintServer? = null
    private var ippServer: IppServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        try {
            server = WebManagementServer(applicationContext, PORT).also {
                it.start()
            }
            Log.i(TAG, "Web server started on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
        }
        try {
            rawPrintServer = RawPrintServer(RAW_PORT).also {
                it.start()
            }
            Log.i(TAG, "RAW print server started on port $RAW_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RAW print server", e)
        }
        try {
            ippServer = IppServer(IPP_PORT).also {
                it.start(applicationContext)
            }
            Log.i(TAG, "IPP server started on port $IPP_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start IPP server", e)
        }
    }

    private fun stopServer() {
        try {
            ippServer?.stop()
            ippServer = null
            Log.i(TAG, "IPP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping IPP server", e)
        }
        try {
            rawPrintServer?.stop()
            rawPrintServer = null
            Log.i(TAG, "RAW print server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RAW print server", e)
        }
        try {
            server?.stop()
            server = null
            Log.i(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping web server", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "웹 관리 서버",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "웹 관리 서버 실행 상태 표시"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("웹 관리 서버 실행 중")
            .setContentText("웹 :$PORT / RAW :$RAW_PORT / IPP :$IPP_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WebServerService"
        private const val PORT = 8080
        private const val RAW_PORT = 9100
        private const val IPP_PORT = 6631
        private const val CHANNEL_ID = "web_server_channel"
        private const val NOTIFICATION_ID = 9080

        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebServerService::class.java))
        }
    }
}
