package com.betona.libroprintplugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.buildSpannedString

/**
 * Plugin home / setup screen.
 *
 * Read-only view — does NOT start its own NSD discovery to avoid racing with
 * the system PrintFramework's discovery session. Lists kiosks from
 * [KioskRegistry] (populated whenever the user successfully prints).
 *
 * Banners guide first-time setup: enable print service + disable battery
 * optimization (works around Samsung Freecess freezing the plugin process).
 */
class PluginSettingsActivity : Activity() {

    private lateinit var setupContainer: LinearLayout
    private lateinit var kioskListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "LibroPrinter 플러그인"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)

        // ── Header ──────────────────────────────────────────────────────
        TextView(this).apply {
            text = "LibroPrinter 플러그인"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }.also { root.addView(it) }
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" }
        TextView(this).apply {
            text = "키오스크 감열 프린터 (72mm) 인쇄 v$versionName"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 24)
        }.also { root.addView(it) }

        // ── Setup banners ───────────────────────────────────────────────
        setupContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setupContainer)

        // ── Discovered kiosks list ──────────────────────────────────────
        TextView(this).apply {
            text = "발견된 프린터"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }.also { root.addView(it) }
        TextView(this).apply {
            text = "한 번 인쇄에 성공한 키오스크가 여기 표시됩니다."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 8)
        }.also { root.addView(it) }
        kioskListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(kioskListContainer)

        // ── How-to ─────────────────────────────────────────────────────
        TextView(this).apply {
            text = "사용법"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        }.also { root.addView(it) }
        TextView(this).apply {
            text = "1. 폰을 LibroPrinter 키오스크와 같은 Wi-Fi에 연결하세요.\n" +
                    "2. 어떤 앱에서든 인쇄 메뉴를 여세요 (크롬·갤러리·문서 등).\n" +
                    "3. 프린터 목록에서 \"LibroPrinter-OOO (72mm)\"를 선택하세요.\n" +
                    "4. 인쇄 버튼을 누르면 키오스크에서 출력됩니다."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setLineSpacing(8f, 1f)
        }.also { root.addView(it) }

        TextView(this).apply {
            text = "© BITIC Ltd."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 32, 0, 0)
        }.also { root.addView(it) }

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshKioskList()
    }

    // ── Setup banners ───────────────────────────────────────────────────

    private fun refreshSetup() {
        setupContainer.removeAllViews()

        val printOK = isPrintServiceEnabled()
        val batteryOK = isBatteryOptimizationDisabled()

        if (!printOK) {
            addBanner(
                title = "① 인쇄 서비스 켜기",
                description = "안드로이드 시스템에서 이 플러그인을 인쇄 서비스로 활성화해야 사용할 수 있습니다.",
                buttonText = "인쇄 설정 열기",
                buttonAction = ::openPrintSettings
            )
        }

        if (!batteryOK) {
            addBanner(
                title = "② 배터리 최적화 해제",
                description = "갤럭시 등 일부 폰은 백그라운드 앱을 자동으로 잠재웁니다. 해제하지 않으면 인쇄 시 \"프린터 없음\"으로 보일 수 있습니다.",
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
                accent = Color.parseColor("#2E7D32"),
                bg = Color.parseColor("#E8F5E9")
            )
        }
    }

    private fun addBanner(
        title: String,
        description: String,
        buttonText: String?,
        buttonAction: (() -> Unit)?,
        accent: Int = Color.parseColor("#D84315"),
        bg: Int = Color.parseColor("#FFF8E1")
    ) {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(28, 22, 28, 22)
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

    // ── Kiosk picker (radio group) ──────────────────────────────────────

    private fun refreshKioskList() {
        kioskListContainer.removeAllViews()
        val kiosks = KioskRegistry.getAll(this)
        if (kiosks.isEmpty()) {
            kioskListContainer.addView(TextView(this).apply {
                text = "아직 발견된 프린터가 없습니다.\n같은 Wi-Fi의 키오스크에 한 번 인쇄해 보세요."
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 12, 0, 12)
            })
            return
        }

        // Description + radio group + save button. Selecting "전체 표시" leaves
        // discovery unfiltered; picking a specific kiosk filters out the others
        // so the system print picker shows only that one.
        kioskListContainer.addView(TextView(this).apply {
            text = "여러 키오스크가 있는 매장에서 한 대만 사용하려면 아래에서 선택하세요. " +
                    "Android 인쇄 메뉴에 그 한 대만 표시됩니다."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 12)
        })

        val saved = PluginPrefs.getPreferredPrinter(this)
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val allRadio = RadioButton(this).apply {
            text = "전체 표시 (모든 키오스크 노출)"
            textSize = 15f
            setPadding(0, 16, 0, 16)
            isChecked = saved.isEmpty()
        }
        group.addView(allRadio)

        val nameToRadio = HashMap<String, RadioButton>()
        for (k in kiosks) {
            val radio = RadioButton(this).apply {
                text = "${k.name}\n  ${k.host}:${k.port}"
                textSize = 14f
                setPadding(0, 16, 0, 16)
                isChecked = (k.name == saved)
            }
            group.addView(radio)
            nameToRadio[k.name] = radio
        }
        kioskListContainer.addView(group)

        kioskListContainer.addView(Button(this).apply {
            text = "저장"
            setOnClickListener {
                val pickedName = if (allRadio.isChecked) {
                    ""
                } else {
                    nameToRadio.entries.firstOrNull { it.value.isChecked }?.key ?: ""
                }
                PluginPrefs.setPreferredPrinter(this@PluginSettingsActivity, pickedName)
                val msg = if (pickedName.isEmpty()) "전체 표시" else "기본 프린터: $pickedName"
                Toast.makeText(this@PluginSettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            layoutParams = lp
        })
    }

    // ── State checks ────────────────────────────────────────────────────

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
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
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
}
