package com.betona.printdriver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-enables the PrintService after reboot and optionally auto-launches the app.
 *
 * On A40i (Android 7), the system resets enabled_print_services
 * and adds our service to disabled_print_services on each boot.
 * This receiver restores the correct settings via su.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val SERVICE_COMPONENT =
            "com.android.printdriver/com.betona.printdriver.LibroPrintService"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed, configuring print service...")

        Thread {
            try {
                // Wait for system services to be fully ready
                Thread.sleep(5000)

                val commands = arrayOf(
                    "settings put secure enabled_print_services $SERVICE_COMPONENT",
                    "settings put secure disabled_print_services ''",
                    "pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION",
                    "pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION"
                )

                for (cmd in commands) {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
                    val exit = process.waitFor()
                    Log.d(TAG, "cmd=[$cmd] exit=$exit")
                }

                Log.i(TAG, "Print service configuration complete")

                // Schedule next auto-shutdown if schedule is configured
                PowerScheduleManager.scheduleNext(context)

                // Auto-start app if enabled (use am start via su for Android 7 compatibility)
                if (AppPrefs.getAutoStart(context)) {
                    Log.i(TAG, "Auto-start enabled, launching app via am start...")
                    val component = "${context.packageName}/com.betona.printdriver.WebPrintActivity"
                    val cmd = "am start -n $component"
                    val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
                    val exit = process.waitFor()
                    Log.i(TAG, "Auto-start cmd=[$cmd] exit=$exit")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure print service", e)
            }
        }.start()
    }
}
