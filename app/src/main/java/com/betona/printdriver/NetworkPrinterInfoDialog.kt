package com.betona.printdriver

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkPrinterInfoDialog {

    private const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=kr.co.bitic.libroprinter"

    fun show(context: Context) {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
        }
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        addInstallSection(context, root)
        addKioskInfoSection(context, root)
        addRenameGuide(context, root)

        AlertDialog.Builder(context)
            .setTitle("네트워크 프린터 정보")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun addInstallSection(context: Context, parent: LinearLayout) {
        parent.addView(sectionHeader(context, "①  폰에서 인쇄하기"))
        parent.addView(bodyText(
            context,
            "폰의 카메라로 아래 QR을 스캔해 'LibroPrinter 플러그인'을 설치하세요."
        ))

        val qrSize = dp(context, 180)
        parent.addView(ImageView(context).apply {
            try {
                setImageBitmap(QrGenerator.encode(PLAY_STORE_URL, qrSize))
            } catch (_: Exception) {}
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(context, 8)
            }
        })
        parent.addView(TextView(context).apply {
            text = "Play Store"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(context, 4), 0, dp(context, 16))
        })
    }

    private fun addKioskInfoSection(context: Context, parent: LinearLayout) {
        parent.addView(sectionHeader(context, "②  이 키오스크 정보"))

        val alias = AppPrefs.getPrinterAlias(context).ifEmpty { Build.MODEL ?: "LibroPrinter" }
        val printerName = "LibroPrinter-$alias"
        val ip = getLocalIp()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
        }
        box.addView(infoRow(context, "프린터 이름", printerName))
        box.addView(infoRow(context, "IP 주소", "$ip:6631"))

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 16) }
        parent.addView(box, lp)
    }

    private fun addRenameGuide(context: Context, parent: LinearLayout) {
        parent.addView(sectionHeader(context, "③  키오스크 2대 이상일 때 이름 바꾸는 법"))
        parent.addView(bodyText(
            context,
            "같은 매장에 키오스크가 여러 대 있을 때 구분되도록 키오스크에서 직접 이름을 바꿉니다.\n\n" +
                    "1. 화면 우측 상단의 메뉴(설정) 아이콘 → 관리자 로그인 (기본 비번 1234)\n" +
                    "2. '설정' 탭의 '프린터 별칭'에 새 이름 입력 후 '저장'\n" +
                    "    예: \"1층카운터\", \"우리학교 도서관\"\n" +
                    "3. 키오스크 재시작 → 폰의 인쇄 메뉴에 'LibroPrinter-별칭'으로 표시됩니다."
        ))
    }

    private fun infoRow(context: Context, label: String, value: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }
        row.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(dp(context, 80), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun sectionHeader(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, dp(context, 12), 0, dp(context, 6))
        }

    private fun bodyText(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.DKGRAY)
            setLineSpacing(dp(context, 4).toFloat(), 1f)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

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
        return "—"
    }
}
