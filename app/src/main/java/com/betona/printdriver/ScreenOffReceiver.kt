package com.betona.printdriver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives screen-off alarm from AlarmManager at scheduled end time.
 * Launches WebPrintActivity with countdown UI (1 minute before screen off).
 */
class ScreenOffReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenOffReceiver"
        const val EXTRA_SCREEN_OFF_COUNTDOWN = "SCREEN_OFF_COUNTDOWN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Screen-off alarm triggered, launching countdown UI")

        val launchIntent = Intent(context, WebPrintActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SCREEN_OFF_COUNTDOWN, true)
        }
        context.startActivity(launchIntent)

        // Schedule next alarm for tomorrow
        PowerScheduleManager.scheduleNext(context)
    }
}
