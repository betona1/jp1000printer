package com.betona.printdriver

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin settings & test UI — Jetpack Compose Material3.
 * Password protected (1234). Also serves as settingsActivity for PrintService.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PrintDriverMain"
        private const val ADMIN_PASSWORD = "1234"
    }

    private val printer = DevicePrinter

    // Compose observable state
    private var authenticated by mutableStateOf(false)
    private var logText by mutableStateOf("")
    private var deviceStatus by mutableStateOf("확인 중...")
    private var schoolUrl by mutableStateOf("")
    private var autoStartEnabled by mutableStateOf(false)
    private var testText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        schoolUrl = AppPrefs.getSchoolUrl(this)
        autoStartEnabled = AppPrefs.getAutoStart(this)

        setContent {
            AdminTheme {
                if (!authenticated) {
                    PasswordDialog()
                } else {
                    AdminScreen()
                }
            }
        }

        checkDeviceStatus()
    }

    // ── Theme ────────────────────────────────────────────────────────────

    @Composable
    private fun AdminTheme(content: @Composable () -> Unit) {
        val colorScheme = lightColorScheme(
            primary = Color(0xFF1565C0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBBDEFB),
            onPrimaryContainer = Color(0xFF0D47A1),
            secondary = Color(0xFF00897B),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFB2DFDB),
            error = Color(0xFFD32F2F),
            onError = Color.White,
            surface = Color(0xFFF5F5F5),
            onSurface = Color(0xFF212121),
            surfaceVariant = Color(0xFFE8EAF6),
            onSurfaceVariant = Color(0xFF424242)
        )
        MaterialTheme(colorScheme = colorScheme, content = content)
    }

    // ── Password Dialog ──────────────────────────────────────────────────

    @Composable
    private fun PasswordDialog() {
        var password by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text("관리자 비밀번호", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; isError = false },
                    label = { Text("비밀번호") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {{ Text("비밀번호가 틀렸습니다", color = MaterialTheme.colorScheme.error) }} else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (password == ADMIN_PASSWORD) {
                        authenticated = true
                    } else {
                        isError = true
                    }
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { finish() }) { Text("취소") }
            }
        )
    }

    // ── Admin Screen ─────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AdminScreen() {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("관리자 설정", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        TextButton(onClick = { finish() }) {
                            Text("←", color = Color.White, fontSize = 22.sp)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SchoolUrlCard()
                AppSettingsCard()
                DeviceStatusCard()
                DirectPrintTestCard()
                TextPrintCard()
                OtherCard()
                LogCard()
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Cards ────────────────────────────────────────────────────────────

    @Composable
    private fun SchoolUrlCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("학교주소 설정", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = schoolUrl,
                    onValueChange = { schoolUrl = it },
                    label = { Text("학교 홈페이지 URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (schoolUrl.isNotBlank()) {
                            AppPrefs.setSchoolUrl(this@MainActivity, schoolUrl.trim())
                            Toast.makeText(this@MainActivity, "학교주소가 저장되었습니다", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("저장") }
            }
        }
    }

    @Composable
    private fun AppSettingsCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("앱 설정", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("인쇄 서비스 설정 열기") }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("부팅 후 자동실행")
                    Switch(
                        checked = autoStartEnabled,
                        onCheckedChange = {
                            autoStartEnabled = it
                            AppPrefs.setAutoStart(this@MainActivity, it)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun DeviceStatusCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("장치 상태", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = deviceStatus,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }

    @Composable
    private fun DirectPrintTestCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("직접 인쇄 테스트", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                OutlinedButton(onClick = { testConnection() }, modifier = Modifier.fillMaxWidth()) {
                    Text("연결 테스트")
                }
                OutlinedButton(onClick = { testLabelPrint() }, modifier = Modifier.fillMaxWidth()) {
                    Text("청구기호 라벨 인쇄")
                }
                OutlinedButton(onClick = { testQrPrint() }, modifier = Modifier.fillMaxWidth()) {
                    Text("QR코드 인쇄")
                }
                OutlinedButton(onClick = { testImagePrint() }, modifier = Modifier.fillMaxWidth()) {
                    Text("이미지 인쇄")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { testCutType(full = true) }, modifier = Modifier.weight(1f)) {
                        Text("전체 절단")
                    }
                    OutlinedButton(onClick = { testCutType(full = false) }, modifier = Modifier.weight(1f)) {
                        Text("부분 절단")
                    }
                }
            }
        }
    }

    @Composable
    private fun TextPrintCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("텍스트 인쇄", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    label = { Text("인쇄할 텍스트") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    maxLines = 5
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { testTextPrint() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) { Text("텍스트 인쇄") }
            }
        }
    }

    @Composable
    private fun OtherCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("기타", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = { testSystemPrint() }, modifier = Modifier.fillMaxWidth()) {
                    Text("시스템 인쇄 테스트 (PrintSpooler)")
                }
                FilledTonalButton(
                    onClick = { startActivity(Intent(this@MainActivity, LadderGameActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("사다리 게임") }
            }
        }
    }

    @Composable
    private fun LogCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("로그", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = logText.ifEmpty { "(로그 없음)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ── Device Status ────────────────────────────────────────────────────

    private fun checkDeviceStatus() {
        val devFile = File(DevicePrinter.DEVICE_PATH)
        deviceStatus = buildString {
            appendLine("장치: ${DevicePrinter.DEVICE_PATH}")
            appendLine("존재: ${if (devFile.exists()) "YES" else "NO"}")
            if (devFile.exists()) {
                appendLine("읽기: ${if (devFile.canRead()) "YES" else "NO"}")
                append("쓰기: ${if (devFile.canWrite()) "YES" else "NO"}")
            }
        }
        log("장치 상태 확인 완료")
    }

    // ── Test: Connection ─────────────────────────────────────────────────

    private fun testConnection() {
        log("프린터 연결 테스트...")
        Thread {
            val success = printer.open()
            if (success) printer.initPrinter()
            runOnUiThread {
                log(if (success) "연결 성공: ${DevicePrinter.DEVICE_PATH}" else "연결 실패")
            }
        }.start()
    }

    // ── Test: Label Print ────────────────────────────────────────────────

    private fun testLabelPrint() {
        log("청구기호 라벨 인쇄 테스트...")
        Thread {
            if (!printer.open()) { runOnUiThread { log("프린터를 열 수 없습니다") }; return@Thread }
            try {
                printer.initPrinter()
                val bitmap = createCallNumberLabel(
                    title = "코틀린 인 액션", author = "드미트리 제메로프",
                    publisher = "에이콘출판사", callNumber = "005.133\nK87", regNumber = "2024-001234"
                )
                val scaled = BitmapConverter.scaleToWidth(bitmap, DevicePrinter.PRINT_WIDTH_PX)
                val monoData = BitmapConverter.toMonochrome(scaled)
                if (scaled !== bitmap) scaled.recycle()
                printer.printBitmapAndCut(monoData)
                bitmap.recycle()
                runOnUiThread { log("라벨 인쇄 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "Label print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            }
        }.start()
    }

    private fun createCallNumberLabel(
        title: String, author: String, publisher: String,
        callNumber: String, regNumber: String
    ): Bitmap {
        val width = DevicePrinter.PRINT_WIDTH_PX
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AColor.WHITE)

        val font = try { Typeface.createFromAsset(assets, "nanum_gothic.ttf") } catch (_: Exception) { Typeface.DEFAULT }

        val paintNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK; textSize = 26f; typeface = font }
        val paintBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK; textSize = 26f; typeface = font; isFakeBoldText = true }
        val paintCallNum = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK; textSize = 48f; typeface = font; isFakeBoldText = true }
        val paintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.BLACK; textSize = 20f; typeface = font }
        val linePaint = Paint().apply { color = AColor.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }

        val margin = 20f
        var y: Float
        val lineSpacing = 38f

        canvas.drawRect(margin, 10f, width - margin, height - 10f, linePaint)
        canvas.drawLine(margin, 60f, width - margin, 60f, linePaint)

        paintBold.textSize = 22f
        canvas.drawText("도서관 청구기호 라벨", margin + 10f, 45f, paintBold)
        y = 90f

        canvas.drawText("서  명: $title", margin + 10f, y, paintNormal); y += lineSpacing
        canvas.drawText("저  자: $author", margin + 10f, y, paintNormal); y += lineSpacing
        canvas.drawText("출판사: $publisher", margin + 10f, y, paintNormal); y += lineSpacing

        y += 5f
        canvas.drawLine(margin + 10f, y, width - margin - 10f, y, linePaint)
        y += 20f

        paintCallNum.textAlign = Paint.Align.CENTER
        for (line in callNumber.split("\n")) {
            canvas.drawText(line, width / 2f, y + 40f, paintCallNum)
            y += 55f
        }

        y += 10f
        canvas.drawLine(margin + 10f, y, width - margin - 10f, y, linePaint)
        y += 25f
        canvas.drawText("등록번호: $regNumber", margin + 10f, y, paintSmall)

        return bitmap
    }

    // ── Test: Text Print ─────────────────────────────────────────────────

    private fun testTextPrint() {
        if (testText.isBlank()) { log("텍스트를 입력하세요"); return }
        log("텍스트 인쇄 중...")
        Thread {
            if (!printer.open()) { runOnUiThread { log("프린터를 열 수 없습니다") }; return@Thread }
            try {
                printer.initPrinter()
                val bitmap = BitmapConverter.textToBitmap(this, testText)
                if (bitmap != null) {
                    val monoData = BitmapConverter.toMonochrome(bitmap)
                    printer.printBitmapAndCut(monoData)
                    bitmap.recycle()
                }
                runOnUiThread { log("텍스트 인쇄 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "Text print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            }
        }.start()
    }

    // ── Test: QR Code Print ──────────────────────────────────────────────

    private fun testQrPrint() {
        log("QR코드 인쇄 중...")
        Thread {
            if (!printer.open()) { runOnUiThread { log("프린터를 열 수 없습니다") }; return@Thread }
            try {
                printer.initPrinter()
                val bitmap = BitmapConverter.textToBitmap(this@MainActivity, "QR 테스트:\nhttps://dokseoro.com", 32f)
                if (bitmap != null) {
                    val monoData = BitmapConverter.toMonochrome(bitmap)
                    printer.printBitmapAndCut(monoData)
                    bitmap.recycle()
                }
                runOnUiThread { log("QR코드 인쇄 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "QR print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            }
        }.start()
    }

    // ── Test: Image Print ────────────────────────────────────────────────

    private fun testImagePrint() {
        log("이미지 인쇄 중...")
        Thread {
            if (!printer.open()) { runOnUiThread { log("프린터를 열 수 없습니다") }; return@Thread }
            try {
                printer.initPrinter()
                val bitmap = try {
                    val stream = assets.open("test_image.png")
                    BitmapFactory.decodeStream(stream).also { stream.close() }
                } catch (_: Exception) { createTestPattern() }

                val scaled = BitmapConverter.scaleToWidth(bitmap, DevicePrinter.PRINT_WIDTH_PX)
                val monoData = BitmapConverter.toMonochrome(scaled)
                if (scaled !== bitmap) scaled.recycle()
                val trimmed = BitmapConverter.trimTrailingWhiteRows(monoData)
                printer.printBitmapAndCut(trimmed)
                bitmap.recycle()
                runOnUiThread { log("이미지 인쇄 완료 (${trimmed.size} bytes)") }
            } catch (e: Exception) {
                Log.e(TAG, "Image print error", e)
                runOnUiThread { log("인쇄 오류: ${e.message}") }
            }
        }.start()
    }

    private fun createTestPattern(): Bitmap {
        val w = DevicePrinter.PRINT_WIDTH_PX
        val h = 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AColor.WHITE)
        val paint = Paint().apply { color = AColor.BLACK; strokeWidth = 2f }
        for (i in 0 until w step 40) canvas.drawLine(i.toFloat(), 0f, i.toFloat(), h.toFloat(), paint)
        for (i in 0 until h step 40) canvas.drawLine(0f, i.toFloat(), w.toFloat(), i.toFloat(), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
        canvas.drawRect(2f, 2f, w - 2f, h - 2f, paint)
        paint.style = Paint.Style.FILL; paint.textSize = 36f
        canvas.drawText("TEST PATTERN 576px", 20f, 60f, paint)
        canvas.drawText("테스트 패턴 인쇄", 20f, 110f, paint)
        canvas.drawText("${w}x${h} pixels", 20f, 160f, paint)
        return bmp
    }

    // ── Test: Full / Partial Cut ─────────────────────────────────────────

    private fun testCutType(full: Boolean) {
        val typeName = if (full) "전체 절단 (GS V 0)" else "부분 절단 (GS V 66)"
        log("$typeName 테스트")
        Thread {
            if (!printer.open()) { runOnUiThread { log("프린터를 열 수 없습니다") }; return@Thread }
            try {
                printer.initPrinter()
                val label = if (full) "[ 전체 절단 테스트 ]" else "[ 부분 절단 테스트 ]"
                val bitmap = BitmapConverter.textToBitmap(this@MainActivity, "$label\n$typeName", 40f)
                if (bitmap != null) {
                    val monoData = BitmapConverter.toMonochrome(bitmap)
                    printer.printBitmapAndCut(monoData, fullCut = full)
                    bitmap.recycle()
                }
                runOnUiThread { log("$typeName 완료") }
            } catch (e: Exception) {
                Log.e(TAG, "Cut type test error", e)
                runOnUiThread { log("오류: ${e.message}") }
            }
        }.start()
    }

    // ── Test: System Print (via PrintManager) ────────────────────────────

    private fun testSystemPrint() {
        log("시스템 인쇄 테스트 (PrintSpooler 경유)...")
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val jobName = "LibroPrintDriver 테스트"

        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?
            ) {
                if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
                val info = PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1).build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>, destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal, callback: WriteResultCallback
            ) {
                try {
                    val pdfDoc = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(576, 400, 0).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas = page.canvas
                    val paint = Paint().apply { color = AColor.BLACK; textSize = 24f }
                    canvas.drawText("시스템 인쇄 테스트", 20f, 50f, paint)
                    canvas.drawText("PrintSpooler → PrintService", 20f, 90f, paint)
                    canvas.drawText("LibroPrintDriver", 20f, 130f, paint)
                    paint.textSize = 16f
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                    canvas.drawText(time, 20f, 170f, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                    canvas.drawRect(10f, 10f, 566f, 390f, paint)
                    pdfDoc.finishPage(page)
                    pdfDoc.writeTo(FileOutputStream(destination.fileDescriptor))
                    pdfDoc.close()
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    Log.e(TAG, "SystemPrint: onWrite error", e)
                    callback.onWriteFailed(e.message)
                }
            }
        }, null)
    }

    // ── Logging ──────────────────────────────────────────────────────────

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val line = "[$time] $message"
        Log.d(TAG, message)
        logText += "$line\n"
    }
}
