package com.betona.printdriver

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.net.Uri
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.betona.printdriver.web.WebServerService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.TimePickerDialog
import androidx.compose.runtime.mutableStateListOf

/**
 * Admin settings & test UI — Jetpack Compose Material3.
 * Password protected (1234). Also serves as settingsActivity for PrintService.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PrintDriverMain"
        private const val MASTER_PASSWORD = "32003200"
        const val EXTRA_REQUIRE_AUTH = "require_auth"
    }

    private val printer = DevicePrinter

    // Compose observable state
    private var authenticated by mutableStateOf(false)
    private var logText by mutableStateOf("")
    private var deviceStatus by mutableStateOf("확인 중...")
    private var schoolUrl by mutableStateOf("")
    private var autoStartEnabled by mutableStateOf(false)
    private var showPowerButton by mutableStateOf(false)
    private var showSchedule by mutableStateOf(false)
    private var testText by mutableStateOf("")
    private var cutModeFullCut by mutableStateOf(true)
    private var showPasswordChangeDialog by mutableStateOf(false)
    private var showManualPasswordChange by mutableStateOf(false)
    private var mobileMode by mutableStateOf(true)
    private var showClock by mutableStateOf(true)
    private var showRotateButton by mutableStateOf(false)
    private var showGames by mutableStateOf(false)
    private var nightSaveMode by mutableStateOf(true)
    private var nightSaveStartH by mutableIntStateOf(9)
    private var nightSaveStartM by mutableIntStateOf(0)
    private var nightSaveEndH by mutableIntStateOf(18)
    private var nightSaveEndM by mutableIntStateOf(0)
    private var webRunning by mutableStateOf(false)
    private var rawRunning by mutableStateOf(false)
    private var ippRunning by mutableStateOf(false)
    private val schedules = mutableStateListOf(
        DaySchedule(), DaySchedule(), DaySchedule(), DaySchedule(),
        DaySchedule(), DaySchedule(), DaySchedule()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preserve orientation from WebPrintActivity (fixes Android 7 orientation reset)
        requestedOrientation = if (AppPrefs.isLandscape(this))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT

        schoolUrl = AppPrefs.getSchoolUrl(this)
        autoStartEnabled = AppPrefs.getAutoStart(this)
        showPowerButton = AppPrefs.getShowPowerButton(this)
        showSchedule = AppPrefs.getShowSchedule(this)
        cutModeFullCut = AppPrefs.isFullCut(this)
        mobileMode = AppPrefs.isMobileMode(this)
        showClock = AppPrefs.getShowClock(this)
        showRotateButton = AppPrefs.getShowRotateButton(this)
        showGames = AppPrefs.getShowGames(this)
        nightSaveMode = AppPrefs.isNightSaveMode(this)
        val (nsh, nsm) = AppPrefs.getNightSaveDaytimeStart(this)
        val (neh, nem) = AppPrefs.getNightSaveDaytimeEnd(this)
        nightSaveStartH = nsh; nightSaveStartM = nsm
        nightSaveEndH = neh; nightSaveEndM = nem
        for (i in 0..6) schedules[i] = AppPrefs.getDaySchedule(this, i)
        // Force re-auth when coming from games
        if (intent?.getBooleanExtra(EXTRA_REQUIRE_AUTH, false) == true) {
            authenticated = false
        }
        webRunning = WebServerService.isWebRunning
        rawRunning = WebServerService.isRawRunning
        ippRunning = WebServerService.isIppRunning

        setContent {
            AdminTheme {
                if (!authenticated) {
                    PasswordDialog()
                } else if (showPasswordChangeDialog) {
                    PasswordChangeDialog()
                } else {
                    AdminScreen()
                    if (showManualPasswordChange) {
                        ManualPasswordChangeDialog()
                    }
                }
            }
        }

        checkDeviceStatus()
    }

    override fun onResume() {
        super.onResume()
        webRunning = WebServerService.isWebRunning
        rawRunning = WebServerService.isRawRunning
        ippRunning = WebServerService.isIppRunning
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
                    when {
                        password == MASTER_PASSWORD -> {
                            authenticated = true
                        }
                        password == AppPrefs.getAdminPassword(this@MainActivity) -> {
                            authenticated = true
                            if (AppPrefs.isDefaultPassword(this@MainActivity)) {
                                showPasswordChangeDialog = true
                            }
                        }
                        else -> isError = true
                    }
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { finish() }) { Text("취소") }
            }
        )
    }

    @Composable
    private fun PasswordChangeDialog() {
        var newPassword by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { /* Cannot dismiss — must change password */ },
            title = { Text("비밀번호 변경", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "초기 비밀번호를 변경해주세요 (4자리 이상)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; errorMsg = "" },
                        label = { Text("새 비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMsg = "" },
                        label = { Text("비밀번호 확인") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    when {
                        newPassword.length < 4 -> errorMsg = "4자리 이상 입력해주세요"
                        newPassword != confirmPassword -> errorMsg = "비밀번호가 일치하지 않습니다"
                        else -> {
                            AppPrefs.setAdminPassword(this@MainActivity, newPassword)
                            showPasswordChangeDialog = false
                        }
                    }
                }) { Text("변경") }
            },
            dismissButton = null
        )
    }

    @Composable
    private fun ManualPasswordChangeDialog() {
        var currentPassword by remember { mutableStateOf("") }
        var newPassword by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showManualPasswordChange = false },
            title = { Text("비밀번호 변경", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it; errorMsg = "" },
                        label = { Text("현재 비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; errorMsg = "" },
                        label = { Text("새 비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMsg = "" },
                        label = { Text("비밀번호 확인") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val saved = AppPrefs.getAdminPassword(this@MainActivity)
                    when {
                        currentPassword != saved && currentPassword != MASTER_PASSWORD ->
                            errorMsg = "현재 비밀번호가 틀렸습니다"
                        newPassword.length < 4 -> errorMsg = "4자리 이상 입력해주세요"
                        newPassword != confirmPassword -> errorMsg = "비밀번호가 일치하지 않습니다"
                        else -> {
                            AppPrefs.setAdminPassword(this@MainActivity, newPassword)
                            showManualPasswordChange = false
                        }
                    }
                }) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { showManualPasswordChange = false }) { Text("취소") }
            }
        )
    }

    // ── Admin Screen ─────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AdminScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val tabTitles = listOf("상태", "설정", "테스트")

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("관리자 설정", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            startActivity(Intent(this@MainActivity, WebPrintActivity::class.java))
                            finish()
                        }) {
                            Icon(Icons.Filled.Home, contentDescription = "홈", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val intent = Intent(this@MainActivity, WebPrintActivity::class.java)
                            intent.putExtra(WebPrintActivity.EXTRA_SCREEN_OFF_NOW, true)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            startActivity(intent)
                            finish()
                        }) {
                            Icon(
                                Icons.Filled.PowerSettingsNew,
                                contentDescription = "화면 끄기",
                                tint = Color.White
                            )
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
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> StatusTab()
                    1 -> SettingsTab()
                    2 -> TestTab()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── STATUS TAB ────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    @Composable
    private fun StatusTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ServerStatusCard()
            PrinterStatusCard()
            DeviceInfoCard()
        }
    }

    @Composable
    private fun ServerStatusCard() {
        val ipAddress = remember { getDeviceIp() }
        val anyRunning = webRunning || rawRunning || ippRunning
        var showManual by remember { mutableStateOf(false) }

        if (showManual) {
            ManualDialog(ipAddress) { showManual = false }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (ipAddress != "N/A") Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = if (ipAddress != "N/A") Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ipAddress,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (anyRunning) "서버 실행 중" else "서버 중지됨",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (anyRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                    }
                }

                // IP-based usage guide
                if (ipAddress != "N/A" && anyRunning) {
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (webRunning) {
                                Text(
                                    "웹 관리: http://$ipAddress:8080",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (rawRunning) {
                                Text(
                                    "네트워크 인쇄: $ipAddress:9100 (RAW)",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (ippRunning) {
                                Text(
                                    "IPP 인쇄: ipp://$ipAddress:6631/ipp/print",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                ServerToggleRow("웹 관리", ":8080", webRunning) { checked ->
                    WebServerService.toggleServer(this@MainActivity, WebServerService.SERVER_WEB, checked)
                    webRunning = checked
                }
                ServerToggleRow("RAW 인쇄", ":9100", rawRunning) { checked ->
                    WebServerService.toggleServer(this@MainActivity, WebServerService.SERVER_RAW, checked)
                    rawRunning = checked
                }
                ServerToggleRow("IPP 인쇄", ":6631", ippRunning) { checked ->
                    WebServerService.toggleServer(this@MainActivity, WebServerService.SERVER_IPP, checked)
                    ippRunning = checked
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showManual = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("사용 매뉴얼")
                }
            }
        }
    }

    @Composable
    private fun ManualDialog(ipAddress: String, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("사용 매뉴얼", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ManualSection(
                        "1. 웹 관리 페이지",
                        "같은 와이파이에 연결된 PC나 스마트폰의 브라우저에서 아래 주소를 입력하세요.\n\n" +
                        "http://$ipAddress:8080\n\n" +
                        "웹 관리 페이지에서 프린터 상태 확인, 테스트 인쇄, 설정 변경 등을 할 수 있습니다."
                    )
                    ManualSection(
                        "2. 네트워크 인쇄 (RAW 9100)",
                        "Windows에서 네트워크 프린터를 추가할 때:\n\n" +
                        "  1) 설정 → 프린터 및 스캐너 → 프린터 추가\n" +
                        "  2) \"TCP/IP 주소로 프린터 추가\" 선택\n" +
                        "  3) IP: $ipAddress / 포트: 9100\n" +
                        "  4) 드라이버: \"Generic / Text Only\" 선택\n\n" +
                        "설정 후 일반 프로그램에서 인쇄하면 키오스크의 감열 프린터로 출력됩니다."
                    )
                    ManualSection(
                        "3. IPP 인쇄 (스마트폰)",
                        "스마트폰에서 인쇄하려면:\n\n" +
                        "  1) 같은 와이파이에 연결\n" +
                        "  2) LibroPrintPlugin 앱 설치 (72mm 전용)\n" +
                        "  3) 인쇄 메뉴에서 \"LibroPrinter\" 선택\n\n" +
                        "72mm 폭에 맞게 자동 변환되어 감열 프린터로 출력됩니다."
                    )
                    ManualSection(
                        "4. 사다리 / 빙고 게임",
                        "홈 화면 상단 툴바의 게임 아이콘을 터치하거나, " +
                        "관리자 설정 → 기타에서 실행할 수 있습니다.\n\n" +
                        "게임 결과를 감열 프린터로 바로 인쇄할 수 있습니다."
                    )
                    ManualSection(
                        "5. 절단 모드",
                        "홈 화면 상단 가위 아이콘을 터치하면 전체절단/부분절단을 전환할 수 있습니다.\n\n" +
                        "  - 전체절단: 용지를 완전히 자름\n" +
                        "  - 부분절단: 한쪽이 연결된 채로 자름"
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        )
    }

    @Composable
    private fun ManualSection(title: String, content: String) {
        Column {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                content,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    @Composable
    private fun ServerToggleRow(
        name: String,
        port: String,
        running: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = if (running) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(name, fontSize = 14.sp)
                Text(
                    "  $port",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = running, onCheckedChange = onToggle)
        }
    }

    @Composable
    private fun PrinterStatusCard() {
        val devFile = File(DevicePrinter.DEVICE_PATH)
        val exists = devFile.exists()
        val canRead = exists && devFile.canRead()
        val canWrite = exists && devFile.canWrite()

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("프린터 상태", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(8.dp))
                StatusRow("장치 경로", DevicePrinter.DEVICE_PATH, true)
                StatusRow("장치 존재", if (exists) "YES" else "NO", exists)
                StatusRow("읽기 가능", if (canRead) "YES" else "NO", canRead)
                StatusRow("쓰기 가능", if (canWrite) "YES" else "NO", canWrite)
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String, ok: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                tint = if (ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, modifier = Modifier.width(70.dp))
            Text(
                value,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun DeviceInfoCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── SETTINGS TAB ──────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    @Composable
    private fun SettingsTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionInfoCard()
            SchoolUrlCard()
            AppSettingsCard()
            ScheduleCard()
        }
    }

    @Composable
    private fun ConnectionInfoCard() {
        val ipAddress = remember { getDeviceIp() }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ipAddress != "N/A") Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        contentDescription = null,
                        tint = if (ipAddress != "N/A") Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("프린터 접속 정보", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                if (ipAddress == "N/A") {
                    Text("WiFi에 연결되어 있지 않습니다", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("IP 주소: $ipAddress", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("접속 방법", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    ConnectionRow("웹 관리 페이지", "http://$ipAddress:8080", webRunning)
                    ConnectionRow("네트워크 인쇄 (RAW)", "$ipAddress:9100", rawRunning)
                    ConnectionRow("IPP 인쇄 (스마트폰)", "ipp://$ipAddress:6631/ipp/print", ippRunning)
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("PC에서 인쇄하기", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("  1) 제어판 → 장치 및 프린터 → 프린터 추가", fontSize = 11.sp)
                            Text("  2) TCP/IP 주소로 프린터 추가", fontSize = 11.sp)
                            Text("  3) IP: $ipAddress / 포트: 9100", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text("스마트폰에서 인쇄하기", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("  1) 인쇄 플러그인 앱 설치", fontSize = 11.sp)
                            Text("  2) 같은 WiFi 연결 → 자동 검색됨", fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("브라우저에서 인쇄하기", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("  http://$ipAddress:8080 접속", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionRow(label: String, address: String, running: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (running) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                tint = if (running) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, modifier = Modifier.width(120.dp))
            Text(
                address,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun SchoolUrlCard() {
        var textFieldValue by remember {
            mutableStateOf(TextFieldValue(schoolUrl))
        }
        // Sync when schoolUrl changes externally
        if (textFieldValue.text != schoolUrl && !textFieldValue.text.contentEquals(schoolUrl)) {
            textFieldValue = TextFieldValue(schoolUrl)
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("학교주소 설정", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        schoolUrl = it.text
                    },
                    label = { Text("학교 홈페이지 URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                textFieldValue = textFieldValue.copy(
                                    selection = TextRange(0, textFieldValue.text.length)
                                )
                            }
                        }
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
                SettingSwitch(
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "부팅 후 자동실행",
                    checked = autoStartEnabled,
                    onCheckedChange = {
                        autoStartEnabled = it
                        AppPrefs.setAutoStart(this@MainActivity, it)
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "홈 화면 종료버튼 표시",
                    checked = showPowerButton,
                    onCheckedChange = {
                        showPowerButton = it
                        AppPrefs.setShowPowerButton(this@MainActivity, it)
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "홈 화면 시작/종료시간 표시",
                    checked = showSchedule,
                    onCheckedChange = {
                        showSchedule = it
                        AppPrefs.setShowSchedule(this@MainActivity, it)
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "절단 모드: ${if (cutModeFullCut) "전체절단" else "부분절단"}",
                    checked = cutModeFullCut,
                    onCheckedChange = {
                        cutModeFullCut = it
                        AppPrefs.setCutMode(this@MainActivity, if (it) "full" else "partial")
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "홈 화면 시계 표시",
                    checked = showClock,
                    onCheckedChange = {
                        showClock = it
                        AppPrefs.setShowClock(this@MainActivity, it)
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.ScreenRotation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "화면전환 표시",
                    checked = showRotateButton,
                    onCheckedChange = {
                        showRotateButton = it
                        AppPrefs.setShowRotateButton(this@MainActivity, it)
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "홈 화면 게임 표시 (사다리/빙고)",
                    checked = showGames,
                    onCheckedChange = {
                        showGames = it
                        AppPrefs.setShowGames(this@MainActivity, it)
                    }
                )
                SettingSwitch(
                    icon = { Icon(Icons.Filled.NightsStay, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "야간 절전모드",
                    checked = nightSaveMode,
                    onCheckedChange = {
                        nightSaveMode = it
                        AppPrefs.setNightSaveMode(this@MainActivity, it)
                    }
                )
                if (nightSaveMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("주간 활성시간:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            TimePickerDialog(this@MainActivity, { _, h, m ->
                                nightSaveStartH = h; nightSaveStartM = m
                                AppPrefs.setNightSaveDaytimeStart(this@MainActivity, h, m)
                            }, nightSaveStartH, nightSaveStartM, true).show()
                        }) {
                            Text(String.format("%02d:%02d", nightSaveStartH, nightSaveStartM),
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("~", modifier = Modifier.padding(horizontal = 2.dp))
                        TextButton(onClick = {
                            TimePickerDialog(this@MainActivity, { _, h, m ->
                                nightSaveEndH = h; nightSaveEndM = m
                                AppPrefs.setNightSaveDaytimeEnd(this@MainActivity, h, m)
                            }, nightSaveEndH, nightSaveEndM, true).show()
                        }) {
                            Text(String.format("%02d:%02d", nightSaveEndH, nightSaveEndM),
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        "  위 시간 외에 3분간 미터치 시 화면 OFF",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 32.dp, bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                SettingButton(
                    icon = { Icon(Icons.Filled.Print, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "인쇄 서비스 설정",
                    onClick = {
                        startActivity(Intent(android.provider.Settings.ACTION_PRINT_SETTINGS))
                    }
                )
                SettingButton(
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "인쇄 서비스 강제 활성화",
                    onClick = { forceEnablePrintService() }
                )
                SettingButton(
                    icon = { Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                    label = "비밀번호 변경",
                    onClick = { showManualPasswordChange = true }
                )
            }
        }
    }

    @Composable
    private fun SettingSwitch(
        icon: @Composable () -> Unit,
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 14.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun SettingButton(
        icon: @Composable () -> Unit,
        label: String,
        onClick: () -> Unit
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }

    @Composable
    private fun ScheduleCard() {
        var expanded by remember { mutableStateOf(false) }
        val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "자동 시작/종료 스케줄",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "종료 시간에 자동 OFF, 시작 시간에 RTC 알람 ON",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        dayNames.forEachIndexed { index, name ->
                            ScheduleRow(index, name)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScheduleRow(dayIndex: Int, dayName: String) {
        val sched = schedules[dayIndex]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dayName,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 4.dp)
            )
            Switch(
                checked = sched.enabled,
                onCheckedChange = {
                    schedules[dayIndex] = sched.copy(enabled = it)
                    saveAndReschedule(dayIndex)
                },
                modifier = Modifier.padding(end = 4.dp)
            )
            TextButton(
                onClick = {
                    TimePickerDialog(this@MainActivity, { _, h, m ->
                        schedules[dayIndex] = schedules[dayIndex].copy(startHour = h, startMin = m)
                        saveAndReschedule(dayIndex)
                    }, sched.startHour, sched.startMin, true).show()
                },
                enabled = sched.enabled
            ) {
                Text(
                    String.format("%02d:%02d", sched.startHour, sched.startMin),
                    fontSize = 14.sp,
                    color = if (sched.enabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            Text("~", modifier = Modifier.padding(horizontal = 2.dp))
            TextButton(
                onClick = {
                    TimePickerDialog(this@MainActivity, { _, h, m ->
                        schedules[dayIndex] = schedules[dayIndex].copy(endHour = h, endMin = m)
                        saveAndReschedule(dayIndex)
                    }, sched.endHour, sched.endMin, true).show()
                },
                enabled = sched.enabled
            ) {
                Text(
                    String.format("%02d:%02d", sched.endHour, sched.endMin),
                    fontSize = 14.sp,
                    color = if (sched.enabled) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }

    private fun saveAndReschedule(dayIndex: Int) {
        AppPrefs.setDaySchedule(this, dayIndex, schedules[dayIndex])
        PowerScheduleManager.scheduleNext(this)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── TEST TAB ──────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun TestTab() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DirectPrintTestCard()
            TextPrintCard()
            OtherCard()
            LogCard()
            Spacer(Modifier.height(16.dp))
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DirectPrintTestCard() {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("직접 인쇄 테스트", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2
                ) {
                    OutlinedButton(
                        onClick = { testConnection() },
                        modifier = Modifier.weight(1f)
                    ) { Text("연결테스트") }
                    OutlinedButton(
                        onClick = { testLabelPrint() },
                        modifier = Modifier.weight(1f)
                    ) { Text("라벨인쇄") }
                    OutlinedButton(
                        onClick = { testQrPrint() },
                        modifier = Modifier.weight(1f)
                    ) { Text("QR인쇄") }
                    OutlinedButton(
                        onClick = { testImagePrint() },
                        modifier = Modifier.weight(1f)
                    ) { Text("이미지인쇄") }
                    OutlinedButton(
                        onClick = { testCutType(full = true) },
                        modifier = Modifier.weight(1f)
                    ) { Text("전체절단") }
                    OutlinedButton(
                        onClick = { testCutType(full = false) },
                        modifier = Modifier.weight(1f)
                    ) { Text("부분절단") }
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
                FilledTonalButton(
                    onClick = { startActivity(Intent(this@MainActivity, BingoGameActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("빙고 게임") }
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

    // ── Device IP ─────────────────────────────────────────────────────────

    private fun getDeviceIp(): String {
        // 1) Try NetworkInterface (works for both WiFi and Ethernet)
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
        // 2) Fallback: WifiManager
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
            }
        } catch (_: Exception) {}
        return "N/A"
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

    // ── APK Install ────────────────────────────────────────────────────────

    private fun showApkInstallDialog() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apks = dir.listFiles(java.io.FileFilter { it.name.endsWith(".apk", true) })
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (apks.isEmpty()) {
            Toast.makeText(this, "Downloads 폴더에 APK 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val names = apks.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("설치할 APK 선택")
            .setItems(names) { _, i -> installApk(apks[i]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "APK install error", e)
            Toast.makeText(this, "설치 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Print Service ─────────────────────────────────────────────────────

    private fun forceEnablePrintService() {
        Thread {
            try {
                val component = "${packageName}/com.betona.printdriver.LibroPrintService"
                val p = Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c", "settings put secure enabled_print_services $component"
                ))
                p.waitFor()
                runOnUiThread {
                    Toast.makeText(this, "인쇄 서비스 활성화 완료", Toast.LENGTH_SHORT).show()
                }
                Log.d(TAG, "PrintService force-enabled: $component")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable PrintService", e)
                runOnUiThread {
                    Toast.makeText(this, "활성화 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ── Logging ──────────────────────────────────────────────────────────

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        val line = "[$time] $message"
        Log.d(TAG, message)
        logText += "$line\n"
    }
}
