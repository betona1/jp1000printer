package com.betona.libroprintplugin

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent registry of LibroPrinter kiosks the user has successfully printed to.
 *
 * Why this exists: Android creates a fresh PrinterDiscoverySession for every
 * print, and the system caches printerId across sessions. If the user prints
 * twice in a row, the second session may receive `onStartPrinterStateTracking`
 * for the cached printerId BEFORE NSD has had time to rediscover the kiosk.
 *
 * Without a persistent endpoint cache, we'd have to silently fail in that
 * window, and the print picker would show "사용할 수 없습니다". By persisting
 * (name, host, port) and re-publishing immediately at session start, the
 * picker stays responsive across consecutive prints.
 */
object KioskRegistry {

    private const val TAG = "KioskRegistry"
    private const val PREFS_NAME = "libroprintplugin_kiosks"
    private const val KEY_KIOSKS = "kiosks_json"

    data class Kiosk(
        val name: String,    // mDNS service name, e.g. "LibroPrinter-도서관1"
        val host: String,    // last-known IP
        val port: Int,       // last-known IPP port (typically 6631)
        val lastSeenMs: Long // for staleness tracking
    )

    /** All kiosks ever successfully discovered. Caller filters for staleness. */
    @Synchronized
    fun getAll(context: Context): List<Kiosk> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_KIOSKS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Kiosk(
                    name = o.optString("name").ifEmpty { return@mapNotNull null },
                    host = o.optString("host").ifEmpty { return@mapNotNull null },
                    port = o.optInt("port", 6631),
                    lastSeenMs = o.optLong("lastSeen", 0L)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed", e)
            emptyList()
        }
    }

    @Synchronized
    fun upsert(context: Context, kiosk: Kiosk) {
        val all = getAll(context).toMutableList()
        val idx = all.indexOfFirst { it.name == kiosk.name }
        if (idx >= 0) all[idx] = kiosk else all.add(kiosk)
        save(context, all)
        Log.i(TAG, "upsert: ${kiosk.name} @ ${kiosk.host}:${kiosk.port}")
    }

    @Synchronized
    fun remove(context: Context, name: String) {
        val all = getAll(context).filterNot { it.name == name }
        save(context, all)
    }

    private fun save(context: Context, list: List<Kiosk>) {
        val arr = JSONArray()
        for (k in list) {
            arr.put(JSONObject().apply {
                put("name", k.name)
                put("host", k.host)
                put("port", k.port)
                put("lastSeen", k.lastSeenMs)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_KIOSKS, arr.toString()).apply()
    }
}
