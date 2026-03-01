package com.betona.printdriver.web

import android.content.Context
import android.util.Log
import com.betona.printdriver.AppPrefs
import com.betona.printdriver.BitmapConverter
import com.betona.printdriver.DevicePrinter
import com.betona.printdriver.EscPosCommands
import org.json.JSONObject

/**
 * Printer status, test print, feed, and cut API handlers.
 */
object PrinterApi {

    private const val TAG = "PrinterApi"

    fun getStatus(): JSONObject {
        val data = JSONObject()
        data.put("isOpen", DevicePrinter.isOpen)
        data.put("paper", DevicePrinter.checkPaper())
        data.put("cover", DevicePrinter.checkCover())
        data.put("overheat", DevicePrinter.checkOverheat())
        return success(data)
    }

    fun testPrint(context: Context): JSONObject {
        return printerAction("testPrint") {
            val text = "=== 테스트 인쇄 ===\n" +
                    "LibroPrintDriver\n" +
                    "Web Management\n" +
                    "프린터 정상 작동 중\n" +
                    "==================\n" +
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
            val bitmap = BitmapConverter.textToBitmap(context, text)
                ?: return@printerAction error("Failed to create test bitmap")
            val monoData = BitmapConverter.toMonochrome(bitmap)
            val trimmed = BitmapConverter.trimTrailingWhiteRows(monoData)
            DevicePrinter.printBitmapAndCut(trimmed, fullCut = AppPrefs.isFullCut(context))
            success(JSONObject().put("message", "테스트 인쇄 완료"))
        }
    }

    fun feed(): JSONObject {
        return printerAction("feed") {
            DevicePrinter.write(EscPosCommands.feedLines(4))
            success(JSONObject().put("message", "용지 이송 완료"))
        }
    }

    fun cut(context: Context): JSONObject {
        return printerAction("cut") {
            DevicePrinter.feedAndCut(fullCut = AppPrefs.isFullCut(context))
            success(JSONObject().put("message", "용지 절단 완료"))
        }
    }

    private fun printerAction(name: String, action: () -> JSONObject): JSONObject {
        return try {
            if (!DevicePrinter.isOpen) {
                if (!DevicePrinter.open()) {
                    return error("프린터를 열 수 없습니다")
                }
                DevicePrinter.initPrinter()
            }
            action()
        } catch (e: Exception) {
            Log.e(TAG, "$name failed", e)
            error("$name 실패: ${e.message}")
        }
    }

    private fun success(data: JSONObject): JSONObject {
        return JSONObject().put("success", true).put("data", data)
    }

    private fun error(message: String): JSONObject {
        return JSONObject().put("success", false).put("error", message)
    }
}
