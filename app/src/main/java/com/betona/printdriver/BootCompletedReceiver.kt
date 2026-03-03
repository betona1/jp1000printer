package com.betona.printdriver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Re-enables the PrintService after reboot and optionally auto-launches the app.
 *
 * Strategy:
 *   1. Try Settings.Secure.putString() directly (Android 11+ with WRITE_SECURE_SETTINGS)
 *   2. Fall back to su shell commands (A40i / rooted devices)
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed, configuring print service...")

        Thread {
            try {
                // Wait for system services to be fully ready
                Thread.sleep(5000)

                val serviceComponent =
                    "${context.packageName}/com.betona.printdriver.LibroPrintService"

                val configured = trySettingsApi(context, serviceComponent)
                    || trySuCommands(context, serviceComponent)

                if (configured) {
                    Log.i(TAG, "Print service configuration complete")
                } else {
                    Log.e(TAG, "All configuration methods failed")
                }

                // Schedule next auto-shutdown if schedule is configured
                PowerScheduleManager.scheduleNext(context)

                // Auto-start app if enabled (use am start via su for Android 7 compatibility)
                if (AppPrefs.getAutoStart(context)) {
                    Log.i(TAG, "Auto-start enabled, launching app...")
                    val component = "${context.packageName}/com.betona.printdriver.WebPrintActivity"
                    try {
                        // Try direct Intent first (works on Android 11+)
                        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setClassName(context.packageName, "com.betona.printdriver.WebPrintActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(launchIntent)
                        Log.i(TAG, "Auto-start via startActivity success")
                    } catch (e: Exception) {
                        Log.d(TAG, "startActivity failed, trying su am start...")
                        val cmd = "am start -n $component"
                        val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
                        val exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                        val exit = if (exited) process.exitValue() else -1
                        if (!exited) process.destroyForcibly()
                        Log.i(TAG, "Auto-start su cmd exit=$exit")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure print service", e)
            }
        }.start()
    }

    /**
     * Method 1: Use Settings.Secure API directly.
     * Requires WRITE_SECURE_SETTINGS permission (grantable via adb on Android 11+).
     */
    private fun trySettingsApi(context: Context, serviceComponent: String): Boolean {
        return try {
            val cr = context.contentResolver
            Settings.Secure.putString(cr, "enabled_print_services", serviceComponent)
            Settings.Secure.putString(cr, "disabled_print_services", "")

            // Grant PrintSpooler location permissions via shell (no su needed for pm grant on some devices)
            grantPermissionQuietly("com.android.printspooler", "android.permission.ACCESS_COARSE_LOCATION")
            grantPermissionQuietly("com.android.printspooler", "android.permission.ACCESS_FINE_LOCATION")

            // Verify
            val enabled = Settings.Secure.getString(cr, "enabled_print_services") ?: ""
            val ok = enabled.contains("LibroPrintService")
            Log.i(TAG, "Settings API: enabled=$enabled ok=$ok")
            ok
        } catch (e: SecurityException) {
            Log.d(TAG, "Settings API not available (no WRITE_SECURE_SETTINGS): ${e.message}")
            false
        }
    }

    /**
     * Method 2: Use su shell commands (for rooted devices like A40i).
     */
    private fun trySuCommands(context: Context, serviceComponent: String): Boolean {
        return try {
            val commands = arrayOf(
                "settings put secure enabled_print_services $serviceComponent",
                "settings put secure disabled_print_services ''",
                "pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION",
                "pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION"
            )

            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
                val exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                val exit = if (exited) process.exitValue() else -1
                if (!exited) process.destroyForcibly()
                Log.d(TAG, "su cmd=[$cmd] exit=$exit${if (!exited) " (timeout)" else ""}")
            }
            Log.i(TAG, "su commands completed")
            true
        } catch (e: Exception) {
            Log.d(TAG, "su not available: ${e.message}")
            false
        }
    }

    private fun grantPermissionQuietly(pkg: String, perm: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm grant $pkg $perm"))
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }
}
