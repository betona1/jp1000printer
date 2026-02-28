package com.betona.printdriver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives screen-on alarm from AlarmManager at scheduled start time.
 * Wakes up the screen and launches WebPrintActivity.
 */
class ScreenOnReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("ScreenOnReceiver", "Screen-on alarm triggered, waking screen")

        // Wake up the screen
        PowerScheduleManager.wakeUpScreen(context)

        // Launch WebPrintActivity
        val launchIntent = Intent(context, WebPrintActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(launchIntent)

        // Schedule next alarms
        PowerScheduleManager.scheduleNext(context)
    }
}
