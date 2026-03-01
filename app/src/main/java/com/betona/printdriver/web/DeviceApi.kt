package com.betona.printdriver.web

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.betona.printdriver.AppPrefs
import com.betona.printdriver.DaySchedule
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device info and settings API handlers.
 */
object DeviceApi {

    private const val TAG = "DeviceApi"

    fun getDeviceInfo(context: Context): JSONObject {
        val data = JSONObject()
        data.put("model", Build.MODEL)
        data.put("android", Build.VERSION.RELEASE)
        data.put("sdkInt", Build.VERSION.SDK_INT)
        data.put("ip", getWifiIp(context))
        data.put("appVersion", getAppVersion(context))
        data.put("uptime", formatUptime())
        return JSONObject().put("success", true).put("data", data)
    }

    fun getSettings(context: Context): JSONObject {
        val data = JSONObject()
        data.put("schoolUrl", AppPrefs.getSchoolUrl(context))
        data.put("autoStart", AppPrefs.getAutoStart(context))
        data.put("showPowerBtn", AppPrefs.getShowPowerButton(context))
        data.put("showSchedule", AppPrefs.getShowSchedule(context))
        data.put("cutMode", AppPrefs.getCutMode(context))

        val scheduleArr = JSONArray()
        for (i in 0..6) {
            val s = AppPrefs.getDaySchedule(context, i)
            scheduleArr.put(JSONObject()
                .put("enabled", s.enabled)
                .put("startHour", s.startHour)
                .put("startMin", s.startMin)
                .put("endHour", s.endHour)
                .put("endMin", s.endMin))
        }
        data.put("schedule", scheduleArr)
        return JSONObject().put("success", true).put("data", data)
    }

    fun updateSettings(context: Context, body: JSONObject): JSONObject {
        return try {
            if (body.has("schoolUrl")) {
                AppPrefs.setSchoolUrl(context, body.getString("schoolUrl"))
            }
            if (body.has("autoStart")) {
                AppPrefs.setAutoStart(context, body.getBoolean("autoStart"))
            }
            if (body.has("showPowerBtn")) {
                AppPrefs.setShowPowerButton(context, body.getBoolean("showPowerBtn"))
            }
            if (body.has("showSchedule")) {
                AppPrefs.setShowSchedule(context, body.getBoolean("showSchedule"))
            }
            if (body.has("cutMode")) {
                AppPrefs.setCutMode(context, body.getString("cutMode"))
            }
            if (body.has("schedule")) {
                val arr = body.getJSONArray("schedule")
                for (i in 0 until minOf(arr.length(), 7)) {
                    val obj = arr.getJSONObject(i)
                    AppPrefs.setDaySchedule(context, i, DaySchedule(
                        enabled = obj.optBoolean("enabled", false),
                        startHour = obj.optInt("startHour", 9),
                        startMin = obj.optInt("startMin", 0),
                        endHour = obj.optInt("endHour", 18),
                        endMin = obj.optInt("endMin", 0)
                    ))
                }
            }
            JSONObject().put("success", true)
                .put("data", JSONObject().put("message", "설정 저장 완료"))
        } catch (e: Exception) {
            Log.e(TAG, "updateSettings failed", e)
            JSONObject().put("success", false).put("error", "설정 변경 실패: ${e.message}")
        }
    }

    private fun getWifiIp(context: Context): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (ni in interfaces) {
                    if (ni.isLoopback || !ni.isUp) continue
                    for (addr in ni.inetAddresses) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: continue
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
        } catch (_: Exception) {}
        return "N/A"
    }

    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun formatUptime(): String {
        val seconds = SystemClock.elapsedRealtime() / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "${hours}h ${minutes}m"
    }
}
