package com.betona.printdriver

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class LadderGameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LadderGame"
    }

    private val printer = DevicePrinter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val font = try {
            Typeface.createFromAsset(assets, "nanum_gothic.ttf")
        } catch (_: Exception) {
            Typeface.DEFAULT
        }

        setContent {
            LadderTheme {
                LadderGameScreen(font)
            }
        }
    }

    @Composable
    private fun LadderTheme(content: @Composable () -> Unit) {
        val colorScheme = lightColorScheme(
            primary = Color(0xFF1976D2),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBBDEFB),
            onPrimaryContainer = Color(0xFF0D47A1),
            error = Color(0xFFD32F2F),
            onError = Color.White,
            surface = Color(0xFFFAFAFA),
            onSurface = Color(0xFF212121),
            surfaceVariant = Color(0xFFE3F2FD),
            onSurfaceVariant = Color(0xFF424242)
        )
        MaterialTheme(colorScheme = colorScheme, content = content)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun LadderGameScreen(font: Typeface) {
        var playerCount by remember { mutableIntStateOf(3) }
        var selectedLength by remember { mutableIntStateOf(LadderGenerator.LENGTH_MEDIUM) }
        var generator by remember { mutableStateOf<LadderGenerator?>(null) }
        var ladderViewRef by remember { mutableStateOf<LadderView?>(null) }
        var resultText by remember { mutableStateOf("") }
        var showResult by remember { mutableStateOf(false) }
        var showLadder by remember { mutableStateOf(false) }

        val resultTexts = remember { mutableStateListOf<String>() }
        // Initialize result texts
        if (resultTexts.size != playerCount) {
            val old = resultTexts.toList()
            resultTexts.clear()
            for (i in 0 until playerCount) {
                resultTexts.add(old.getOrElse(i) { "" })
            }
        }

        val resultHints = listOf("당첨!", "5000원", "벌칙", "꽝", "1등", "커피", "간식", "통과")
        val context = LocalContext.current

        fun getNames(): List<String> = (1..playerCount).map { "$it" }

        fun getResults(): List<String> = resultTexts.mapIndexed { i, text ->
            text.ifEmpty { "결과${i + 1}" }
        }

        fun printLadder() {
            val lv = ladderViewRef ?: return
            generator ?: return
            Toast.makeText(context, "사다리 인쇄 중...", Toast.LENGTH_SHORT).show()
            Thread {
                if (!printer.open()) {
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "프린터를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                try {
                    printer.initPrinter()
                    val bitmap = lv.toBitmap(includeResults = false)
                    val scaled = BitmapConverter.scaleToWidth(bitmap, DevicePrinter.PRINT_WIDTH_PX)
                    val monoData = BitmapConverter.toMonochrome(scaled)
                    val trimmed = BitmapConverter.trimTrailingWhiteRows(monoData)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    printer.printBitmap(trimmed)
                    // ~2cm bottom margin before cut (160 dots at 203 DPI)
                    printer.write(EscPosCommands.feedDots(160))
                    printer.feedAndCut(fullCut = AppPrefs.isFullCut(this@LadderGameActivity))
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "사다리 인쇄 완료", Toast.LENGTH_SHORT).show() }
                    Log.d(TAG, "Ladder print complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Print error", e)
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "인쇄 오류: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        fun printResults() {
            val gen = generator ?: return
            Toast.makeText(context, "결과 인쇄 중...", Toast.LENGTH_SHORT).show()
            Thread {
                if (!printer.open()) {
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "프린터를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                try {
                    printer.initPrinter()
                    val names = getNames()
                    val results = getResults()
                    val text = buildString {
                        appendLine("[ 사다리 결과 ]")
                        appendLine()
                        for (i in 0 until playerCount) {
                            val dest = gen.getDestination(i)
                            appendLine("${names[i]} → ${results[dest]}")
                        }
                    }
                    val resultBmp = BitmapConverter.textToBitmap(this@LadderGameActivity, text, 28f)
                    if (resultBmp != null) {
                        val monoResult = BitmapConverter.toMonochrome(resultBmp)
                        val trimmedResult = BitmapConverter.trimTrailingWhiteRows(monoResult)
                        resultBmp.recycle()
                        printer.printBitmap(trimmedResult)
                        printer.write(EscPosCommands.feedDots(160))
                        printer.feedAndCut(fullCut = AppPrefs.isFullCut(this@LadderGameActivity))
                    }
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "결과 인쇄 완료", Toast.LENGTH_SHORT).show() }
                    Log.d(TAG, "Results print complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Print error", e)
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "인쇄 오류: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("사다리 게임", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            startActivity(Intent(this@LadderGameActivity, WebPrintActivity::class.java))
                            finish()
                        }) {
                            Icon(Icons.Filled.Home, contentDescription = "홈", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            startActivity(Intent(this@LadderGameActivity, MainActivity::class.java).apply {
                                putExtra(MainActivity.EXTRA_REQUIRE_AUTH, true)
                            })
                            finish()
                        }) {
                            Icon(Icons.Filled.Settings, contentDescription = "관리자", tint = Color.White)
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
                // Player count card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "참가자 수",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (playerCount > 2) playerCount--
                                },
                                enabled = playerCount > 2
                            ) {
                                Text("−", fontSize = 20.sp)
                            }
                            Text(
                                "$playerCount",
                                modifier = Modifier.padding(horizontal = 24.dp),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (playerCount < 15) playerCount++
                                },
                                enabled = playerCount < 15
                            ) {
                                Text("+", fontSize = 20.sp)
                            }
                        }
                    }
                }

                // Ladder length card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "사다리 길이",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            data class LengthOption(val label: String, val value: Int)
                            val options = listOf(
                                LengthOption("짧게", LadderGenerator.LENGTH_SHORT),
                                LengthOption("보통", LadderGenerator.LENGTH_MEDIUM),
                                LengthOption("길게", LadderGenerator.LENGTH_LONG)
                            )
                            options.forEach { option ->
                                FilterChip(
                                    selected = selectedLength == option.value,
                                    onClick = { selectedLength = option.value },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                    }
                }

                // Results input card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "결과 (금액/벌칙)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        for (i in 0 until playerCount) {
                            OutlinedTextField(
                                value = resultTexts[i],
                                onValueChange = { resultTexts[i] = it },
                                label = { Text("${i + 1}번") },
                                placeholder = { Text(resultHints.getOrElse(i) { "결과 ${i + 1}" }) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                singleLine = true
                            )
                        }
                    }
                }

                // Generate button
                Button(
                    onClick = {
                        val names = getNames()
                        val results = getResults()
                        val gen = LadderGenerator(playerCount, selectedLength)
                        generator = gen

                        ladderViewRef?.let { lv ->
                            font.let { lv.setFont(it) }
                            lv.setLadder(gen, names, results)
                            lv.resetRevealed()
                            lv.setShowResults(true)
                        }

                        showLadder = true
                        showResult = false
                        resultText = ""
                        Toast.makeText(context, "번호를 터치하면 결과가 하나씩 공개됩니다", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("사다리 생성", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                // Ladder view
                AnimatedVisibility(visible = showLadder) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                LadderView(ctx).also { lv ->
                                    ladderViewRef = lv
                                    font.let { lv.setFont(it) }
                                    lv.onPathComplete = { startRail, endRail ->
                                        runOnUiThread { if (!isFinishing) {
                                            lv.revealResult(endRail)
                                            val names = getNames()
                                            val results = getResults()
                                            val name = names.getOrElse(startRail) { "?" }
                                            val result = results.getOrElse(endRail) { "?" }
                                            resultText = "$name → $result"
                                            showResult = true
                                        } }
                                    }
                                    // If generator already set, apply it
                                    generator?.let { gen ->
                                        lv.setLadder(gen, getNames(), getResults())
                                        lv.resetRevealed()
                                        lv.setShowResults(true)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Result text
                AnimatedVisibility(visible = showResult && resultText.isNotEmpty()) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                    ) {
                        Text(
                            text = resultText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Reveal all button
                AnimatedVisibility(visible = showLadder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        OutlinedButton(onClick = {
                            ladderViewRef?.revealAll()
                            resultText = "전체 결과 공개!"
                            showResult = true
                        }) {
                            Text("전체 결과 보기")
                        }
                    }
                }

                // Print buttons
                AnimatedVisibility(visible = showLadder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { printLadder() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("사다리 인쇄")
                        }
                        OutlinedButton(
                            onClick = { printResults() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("결과 인쇄")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
