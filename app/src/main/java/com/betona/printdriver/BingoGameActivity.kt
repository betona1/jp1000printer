package com.betona.printdriver

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class BingoGameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BingoGame"
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
            BingoTheme {
                BingoGameScreen(font)
            }
        }
    }

    @Composable
    private fun BingoTheme(content: @Composable () -> Unit) {
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

    private enum class Phase { SETUP, CARDS, DRAW }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun BingoGameScreen(font: Typeface) {
        var phase by remember { mutableStateOf(Phase.SETUP) }

        var gridSize by remember { mutableIntStateOf(3) }
        var rangeStart by remember { mutableStateOf("") }
        var rangeEnd by remember { mutableStateOf("") }
        var playerCount by remember { mutableIntStateOf(5) }

        var generator by remember { mutableStateOf<BingoGenerator?>(null) }
        val drawnNumbersState = remember { mutableStateListOf<Int>() }
        var lastDrawnNumber by remember { mutableStateOf<Int?>(null) }
        var requiredLines by remember { mutableIntStateOf(1) }
        var showWinnerDialog by remember { mutableStateOf(false) }
        var winnerIndices by remember { mutableStateOf(listOf<Int>()) }
        var drawVersion by remember { mutableIntStateOf(0) }
        var showManualPicker by remember { mutableStateOf(false) }

        val context = LocalContext.current

        val defaultMax = gridSize * gridSize
        val effectiveStart = rangeStart.toIntOrNull() ?: 1
        val effectiveEnd = rangeEnd.toIntOrNull() ?: defaultMax

        fun startGame() {
            val cellCount = gridSize * gridSize
            if (effectiveEnd - effectiveStart + 1 < cellCount) {
                Toast.makeText(context, "숫자 범위가 ${cellCount}개 이상이어야 합니다", Toast.LENGTH_SHORT).show()
                return
            }
            if (effectiveStart >= effectiveEnd) {
                Toast.makeText(context, "시작이 끝보다 작아야 합니다", Toast.LENGTH_SHORT).show()
                return
            }
            val gen = BingoGenerator(gridSize, effectiveStart..effectiveEnd, playerCount)
            generator = gen
            drawnNumbersState.clear()
            lastDrawnNumber = null
            phase = Phase.CARDS
        }

        fun onNumberDrawn(num: Int) {
            drawnNumbersState.add(num)
            lastDrawnNumber = num
            drawVersion++

            val gen = generator ?: return
            val winners = gen.getWinners(requiredLines)
            if (winners.isNotEmpty()) {
                winnerIndices = winners
                showWinnerDialog = true
            }
        }

        fun drawNumberAuto() {
            val gen = generator ?: return
            val num = gen.drawNumber()
            if (num == null) {
                Toast.makeText(context, "모든 번호가 추첨되었습니다", Toast.LENGTH_SHORT).show()
                return
            }
            onNumberDrawn(num)
        }

        fun drawNumberManual(num: Int) {
            val gen = generator ?: return
            if (gen.drawSpecificNumber(num)) {
                onNumberDrawn(num)
                showManualPicker = false
            }
        }

        fun printAllCards() {
            val gen = generator ?: return
            Toast.makeText(context, "전체 카드 인쇄 중... (${gen.playerCount}장)", Toast.LENGTH_SHORT).show()
            Thread {
                if (!printer.open()) {
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "프린터를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                try {
                    printer.initPrinter()
                    val drawnSet = gen.drawnNumbers.toSet()
                    for (i in 0 until gen.playerCount) {
                        val bitmap = BingoCardView.renderToBitmap(
                            i, gen.cards[i], gen.gridSize, drawnSet, font = font
                        )
                        val mono = BitmapConverter.toMonochrome(bitmap)
                        val trimmed = BitmapConverter.trimTrailingWhiteRows(mono)
                        bitmap.recycle()
                        printer.printBitmap(trimmed)
                        printer.write(EscPosCommands.feedDots(160))
                        printer.feedAndCut(fullCut = false)
                    }
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "인쇄 완료", Toast.LENGTH_SHORT).show() }
                    Log.d(TAG, "All cards printed")
                } catch (e: Exception) {
                    Log.e(TAG, "Print error", e)
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "인쇄 오류: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        fun printWinnerCards(indices: List<Int>) {
            val gen = generator ?: return
            Toast.makeText(context, "당첨 카드 인쇄 중...", Toast.LENGTH_SHORT).show()
            Thread {
                if (!printer.open()) {
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "프린터를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                try {
                    printer.initPrinter()
                    val drawnSet = gen.drawnNumbers.toSet()
                    for (i in indices) {
                        val lines = gen.getCompletedLines(i)
                        val bitmap = BingoCardView.renderToBitmap(
                            i, gen.cards[i], gen.gridSize, drawnSet,
                            bingoLines = lines.size, completedLines = lines, font = font
                        )
                        val mono = BitmapConverter.toMonochrome(bitmap)
                        val trimmed = BitmapConverter.trimTrailingWhiteRows(mono)
                        bitmap.recycle()
                        printer.printBitmap(trimmed)
                        printer.write(EscPosCommands.feedDots(160))
                        printer.feedAndCut(fullCut = AppPrefs.isFullCut(this@BingoGameActivity))
                    }
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "당첨 카드 인쇄 완료", Toast.LENGTH_SHORT).show() }
                    Log.d(TAG, "Winner cards printed")
                } catch (e: Exception) {
                    Log.e(TAG, "Print error", e)
                    runOnUiThread { if (!isFinishing) Toast.makeText(context, "인쇄 오류: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        // Winner dialog
        if (showWinnerDialog && winnerIndices.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showWinnerDialog = false },
                title = { Text("빙고!", fontWeight = FontWeight.Bold, color = Color(0xFFE91E63)) },
                text = {
                    Column {
                        Text(
                            "당첨 카드: ${winnerIndices.map { "#${it + 1}" }.joinToString(", ")}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${requiredLines}줄 빙고 달성!")
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showWinnerDialog = false
                        printWinnerCards(winnerIndices)
                    }) { Text("인쇄하기") }
                },
                dismissButton = {
                    TextButton(onClick = { showWinnerDialog = false }) { Text("확인") }
                }
            )
        }

        // Manual number picker dialog
        if (showManualPicker) {
            val gen = generator
            if (gen != null) {
                ManualNumberPickerDialog(
                    numberRange = gen.numberRange,
                    drawnNumbers = drawnNumbersState.toSet(),
                    onPick = { drawNumberManual(it) },
                    onDismiss = { showManualPicker = false }
                )
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("빙고 게임", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            startActivity(Intent(this@BingoGameActivity, WebPrintActivity::class.java))
                            finish()
                        }) {
                            Icon(Icons.Filled.Home, contentDescription = "홈", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            startActivity(Intent(this@BingoGameActivity, MainActivity::class.java).apply {
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
            when (phase) {
                Phase.SETUP -> SetupPhase(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    gridSize = gridSize,
                    onGridSizeChange = { gridSize = it },
                    rangeStart = rangeStart,
                    onRangeStartChange = { rangeStart = it },
                    rangeEnd = rangeEnd,
                    onRangeEndChange = { rangeEnd = it },
                    defaultMax = defaultMax,
                    playerCount = playerCount,
                    onPlayerCountChange = { playerCount = it },
                    onStartGame = { startGame() }
                )

                Phase.CARDS -> generator?.let { gen -> CardsPhase(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    generator = gen,
                    font = font,
                    drawnNumbers = drawnNumbersState.toSet(),
                    drawVersion = drawVersion,
                    onPrintAll = { printAllCards() },
                    onStartDraw = { phase = Phase.DRAW }
                ) }

                Phase.DRAW -> generator?.let { gen -> DrawPhase(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    generator = gen,
                    font = font,
                    drawnNumbers = drawnNumbersState.toList(),
                    lastDrawnNumber = lastDrawnNumber,
                    drawVersion = drawVersion,
                    requiredLines = requiredLines,
                    onRequiredLinesChange = { requiredLines = it },
                    onDrawNumberAuto = { drawNumberAuto() },
                    onShowManualPicker = { showManualPicker = true },
                    onNewGame = {
                        phase = Phase.SETUP
                        generator = null
                        drawnNumbersState.clear()
                        lastDrawnNumber = null
                        drawVersion = 0
                    }
                ) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SetupPhase(
        modifier: Modifier,
        gridSize: Int,
        onGridSizeChange: (Int) -> Unit,
        rangeStart: String,
        onRangeStartChange: (String) -> Unit,
        rangeEnd: String,
        onRangeEndChange: (String) -> Unit,
        defaultMax: Int,
        playerCount: Int,
        onPlayerCountChange: (Int) -> Unit,
        onStartGame: () -> Unit
    ) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("배열 크기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 4, 5).forEach { size ->
                            FilterChip(
                                selected = gridSize == size,
                                onClick = { onGridSizeChange(size) },
                                label = { Text("${size}x${size}") }
                            )
                        }
                    }
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("숫자 범위", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("기본: 1 ~ $defaultMax", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = rangeStart,
                            onValueChange = { onRangeStartChange(it.filter { c -> c.isDigit() }) },
                            label = { Text("시작") }, placeholder = { Text("1") },
                            modifier = Modifier.weight(1f), singleLine = true
                        )
                        Text("~", fontSize = 20.sp)
                        OutlinedTextField(
                            value = rangeEnd,
                            onValueChange = { onRangeEndChange(it.filter { c -> c.isDigit() }) },
                            label = { Text("끝") }, placeholder = { Text("$defaultMax") },
                            modifier = Modifier.weight(1f), singleLine = true
                        )
                    }
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("참가자 수", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = { if (playerCount > 1) onPlayerCountChange(playerCount - 1) },
                            enabled = playerCount > 1
                        ) { Text("−", fontSize = 20.sp) }
                        Text("${playerCount}명", modifier = Modifier.padding(horizontal = 24.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        FilledTonalButton(
                            onClick = { if (playerCount < 30) onPlayerCountChange(playerCount + 1) },
                            enabled = playerCount < 30
                        ) { Text("+", fontSize = 20.sp) }
                    }
                }
            }

            Button(
                onClick = onStartGame,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("게임 시작", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun CardsPhase(
        modifier: Modifier,
        generator: BingoGenerator,
        font: Typeface,
        drawnNumbers: Set<Int>,
        @Suppress("UNUSED_PARAMETER") drawVersion: Int,
        onPrintAll: () -> Unit,
        onStartDraw: () -> Unit
    ) {
        Column(modifier = modifier) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onPrintAll, modifier = Modifier.weight(1f)) { Text("전체 출력") }
                Button(onClick = onStartDraw, modifier = Modifier.weight(1f)) { Text("번호 추첨 시작") }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(generator.cards.indices.toList()) { idx ->
                    ElevatedCard(elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)) {
                        AndroidView(
                            factory = { ctx ->
                                BingoCardView(ctx).also { v ->
                                    v.setFont(font)
                                    v.setCard(idx, generator.cards[idx], generator.gridSize)
                                    v.setMarkedNumbers(drawnNumbers)
                                }
                            },
                            update = { v -> v.setMarkedNumbers(drawnNumbers) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun DrawPhase(
        modifier: Modifier,
        generator: BingoGenerator,
        font: Typeface,
        drawnNumbers: List<Int>,
        lastDrawnNumber: Int?,
        @Suppress("UNUSED_PARAMETER") drawVersion: Int,
        requiredLines: Int,
        onRequiredLinesChange: (Int) -> Unit,
        onDrawNumberAuto: () -> Unit,
        onShowManualPicker: () -> Unit,
        onNewGame: () -> Unit
    ) {
        Column(modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Last drawn number with animation
                if (lastDrawnNumber != null) {
                    val scale by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = tween(300),
                        label = "drawnScale"
                    )
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(Color(0xFFE91E63)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$lastDrawnNumber",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Bingo condition
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("빙고 조건:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val maxLines = generator.gridSize + 2
                    (1..minOf(maxLines, 5)).forEach { lines ->
                        FilterChip(
                            selected = requiredLines == lines,
                            onClick = { onRequiredLinesChange(lines) },
                            label = { Text("${lines}줄") }
                        )
                    }
                }

                // Card bingo progress
                if (drawnNumbers.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (idx in generator.cards.indices) {
                            val count = generator.countBingoLines(idx)
                            val isWinner = count >= requiredLines
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isWinner -> Color(0xFFE91E63)
                                            count > 0 -> Color(0xFFFFF176)
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "#${idx + 1}: ${count}줄",
                                    fontSize = 11.sp,
                                    fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isWinner) Color.White else Color.Black
                                )
                            }
                        }
                    }
                }

                // Draw history
                if (drawnNumbers.isNotEmpty()) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("추첨 이력 (${drawnNumbers.size}개)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                drawnNumbers.forEach { num ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (num == lastDrawnNumber) Color(0xFFE91E63)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "$num", fontSize = 11.sp,
                                            fontWeight = if (num == lastDrawnNumber) FontWeight.Bold else FontWeight.Normal,
                                            color = if (num == lastDrawnNumber) Color.White else Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDrawNumberAuto,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("자동 추첨", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = onNewGame, modifier = Modifier.weight(1f)) { Text("새 게임") }
                }
                FilledTonalButton(
                    onClick = onShowManualPicker,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("번호를 선택하세요") }
            }

            // Card grid
            val drawnSet = drawnNumbers.toSet()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(generator.cards.indices.toList()) { idx ->
                    val completedLines = generator.getCompletedLines(idx)
                    val bingoCount = completedLines.size
                    ElevatedCard(
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                        colors = if (bingoCount >= requiredLines) {
                            CardDefaults.elevatedCardColors(containerColor = Color(0xFFFFF9C4))
                        } else {
                            CardDefaults.elevatedCardColors()
                        }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                BingoCardView(ctx).also { v ->
                                    v.setFont(font)
                                    v.setCard(idx, generator.cards[idx], generator.gridSize)
                                    v.setMarkedNumbers(drawnSet)
                                    v.setCompletedLines(completedLines)
                                }
                            },
                            update = { v ->
                                v.setMarkedNumbers(drawnSet)
                                v.setCompletedLines(generator.getCompletedLines(idx))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ManualNumberPickerDialog(
        numberRange: IntRange,
        drawnNumbers: Set<Int>,
        onPick: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("번호를 선택하세요", fontWeight = FontWeight.Bold) },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    numberRange.forEach { num ->
                        val isDrawn = num in drawnNumbers
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isDrawn -> Color(0xFFE0E0E0)
                                        else -> Color(0xFFBBDEFB)
                                    }
                                )
                                .then(
                                    if (!isDrawn) Modifier.clickable { onPick(num) }
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$num",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDrawn) Color(0xFFBDBDBD) else Color(0xFF0D47A1)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        )
    }
}
