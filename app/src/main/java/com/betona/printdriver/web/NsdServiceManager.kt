package com.betona.printdriver.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * mDNS(NSD) 서비스 광고 매니저.
 * IPP / RAW / Web 3개 서비스를 등록하고,
 * 네트워크 변경(WiFi 끊김/재연결/IP 변경) 시 자동 재등록한다.
 */
class NsdServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "LibroNSD"
        const val KEY_IPP = "ipp"
        const val KEY_RAW = "raw"
        const val KEY_WEB = "web"
    }

    // 기기별 고유 이름/UUID (IppServer와 동일 알고리즘)
    val printerName: String
    val printerUuid: String

    private var nsdManager: NsdManager? = null
    private val listeners = mutableMapOf<String, NsdManager.RegistrationListener>()

    // 재등록용: 현재 활성 서비스 설정 저장
    private data class ServiceConfig(
        val key: String,
        val serviceType: String,
        val serviceName: String,
        val port: Int,
        val txtRecords: Map<String, String>
    )
    private val activeConfigs = mutableMapOf<String, ServiceConfig>()

    // 네트워크 변경 감지
    private var networkReceiver: BroadcastReceiver? = null
    private var lastKnownIp: String? = null

    init {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        printerUuid = UUID.nameUUIDFromBytes("LibroPrinter-$androidId".toByteArray()).toString()

        val model = Build.MODEL?.replace(" ", "") ?: "Unknown"
        val shortModel = if (model.length > 10) model.takeLast(10) else model
        printerName = "LibroPrinter-$shortModel"

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        Log.i(TAG, "초기화: name=$printerName, uuid=$printerUuid")
    }

    // ── 서비스 등록 ─────────────────────────────────────────────────────

    /** IPP 프린터 서비스 (_ipp._tcp) 등록 */
    fun registerIpp(port: Int) {
        val txt = mapOf(
            "txtvers" to "1",
            "qtotal" to "1",
            "pdl" to "application/pdf,image/png",
            "rp" to "ipp/print",
            "note" to "JY-P1000 감열 프린터",
            "product" to "(LibroPrintDriver)",
            "ty" to "JY-P1000 Thermal Printer",
            "URF" to "W8,SRGB24,CP1,RS300",
            "UUID" to printerUuid
        )
        registerService(KEY_IPP, "_ipp._tcp", printerName, port, txt)
    }

    /** RAW 프린터 서비스 (_pdl-datastream._tcp) 등록 */
    fun registerRaw(port: Int) {
        registerService(KEY_RAW, "_pdl-datastream._tcp", "$printerName-RAW", port, emptyMap())
    }

    /** 웹 관리 서비스 (_http._tcp) 등록 */
    fun registerWeb(port: Int) {
        registerService(KEY_WEB, "_http._tcp", "$printerName-Web", port, emptyMap())
    }

    /** 개별 서비스 해제 */
    fun unregister(key: String) {
        activeConfigs.remove(key)
        val listener = listeners.remove(key) ?: return
        try {
            nsdManager?.unregisterService(listener)
            Log.i(TAG, "서비스 해제: $key")
        } catch (e: Exception) {
            Log.e(TAG, "서비스 해제 실패: $key", e)
        }
    }

    /** 모든 서비스 해제 + 네트워크 모니터 중지 */
    fun unregisterAll() {
        val keys = activeConfigs.keys.toList()
        for (key in keys) {
            unregister(key)
        }
        stopNetworkMonitor()
        Log.i(TAG, "모든 서비스 해제 완료")
    }

    // ── 네트워크 변경 감지 ──────────────────────────────────────────────

    /** WiFi 연결/해제/IP 변경 감지 시작 */
    @Suppress("DEPRECATION")
    fun startNetworkMonitor() {
        if (networkReceiver != null) return
        lastKnownIp = getLocalIp()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val newIp = getLocalIp()
                val wifiConnected = isWifiConnected()

                if (!wifiConnected) {
                    // WiFi 끊김 → 모든 서비스 일시 해제 (config는 유지)
                    Log.i(TAG, "WiFi 끊김 감지, 서비스 일시 해제")
                    unregisterAllListeners()
                    lastKnownIp = null
                    return
                }

                if (newIp != lastKnownIp && newIp != "127.0.0.1") {
                    // IP 변경 또는 WiFi 재연결 → 재등록
                    Log.i(TAG, "IP 변경 감지: $lastKnownIp → $newIp, 서비스 재등록")
                    lastKnownIp = newIp
                    reregisterAll()
                }
            }
        }

        networkReceiver = receiver
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        context.registerReceiver(receiver, filter)
        Log.i(TAG, "네트워크 모니터 시작")
    }

    /** 네트워크 변경 감지 중지 */
    fun stopNetworkMonitor() {
        networkReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
        networkReceiver = null
    }

    // ── 내부 구현 ───────────────────────────────────────────────────────

    private fun registerService(
        key: String,
        serviceType: String,
        serviceName: String,
        port: Int,
        txtRecords: Map<String, String>
    ) {
        // 기존 등록이 있으면 먼저 해제
        if (listeners.containsKey(key)) {
            unregister(key)
        }

        // 설정 저장 (재등록용)
        activeConfigs[key] = ServiceConfig(key, serviceType, serviceName, port, txtRecords)

        try {
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = serviceType
                this.port = port
                for ((k, v) in txtRecords) {
                    setAttribute(k, v)
                }
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) {
                    Log.e(TAG, "등록 실패 [$key]: ${si.serviceName}, error=$err")
                }
                override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) {
                    Log.e(TAG, "해제 실패 [$key]: error=$err")
                }
                override fun onServiceRegistered(si: NsdServiceInfo) {
                    Log.i(TAG, "등록 성공 [$key]: ${si.serviceName} (${serviceInfo.serviceType}:$port)")
                }
                override fun onServiceUnregistered(si: NsdServiceInfo) {
                    Log.i(TAG, "해제 완료 [$key]: ${si.serviceName}")
                }
            }

            listeners[key] = listener
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "등록 오류 [$key]", e)
        }
    }

    /** 리스너만 해제 (config는 유지, WiFi 끊김 시 사용) */
    private fun unregisterAllListeners() {
        for ((key, listener) in listeners) {
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.e(TAG, "리스너 해제 실패: $key", e)
            }
        }
        listeners.clear()
    }

    /** 저장된 config로 전체 재등록 (IP 변경 시 사용) */
    private fun reregisterAll() {
        unregisterAllListeners()
        val configs = activeConfigs.values.toList()
        for (cfg in configs) {
            registerService(cfg.key, cfg.serviceType, cfg.serviceName, cfg.port, cfg.txtRecords)
        }
    }

    @Suppress("DEPRECATION")
    private fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
        } catch (_: Exception) { false }
    }

    private fun getLocalIp(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
