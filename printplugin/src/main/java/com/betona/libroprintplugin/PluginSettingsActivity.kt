package com.betona.libroprintplugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Plugin home screen — guides the user through the one-time setup and lets them
 * pick a default kiosk so only that one appears in the system print picker.
 *
 * Setup steps surfaced as banners (only visible when not yet satisfied):
 *   1. Enable the print service in Android system settings
 *   2. Whitelist the plugin from battery optimization (Samsung Freecess freezes
 *      it otherwise — printing fails silently)
 */
class PluginSettingsActivity : Activity() {

    companion object {
        private const val TAG = "PluginSettings"
        private const val SERVICE_TYPE = "_ipp._tcp."
        private const val PRINTER_NAME_PREFIX = "LibroPrinter"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val discovered = LinkedHashMap<String, RadioButton>() // serviceName -> radio
    private lateinit var radioGroup: RadioGroup
    private lateinit var statusText: TextView
    private lateinit var setupContainer: LinearLayout
    private lateinit var allRadio: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "LibroPrinter 플러그인"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }

        // ── Header ──────────────────────────────────────────────────────
        TextView(this).apply {
            text = "LibroPrinter 플러그인"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }.also { root.addView(it) }
        TextView(this).apply {
            text = "키오스크 감열 프린터 (72mm) 인쇄 플러그인"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 24)
        }.also { root.addView(it) }

        // ── Setup banners (dynamically populated by refreshSetup()) ─────
        setupContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(setupContainer)

        // ── Default printer picker ──────────────────────────────────────
        TextView(this).apply {
            text = "기본 프린터 선택"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }.also { root.addView(it) }
        TextView(this).apply {
            text = "지정한 프린터만 폰의 인쇄 메뉴에 표시됩니다.\n장소를 옮기면 다시 선택하세요."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }.also { root.addView(it) }

        statusText = TextView(this).apply {
            text = "근처 프린터 검색 중..."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 8)
        }.also { root.addView(it) }

        radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(radioGroup)
        }
        root.addView(scroll)

        allRadio = RadioButton(this).apply {
            text = "모두 표시 (기본값)"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        radioGroup.addView(allRadio)

        val savedPreferred = PluginPrefs.getPreferredPrinter(this)
        if (savedPreferred.isEmpty()) allRadio.isChecked = true

        Button(this).apply {
            text = "저장"
            setOnClickListener {
                val checkedId = radioGroup.checkedRadioButtonId
                val checked = radioGroup.findViewById<RadioButton>(checkedId)
                val pickedName = if (checked == null || checked === allRadio) {
                    ""
                } else {
                    discovered.entries.firstOrNull { it.value === checked }?.key ?: ""
                }
                PluginPrefs.setPreferredPrinter(this@PluginSettingsActivity, pickedName)
                val msg = if (pickedName.isEmpty()) "모든 프린터 표시" else "기본 프린터: $pickedName"
                Toast.makeText(this@PluginSettingsActivity, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }.also { root.addView(it) }

        setContentView(root)

        startDiscovery(savedPreferred)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    // ── Setup banners ───────────────────────────────────────────────────

    private fun refreshSetup() {
        setupContainer.removeAllViews()

        val printOK = isPrintServiceEnabled()
        val batteryOK = isBatteryOptimizationDisabled()

        if (!printOK) {
            addBanner(
                title = "① 인쇄 서비스 켜기",
                description = "이 플러그인이 시스템 인쇄에 사용되도록 활성화하세요.",
                buttonText = "인쇄 설정 열기",
                buttonAction = ::openPrintSettings
            )
        }

        if (!batteryOK) {
            addBanner(
                title = "② 배터리 최적화 해제",
                description = "이걸 안 하면 갤럭시 등 일부 폰이 플러그인을 자동으로 잠재워서 \"" +
                        "프린터 없음\"으로 보입니다.",
                buttonText = "배터리 최적화 해제",
                buttonAction = ::requestIgnoreBatteryOpt
            )
        }

        if (printOK && batteryOK) {
            addBanner(
                title = "✓ 설정 완료",
                description = "이제 어떤 앱에서든 인쇄할 수 있습니다.",
                buttonText = null,
                buttonAction = null,
                accent = Color.parseColor("#2E7D32")
            )
        }
    }

    private fun addBanner(
        title: String,
        description: String,
        buttonText: String?,
        buttonAction: (() -> Unit)?,
        accent: Int = Color.parseColor("#D84315")
    ) {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E1"))
            setPadding(24, 20, 24, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = lp
        }
        banner.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
        })
        banner.addView(TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 6, 0, if (buttonText != null) 12 else 0)
        })
        if (buttonText != null && buttonAction != null) {
            banner.addView(Button(this).apply {
                text = buttonText
                setOnClickListener { buttonAction() }
            })
        }
        setupContainer.addView(banner)
    }

    private fun isPrintServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_print_services") ?: ""
        return enabled.contains(packageName)
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openPrintSettings() {
        try {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        } catch (e: Exception) {
            // Fallback: open the app's own info page
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOpt() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Some Samsung devices don't honor REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            // fall back to the general battery optimization list.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    // ── NSD Discovery ───────────────────────────────────────────────────

    private fun startDiscovery(savedPreferred: String) {
        val mgr = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            statusText.text = "이 기기는 NSD를 지원하지 않습니다."
            return
        }
        nsdManager = mgr

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "discovery started") }
            override fun onDiscoveryStopped(serviceType: String) { Log.d(TAG, "discovery stopped") }

            @Suppress("DEPRECATION")
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.contains(PRINTER_NAME_PREFIX, ignoreCase = true)) {
                    addOrUpdate(info.serviceName, savedPreferred)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                mainHandler.post {
                    discovered.remove(info.serviceName)?.let { radioGroup.removeView(it) }
                    refreshStatus()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "start discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stop discovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun addOrUpdate(serviceName: String, savedPreferred: String) {
        mainHandler.post {
            if (discovered.containsKey(serviceName)) return@post
            val radio = RadioButton(this).apply {
                text = serviceName
                textSize = 16f
                setPadding(0, 24, 0, 24)
            }
            radioGroup.addView(radio)
            discovered[serviceName] = radio
            if (serviceName == savedPreferred) radio.isChecked = true
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        statusText.text = when (discovered.size) {
            0 -> "근처 프린터 검색 중..."
            1 -> "프린터 1개 발견"
            else -> "프린터 ${discovered.size}개 발견 — 사용할 것을 선택하세요"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery failed", e)
        }
        discoveryListener = null
    }
}
