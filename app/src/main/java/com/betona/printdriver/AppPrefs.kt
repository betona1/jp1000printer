package com.betona.printdriver

import android.content.Context
import android.util.Log
import java.io.File

data class DaySchedule(
    val enabled: Boolean = false,
    val startHour: Int = 9,
    val startMin: Int = 0,
    val endHour: Int = 18,
    val endMin: Int = 0
)

object AppPrefs {
    private const val TAG = "AppPrefs"
    private const val PREFS_NAME = "libro_prefs"
    private const val KEY_SCHOOL_URL = "school_url"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_SHOW_POWER_BTN = "show_power_btn"
    private const val KEY_SHOW_SCHEDULE = "show_schedule"
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
                    val bootFile = File(context.filesDir, "libro_autostart.sh")
                    bootFile.writeText(
                        "#!/system/bin/sh\nsleep 15\nam start -n $component\nsleep 3\nam start -n $component\n"
                    )
                    su("cp ${bootFile.absolutePath} $BOOT_SCRIPT")
                    su("chmod 755 $BOOT_SCRIPT")
                    bootFile.delete()

                    // Write init .rc file (autostart only, no shutdown service)
                    val rcFile = File(context.filesDir, "libro_autostart.rc")
                    rcFile.writeText(
                        "service libro_autostart $BOOT_SCRIPT\n" +
                        "    user root\n" +
                        "    oneshot\n" +
                        "    disabled\n" +
                        "\n" +
                        "on property:sys.boot_completed=1\n" +
                        "    start libro_autostart\n"
                    )
                    su("cp ${rcFile.absolutePath} $BOOT_RC")
                    su("chmod 644 $BOOT_RC")
                    rcFile.delete()

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

    fun getShowPowerButton(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_POWER_BTN, false)
    }

    fun setShowPowerButton(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_POWER_BTN, show).apply()
    }

    fun getShowSchedule(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SCHEDULE, false)
    }

    fun setShowSchedule(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_SCHEDULE, show).apply()
    }

    /** Get today's schedule for display */
    fun getTodayScheduleText(context: Context): String? {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        val dayIndex = when (today) {
            java.util.Calendar.MONDAY -> 0; java.util.Calendar.TUESDAY -> 1
            java.util.Calendar.WEDNESDAY -> 2; java.util.Calendar.THURSDAY -> 3
            java.util.Calendar.FRIDAY -> 4; java.util.Calendar.SATURDAY -> 5
            java.util.Calendar.SUNDAY -> 6; else -> 0
        }
        val sched = getDaySchedule(context, dayIndex)
        if (!sched.enabled) return null
        return String.format("%02d:%02d~%02d:%02d", sched.startHour, sched.startMin, sched.endHour, sched.endMin)
    }

    // ── Schedule ──────────────────────────────────────────────────────

    fun getDaySchedule(context: Context, dayIndex: Int): DaySchedule {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DaySchedule(
            enabled = prefs.getBoolean("sched_${dayIndex}_on", false),
            startHour = prefs.getInt("sched_${dayIndex}_sh", 9),
            startMin = prefs.getInt("sched_${dayIndex}_sm", 0),
            endHour = prefs.getInt("sched_${dayIndex}_eh", 18),
            endMin = prefs.getInt("sched_${dayIndex}_em", 0)
        )
    }

    fun setDaySchedule(context: Context, dayIndex: Int, schedule: DaySchedule) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("sched_${dayIndex}_on", schedule.enabled)
            .putInt("sched_${dayIndex}_sh", schedule.startHour)
            .putInt("sched_${dayIndex}_sm", schedule.startMin)
            .putInt("sched_${dayIndex}_eh", schedule.endHour)
            .putInt("sched_${dayIndex}_em", schedule.endMin)
            .apply()
    }

    private fun su(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
        val exit = p.waitFor()
        Log.d(TAG, "su exit=$exit: $cmd")
    }
}
