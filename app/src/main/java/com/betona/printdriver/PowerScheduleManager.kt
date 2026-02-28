package com.betona.printdriver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import java.util.Calendar

/**
 * Manages automatic screen on/off schedule.
 * - Screen off: AlarmManager exact alarm → countdown → clear FLAG_KEEP_SCREEN_ON
 * - Screen on: AlarmManager exact alarm → WakeLock ACQUIRE_CAUSES_WAKEUP
 * No root/su needed. Safe — no bricking risk.
 */
object PowerScheduleManager {

    private const val TAG = "PowerSchedule"
    private const val REQUEST_SCREEN_OFF = 9001
    private const val REQUEST_SCREEN_ON = 9002

    /**
     * Schedule next screen-off and screen-on alarms.
     * Call after boot and after schedule changes.
     */
    fun scheduleNext(context: Context) {
        val schedules = (0..6).map { AppPrefs.getDaySchedule(context, it) }
        if (schedules.none { it.enabled }) {
            cancelAlarm(context, REQUEST_SCREEN_OFF)
            cancelAlarm(context, REQUEST_SCREEN_ON)
            Log.i(TAG, "No schedule enabled, cleared all alarms")
            return
        }

        val now = Calendar.getInstance()

        // Schedule next screen-off (end time)
        val nextOff = findNextTime(now, schedules, useEndTime = true)
        if (nextOff != null) {
            setAlarm(context, nextOff.timeInMillis, REQUEST_SCREEN_OFF, ScreenOffReceiver::class.java)
            Log.i(TAG, "Next screen-off: ${fmt(nextOff)}")
        }

        // Schedule next screen-on (start time)
        val nextOn = findNextTime(now, schedules, useEndTime = false)
        if (nextOn != null) {
            setAlarm(context, nextOn.timeInMillis, REQUEST_SCREEN_ON, ScreenOnReceiver::class.java)
            Log.i(TAG, "Next screen-on: ${fmt(nextOn)}")
        }
    }

    /**
     * Find the next occurrence of a start or end time from now.
     * Searches up to 8 days ahead (covers all 7 weekdays).
     */
    private fun findNextTime(
        from: Calendar, schedules: List<DaySchedule>, useEndTime: Boolean
    ): Calendar? {
        for (daysAhead in 0..7) {
            val check = (from.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, daysAhead)
            }
            val dayIndex = calendarDayToIndex(check.get(Calendar.DAY_OF_WEEK))
            val sched = schedules[dayIndex]
            if (!sched.enabled) continue

            val hour = if (useEndTime) sched.endHour else sched.startHour
            val min = if (useEndTime) sched.endMin else sched.startMin

            val target = (check.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.after(from)) return target
        }
        return null
    }

    /** Calendar.DAY_OF_WEEK (Sun=1..Sat=7) → our index (Mon=0..Sun=6) */
    private fun calendarDayToIndex(calDay: Int): Int = when (calDay) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        Calendar.SUNDAY -> 6
        else -> 0
    }

    private fun setAlarm(context: Context, triggerMillis: Long, requestCode: Int, receiverClass: Class<*>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, receiverClass),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
    }

    private fun cancelAlarm(context: Context, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Cancel both receiver types
        for (cls in arrayOf(ScreenOffReceiver::class.java, ScreenOnReceiver::class.java)) {
            val pi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, cls),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    /**
     * Turn screen on using WakeLock with ACQUIRE_CAUSES_WAKEUP.
     */
    @Suppress("DEPRECATION")
    fun wakeUpScreen(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "libro:screenon"
        )
        wl.acquire(5000) // hold for 5 seconds, then release
        Log.i(TAG, "Screen wake-up triggered")
    }

    private fun fmt(cal: Calendar): String = String.format(
        "%tF %tT", cal, cal
    )
}
