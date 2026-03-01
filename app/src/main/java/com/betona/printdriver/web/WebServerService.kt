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
 * Foreground service that hosts web/RAW/IPP servers.
 * Each server can be individually toggled via Intent actions.
 * Shows a persistent notification while any server is running.
 */
class WebServerService : Service() {

    private var server: WebManagementServer? = null
    private var rawPrintServer: RawPrintServer? = null
    private var ippServer: IppServer? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        }

        when (intent?.action) {
            ACTION_TOGGLE -> {
                val type = intent.getStringExtra(EXTRA_SERVER_TYPE) ?: return START_STICKY
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                when (type) {
                    SERVER_WEB -> if (enabled) startWeb() else stopWeb()
                    SERVER_RAW -> if (enabled) startRaw() else stopRaw()
                    SERVER_IPP -> if (enabled) startIpp() else stopIpp()
                }
                updateNotification()
                // If all servers stopped, stop the service
                if (server == null && rawPrintServer == null && ippServer == null) {
                    stopSelf()
                }
            }
            else -> {
                // Default: start all servers that aren't already running
                if (server == null) startWeb()
                if (rawPrintServer == null) startRaw()
                if (ippServer == null) startIpp()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAllServers()
        foregroundStarted = false
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Individual server start/stop ─────────────────────────────────────

    private fun startWeb() {
        if (server != null) return
        try {
            server = WebManagementServer(applicationContext, PORT).also { it.start() }
            isWebRunning = true
            Log.i(TAG, "Web server started on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
        }
    }

    private fun stopWeb() {
        try {
            server?.stop()
            server = null
            isWebRunning = false
            Log.i(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping web server", e)
        }
    }

    private fun startRaw() {
        if (rawPrintServer != null) return
        try {
            rawPrintServer = RawPrintServer(RAW_PORT).also { it.start() }
            isRawRunning = true
            Log.i(TAG, "RAW print server started on port $RAW_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RAW print server", e)
        }
    }

    private fun stopRaw() {
        try {
            rawPrintServer?.stop()
            rawPrintServer = null
            isRawRunning = false
            Log.i(TAG, "RAW print server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RAW print server", e)
        }
    }

    private fun startIpp() {
        if (ippServer != null) return
        try {
            ippServer = IppServer(IPP_PORT).also { it.start(applicationContext) }
            isIppRunning = true
            Log.i(TAG, "IPP server started on port $IPP_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start IPP server", e)
        }
    }

    private fun stopIpp() {
        try {
            ippServer?.stop()
            ippServer = null
            isIppRunning = false
            Log.i(TAG, "IPP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping IPP server", e)
        }
    }

    private fun stopAllServers() {
        stopIpp()
        stopRaw()
        stopWeb()
    }

    // ── Notification ─────────────────────────────────────────────────────

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

    private fun updateNotification() {
        if (foregroundStarted) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val parts = mutableListOf<String>()
        if (server != null) parts.add("웹:$PORT")
        if (rawPrintServer != null) parts.add("RAW:$RAW_PORT")
        if (ippServer != null) parts.add("IPP:$IPP_PORT")
        val text = if (parts.isEmpty()) "서버 중지 중..." else parts.joinToString(" / ")

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("프린트 서버 실행 중")
            .setContentText(text)
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

        private const val ACTION_TOGGLE = "com.betona.printdriver.TOGGLE_SERVER"
        private const val EXTRA_SERVER_TYPE = "server_type"
        private const val EXTRA_ENABLED = "enabled"
        const val SERVER_WEB = "web"
        const val SERVER_RAW = "raw"
        const val SERVER_IPP = "ipp"

        @Volatile var isWebRunning = false
        @Volatile var isRawRunning = false
        @Volatile var isIppRunning = false
        val isRunning get() = isWebRunning || isRawRunning || isIppRunning

        /** Start the service (all servers). */
        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the entire service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, WebServerService::class.java))
        }

        /** Toggle a specific server on or off. */
        fun toggleServer(context: Context, serverType: String, enabled: Boolean) {
            val intent = Intent(context, WebServerService::class.java).apply {
                action = ACTION_TOGGLE
                putExtra(EXTRA_SERVER_TYPE, serverType)
                putExtra(EXTRA_ENABLED, enabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
