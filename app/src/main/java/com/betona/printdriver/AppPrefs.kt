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
    private const val KEY_ADMIN_PASSWORD = "admin_password"
    private const val KEY_CUT_MODE = "cut_mode"
    private const val KEY_MOBILE_MODE = "mobile_mode"
    private const val KEY_SHOW_CLOCK = "show_clock"
    private const val KEY_LANDSCAPE = "landscape_mode"
    private const val KEY_SHOW_ROTATE = "show_rotate_btn"
    private const val KEY_SHOW_GAMES = "show_games"
    private const val KEY_NIGHT_SAVE = "night_save_mode"
    private const val KEY_NIGHT_SAVE_START_H = "night_save_start_h"
    private const val KEY_NIGHT_SAVE_START_M = "night_save_start_m"
    private const val KEY_NIGHT_SAVE_END_H = "night_save_end_h"
    private const val KEY_NIGHT_SAVE_END_M = "night_save_end_m"
    private const val DEFAULT_SCHOOL_URL = "https://read365.edunet.net/SchoolSearch"
    private const val DEFAULT_PASSWORD = "1234"

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

    // ── Admin Password ──────────────────────────────────────────────────

    fun getAdminPassword(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ADMIN_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
    }

    fun setAdminPassword(context: Context, password: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ADMIN_PASSWORD, password).apply()
    }

    fun isDefaultPassword(context: Context): Boolean {
        return getAdminPassword(context) == DEFAULT_PASSWORD
    }

    // ── Cut Mode ─────────────────────────────────────────────────────

    fun getCutMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUT_MODE, "full") ?: "full"
    }

    fun setCutMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUT_MODE, mode).apply()
    }

    fun isFullCut(context: Context): Boolean = getCutMode(context) == "full"

    // ── Mobile Mode ──────────────────────────────────────────────────

    fun isMobileMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MOBILE_MODE, true)
    }

    fun setMobileMode(context: Context, mobile: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MOBILE_MODE, mobile).apply()
    }

    // ── Show Clock ───────────────────────────────────────────────────

    fun getShowClock(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_CLOCK, true)
    }

    fun setShowClock(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_CLOCK, show).apply()
    }

    // ── Landscape Mode ───────────────────────────────────────────────

    fun isLandscape(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LANDSCAPE, false)
    }

    fun setLandscape(context: Context, landscape: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LANDSCAPE, landscape).apply()
    }

    // ── Show Rotate Button ───────────────────────────────────────────

    fun getShowRotateButton(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_ROTATE, false)
    }

    fun setShowRotateButton(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_ROTATE, show).apply()
    }

    // ── Game Visibility ─────────────────────────────────────────────

    fun getShowGames(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_GAMES, false)
    }

    fun setShowGames(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_GAMES, show).apply()
    }

    // ── Night Save Mode ───────────────────────────────────────────────

    fun isNightSaveMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NIGHT_SAVE, true)
    }

    fun setNightSaveMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NIGHT_SAVE, enabled).apply()
    }

    fun getNightSaveDaytimeStart(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(prefs.getInt(KEY_NIGHT_SAVE_START_H, 9), prefs.getInt(KEY_NIGHT_SAVE_START_M, 0))
    }

    fun getNightSaveDaytimeEnd(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(prefs.getInt(KEY_NIGHT_SAVE_END_H, 18), prefs.getInt(KEY_NIGHT_SAVE_END_M, 0))
    }

    fun setNightSaveDaytimeStart(context: Context, hour: Int, min: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NIGHT_SAVE_START_H, hour).putInt(KEY_NIGHT_SAVE_START_M, min).apply()
    }

    fun setNightSaveDaytimeEnd(context: Context, hour: Int, min: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NIGHT_SAVE_END_H, hour).putInt(KEY_NIGHT_SAVE_END_M, min).apply()
    }

    /** Check if current time is within daytime active hours */
    fun isDaytime(context: Context): Boolean {
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val (sh, sm) = getNightSaveDaytimeStart(context)
        val (eh, em) = getNightSaveDaytimeEnd(context)
        val startMin = sh * 60 + sm
        val endMin = eh * 60 + em
        return nowMin in startMin until endMin
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
