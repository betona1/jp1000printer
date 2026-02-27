package com.betona.printdriver

import android.content.Context
import android.util.Log

object AppPrefs {
    private const val TAG = "AppPrefs"
    private const val PREFS_NAME = "libro_prefs"
    private const val KEY_SCHOOL_URL = "school_url"
    private const val KEY_AUTO_START = "auto_start"
    private const val DEFAULT_SCHOOL_URL = "https://read365.edunet.net/SchoolSearch"

    private const val BOOT_SCRIPT = "/system/bin/libro_autostart.sh"
    private const val BOOT_RC = "/system/etc/init/libro_autostart.rc"
    private const val REMOUNT_RW = "mount -o rw,remount /dev/block/by-name/system /system"
    private const val REMOUNT_RO = "mount -o ro,remount /dev/block/by-name/system /system"

    fun getSchoolUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCHOOL_URL, DEFAULT_SCHOOL_URL) ?: DEFAULT_SCHOOL_URL
    }

    fun setSchoolUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCHOOL_URL, url)
            .apply()
    }

    fun getAutoStart(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, false)
    }

    fun setAutoStart(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()

        installBootScript(context, enabled)
    }

    /**
     * Installs a boot script + .rc file into /system/etc/init/ for auto-start.
     * Android init reads .rc files from this directory at boot.
     * Trigger: sys.boot_completed=1 → runs shell script → am start.
     * This bypasses BackgroundManagerService's BOOT_COMPLETED broadcast blocking on A40i.
     */
    private fun installBootScript(context: Context, enabled: Boolean) {
        Thread {
            try {
                val pkg = context.packageName
                val component = "$pkg/com.betona.printdriver.WebPrintActivity"

                su(REMOUNT_RW)

                if (enabled) {
                    // Write boot shell script (sleep 15 to wait for ActivityManager)
                    su("echo '#!/system/bin/sh' > $BOOT_SCRIPT")
                    su("echo 'sleep 15' >> $BOOT_SCRIPT")
                    su("echo 'am start -n $component' >> $BOOT_SCRIPT")
                    su("echo 'sleep 3' >> $BOOT_SCRIPT")
                    su("echo 'am start -n $component' >> $BOOT_SCRIPT")
                    su("chmod 755 $BOOT_SCRIPT")

                    // Write init .rc file
                    su("echo 'service libro_autostart $BOOT_SCRIPT' > $BOOT_RC")
                    su("echo '    user root' >> $BOOT_RC")
                    su("echo '    oneshot' >> $BOOT_RC")
                    su("echo '    disabled' >> $BOOT_RC")
                    su("echo '' >> $BOOT_RC")
                    su("echo 'on property:sys.boot_completed=1' >> $BOOT_RC")
                    su("echo '    start libro_autostart' >> $BOOT_RC")
                    su("chmod 644 $BOOT_RC")

                    Log.i(TAG, "Boot script installed: $BOOT_RC")
                } else {
                    su("rm -f $BOOT_SCRIPT $BOOT_RC")
                    Log.i(TAG, "Boot script removed")
                }

                su(REMOUNT_RO)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install/remove boot script", e)
                try { su(REMOUNT_RO) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun su(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
        val exit = p.waitFor()
        Log.d(TAG, "su exit=$exit: $cmd")
    }
}
