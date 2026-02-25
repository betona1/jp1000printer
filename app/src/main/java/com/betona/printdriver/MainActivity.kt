package com.betona.printdriver

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.betona.printdriver.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings and test UI for LibroPrintDriver.
 * Also serves as the settingsActivity for the PrintService.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PrintDriverMain"
    }

    private lateinit var binding: ActivityMainBinding
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        checkDeviceStatus()
    }

    private fun setupButtons() {
        binding.btnOpenPrintSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnTestLabel.setOnClickListener { testLabelPrint() }
        binding.btnTestText.setOnClickListener { testTextPrint() }
        binding.btnTestQrCode.setOnClickListener { testQrPrint() }
    }

    private fun checkDeviceStatus() {
        val devFile = File(DevicePrinter.DEVICE_PATH)
        val status = buildString {
            appendLine("장치: ${DevicePrinter.DEVICE_PATH}")
            appendLine("존재: ${if (devFile.exists()) "YES" else "NO"}")
            if (devFile.exists()) {
                appendLine("읽기: ${if (devFile.canRead()) "YES" else "NO"}")
                append("쓰기: ${if (devFile.canWrite()) "YES" else "NO"}")
            }
        }
        binding.tvDeviceStatus.text = status
        log("장치 상태 확인 완료")
    }

    // ── Test: Connection ─────────────────────────────────────────────────

    private fun testConnection() {
        log("프린터 연결 테스트...")
        Thread {
            val printer = DevicePrinter()
            val success = printer.open()
            if (success) {
                printer.initPrinter()
                printer.close()
            }
            runOnUiThread {
                if (success) {
                    log("연결 성공: ${DevicePrinter.DEVICE_PATH}")
                } else {
                    log("연결 실패: ${DevicePrinter.DEVICE_PATH} 를 열 수 없습니다")
                }
            }
        }.start()
    }

    // ── Test: Label Print ────────────────────────────────────────────────

    private fun testLabelPrint() {
        log("청구기호 라벨 인쇄 테스트...")
        Thread {
            val printer = DevicePrinter()
            if (!printer.open()) {
                runOnUiThread { log("프린터를 열 수 없습니다") }
                return@Thread
            }
            try {
                printer.initPrinter()

                val bitmap = createCallNumberLabel(
                    title = "코틀린 인 액션",
                    author = "드미트리 제메로프",
                    publisher = "에이콘출판사",
                    callNumber = "005.133\nK87",
                    regNumber = "2024-001234"
                )
                val scaled = BitmapConverter.scaleToWidth(bitmap, DevicePrinter.PRINT_WIDTH_PX)
                val monoData = BitmapConverter.toMonochrome(scaled)
                if (scaled !== bitmap) scaled.recycle()

                printer.printBitmap(monoData)
                bitmap.recycle()
                printer.feedAndCut()

                runOnUiThread { log("라벨 인쇄 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "Label print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            } finally {
                printer.close()
            }
        }.start()
    }

    private fun createCallNumberLabel(
        title: String,
        author: String,
        publisher: String,
        callNumber: String,
        regNumber: String
    ): Bitmap {
        val width = DevicePrinter.PRINT_WIDTH_PX
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val font = try {
            Typeface.createFromAsset(assets, "nanum_gothic.ttf")
        } catch (_: Exception) {
            Typeface.DEFAULT
        }

        val paintNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = font
        }

        val paintBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = font
            isFakeBoldText = true
        }

        val paintCallNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
            typeface = font
            isFakeBoldText = true
        }

        val paintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 20f
            typeface = font
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        val margin = 20f
        var y: Float
        val lineSpacing = 38f

        // Top border
        canvas.drawRect(margin, 10f, width - margin, height - 10f, linePaint)

        // Header line
        canvas.drawLine(margin, 60f, width - margin, 60f, linePaint)

        // Title area
        paintBold.textSize = 22f
        canvas.drawText("도서관 청구기호 라벨", margin + 10f, 45f, paintBold)

        y = 90f

        // Book info
        canvas.drawText("서  명: $title", margin + 10f, y, paintNormal); y += lineSpacing
        canvas.drawText("저  자: $author", margin + 10f, y, paintNormal); y += lineSpacing
        canvas.drawText("출판사: $publisher", margin + 10f, y, paintNormal); y += lineSpacing

        // Separator
        y += 5f
        canvas.drawLine(margin + 10f, y, width - margin - 10f, y, linePaint)
        y += 20f

        // Call number (large, centered)
        paintCallNum.textAlign = Paint.Align.CENTER
        for (line in callNumber.split("\n")) {
            canvas.drawText(line, width / 2f, y + 40f, paintCallNum)
            y += 55f
        }

        // Separator
        y += 10f
        canvas.drawLine(margin + 10f, y, width - margin - 10f, y, linePaint)
        y += 25f

        // Registration number
        canvas.drawText("등록번호: $regNumber", margin + 10f, y, paintSmall)

        return bitmap
    }

    // ── Test: Text Print ─────────────────────────────────────────────────

    private fun testTextPrint() {
        val text = binding.etTestText.text.toString()
        if (text.isBlank()) {
            log("텍스트를 입력하세요")
            return
        }
        log("텍스트 인쇄 중...")
        Thread {
            val printer = DevicePrinter()
            if (!printer.open()) {
                runOnUiThread { log("프린터를 열 수 없습니다") }
                return@Thread
            }
            try {
                printer.initPrinter()
                val bitmap = BitmapConverter.textToBitmap(this, text)
                if (bitmap != null) {
                    val monoData = BitmapConverter.toMonochrome(bitmap)
                    printer.printBitmap(monoData)
                    bitmap.recycle()
                }
                printer.feedAndCut()
                runOnUiThread { log("텍스트 인쇄 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "Text print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            } finally {
                printer.close()
            }
        }.start()
    }

    // ── Test: QR Code Print ──────────────────────────────────────────────

    private fun testQrPrint() {
        log("QR코드 인쇄 중...")
        Thread {
            val printer = DevicePrinter()
            if (!printer.open()) {
                runOnUiThread { log("프린터를 열 수 없습니다") }
                return@Thread
            }
            try {
                printer.initPrinter()
                printer.write(EscPosCommands.justifyCenter())
                printer.write(EscPosCommands.printQrCode(8, "https://dokseoro.com"))
                printer.write(EscPosCommands.lineFeed())
                printer.write(EscPosCommands.justifyLeft())
                printer.feedAndCut()
                runOnUiThread { log("QR코드 인쇄 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "QR print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            } finally {
                printer.close()
            }
        }.start()
    }

    // ── Logging ──────────────────────────────────────────────────────────

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val line = "[$time] $message"
        Log.d(TAG, message)
        logBuilder.appendLine(line)
        binding.tvLog.text = logBuilder.toString()
    }
}
