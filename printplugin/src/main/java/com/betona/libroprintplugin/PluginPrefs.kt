package com.betona.libroprintplugin

import android.content.Context

/**
 * SharedPreferences for the plugin: which kiosk should appear in the print picker.
 *
 * - empty / null  → all discovered LibroPrinter kiosks are shown (legacy behavior)
 * - "<service-name>" → only this specific kiosk is shown
 *
 * The user picks one in [PluginSettingsActivity].
 */
object PluginPrefs {

    private const val PREFS_NAME = "libroprintplugin_prefs"
    private const val KEY_PREFERRED_PRINTER = "preferred_printer"

    fun getPreferredPrinter(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_PRINTER, "") ?: ""
    }

    fun setPreferredPrinter(context: Context, serviceName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_PRINTER, serviceName)
            .apply()
    }

    fun clearPreferredPrinter(context: Context) {
        setPreferredPrinter(context, "")
    }
}
