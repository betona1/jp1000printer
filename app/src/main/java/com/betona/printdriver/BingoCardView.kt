package com.betona.printdriver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that draws a single bingo card with grid, numbers, marked circles,
 * and strike-through lines on completed bingo lines.
 */
class BingoCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cardIndex: Int = 0
    private var numbers: List<Int> = emptyList()
    private var gridSize: Int = 3
    private var markedNumbers: Set<Int> = emptySet()
    private var bingoLines: Int = 0
    private var completedLines: List<List<Int>> = emptyList()

    private val headerHeight = 48f

    // Paints
    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val innerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 32f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 24f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val markedCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40F44336"); style = Paint.Style.FILL
    }
    private val markedCircleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; strokeWidth = 3f; style = Paint.Style.STROKE
    }

    // Bingo line strike colors (cycle through)
    private val lineColors = intArrayOf(
        Color.parseColor("#E91E63"), // pink
        Color.parseColor("#2196F3"), // blue
        Color.parseColor("#4CAF50"), // green
        Color.parseColor("#FF9800"), // orange
        Color.parseColor("#9C27B0"), // purple
    )

    fun setFont(typeface: Typeface) {
        numberPaint.typeface = typeface
        headerPaint.typeface = typeface
        invalidate()
    }

    fun setCard(cardIndex: Int, numbers: List<Int>, gridSize: Int) {
        this.cardIndex = cardIndex
        this.numbers = numbers
        this.gridSize = gridSize
        requestLayout()
        invalidate()
    }

    fun setMarkedNumbers(drawn: Set<Int>) {
        this.markedNumbers = drawn
        invalidate()
    }

    fun setBingoLineCount(lines: Int) {
        this.bingoLines = lines
        invalidate()
    }

    fun setCompletedLines(lines: List<List<Int>>) {
        this.completedLines = lines
        this.bingoLines = lines.size
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cellSize = w.toFloat() / gridSize
        val h = (headerHeight + cellSize * gridSize + 4).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (numbers.isEmpty()) return
        canvas.drawColor(Color.WHITE)

        val w = width.toFloat()
        val gridTop = headerHeight
        val cellSize = w / gridSize

        // Header
        val headerText = if (bingoLines > 0) "카드 #${cardIndex + 1} - ${bingoLines}줄!"
        else "카드 #${cardIndex + 1}"
        headerPaint.color = if (bingoLines > 0) Color.parseColor("#E91E63") else Color.BLACK
        canvas.drawText(headerText, w / 2, headerHeight - 14f, headerPaint)

        // Cells
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val idx = row * gridSize + col
                if (idx >= numbers.size) continue
                val num = numbers[idx]
                val cx = col * cellSize + cellSize / 2
                val cy = gridTop + row * cellSize + cellSize / 2

                if (num in markedNumbers) {
                    val radius = cellSize / 2 - 6f
                    canvas.drawCircle(cx, cy, radius, markedCirclePaint)
                    canvas.drawCircle(cx, cy, radius, markedCircleStrokePaint)
                }
                canvas.drawText(num.toString(), cx, cy + numberPaint.textSize / 3, numberPaint)
            }
        }

        // Grid lines
        for (i in 1 until gridSize) {
            val x = i * cellSize
            canvas.drawLine(x, gridTop, x, gridTop + gridSize * cellSize, innerLinePaint)
            val y = gridTop + i * cellSize
            canvas.drawLine(0f, y, w, y, innerLinePaint)
        }
        canvas.drawRect(1f, gridTop + 1f, w - 1f, gridTop + gridSize * cellSize - 1f, outerBorderPaint)

        // Strike-through lines on completed bingo lines
        drawBingoStrikeLines(canvas, gridTop, cellSize)
    }

    private fun drawBingoStrikeLines(canvas: Canvas, gridTop: Float, cellSize: Float) {
        if (completedLines.isEmpty()) return

        completedLines.forEachIndexed { lineIdx, cells ->
            if (cells.size < 2) return@forEachIndexed
            val color = lineColors[lineIdx % lineColors.size]
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 4f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            val firstCell = cells.first()
            val lastCell = cells.last()
            val r1 = firstCell / gridSize; val c1 = firstCell % gridSize
            val r2 = lastCell / gridSize; val c2 = lastCell % gridSize

            val x1 = c1 * cellSize + cellSize / 2
            val y1 = gridTop + r1 * cellSize + cellSize / 2
            val x2 = c2 * cellSize + cellSize / 2
            val y2 = gridTop + r2 * cellSize + cellSize / 2

            // Extend line slightly beyond cell centers
            val dx = x2 - x1; val dy = y2 - y1
            val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (len > 0) {
                val ext = cellSize * 0.3f
                val ex = dx / len * ext; val ey = dy / len * ext
                canvas.drawLine(x1 - ex, y1 - ey, x2 + ex, y2 + ey, paint)
            }
        }
    }

    fun toBitmap(printWidth: Int = DevicePrinter.PRINT_WIDTH_PX): Bitmap {
        if (numbers.isEmpty()) return Bitmap.createBitmap(printWidth, 100, Bitmap.Config.ARGB_8888)

        val cellSize = printWidth.toFloat() / gridSize
        val pHeaderHeight = 56f
        val bmpHeight = (pHeaderHeight + cellSize * gridSize + 8).toInt()
        val bitmap = Bitmap.createBitmap(printWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val scale = printWidth.toFloat() / 400f
        val pHeader = Paint(headerPaint).apply { textSize = 28f * scale.coerceAtLeast(1f) }
        val pNumber = Paint(numberPaint).apply { textSize = 36f * scale.coerceAtLeast(1f) }
        val pOuterBorder = Paint(outerBorderPaint).apply { strokeWidth = 5f }
        val pInnerLine = Paint(innerLinePaint).apply { strokeWidth = 2f }
        val pCircle = Paint(markedCirclePaint)
        val pCircleStroke = Paint(markedCircleStrokePaint).apply { strokeWidth = 4f }

        val headerText = if (bingoLines > 0) "카드 #${cardIndex + 1} - ${bingoLines}줄 빙고!"
        else "카드 #${cardIndex + 1}"
        canvas.drawText(headerText, printWidth / 2f, pHeaderHeight - 16f, pHeader)

        val gridTop = pHeaderHeight
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val idx = row * gridSize + col
                if (idx >= numbers.size) continue
                val num = numbers[idx]
                val cx = col * cellSize + cellSize / 2
                val cy = gridTop + row * cellSize + cellSize / 2
                if (num in markedNumbers) {
                    val radius = cellSize / 2 - 8f
                    canvas.drawCircle(cx, cy, radius, pCircle)
                    canvas.drawCircle(cx, cy, radius, pCircleStroke)
                }
                canvas.drawText(num.toString(), cx, cy + pNumber.textSize / 3, pNumber)
            }
        }
        for (i in 1 until gridSize) {
            val x = i * cellSize
            canvas.drawLine(x, gridTop, x, gridTop + gridSize * cellSize, pInnerLine)
            val y = gridTop + i * cellSize
            canvas.drawLine(0f, y, printWidth.toFloat(), y, pInnerLine)
        }
        canvas.drawRect(2f, gridTop + 2f, printWidth - 2f, gridTop + gridSize * cellSize - 2f, pOuterBorder)
        return bitmap
    }

    companion object {
        fun renderToBitmap(
            cardIndex: Int,
            numbers: List<Int>,
            gridSize: Int,
            markedNumbers: Set<Int>,
            bingoLines: Int = 0,
            completedLines: List<List<Int>> = emptyList(),
            font: Typeface? = null,
            printWidth: Int = DevicePrinter.PRINT_WIDTH_PX
        ): Bitmap {
            if (numbers.isEmpty()) return Bitmap.createBitmap(printWidth, 100, Bitmap.Config.ARGB_8888)

            val cellSize = printWidth.toFloat() / gridSize
            val pHeaderHeight = 56f
            val bmpHeight = (pHeaderHeight + cellSize * gridSize + 8).toInt()
            val bitmap = Bitmap.createBitmap(printWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val scale = printWidth.toFloat() / 400f
            val pHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 28f * scale.coerceAtLeast(1f)
                textAlign = Paint.Align.CENTER; isFakeBoldText = true; typeface = font
            }
            val pNumber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 36f * scale.coerceAtLeast(1f)
                textAlign = Paint.Align.CENTER; isFakeBoldText = true; typeface = font
            }
            val pOuterBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; strokeWidth = 5f; style = Paint.Style.STROKE
            }
            val pInnerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY; strokeWidth = 2f; style = Paint.Style.STROKE
            }
            val pCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#40F44336"); style = Paint.Style.FILL
            }
            val pCircleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED; strokeWidth = 4f; style = Paint.Style.STROKE
            }

            val headerText = if (bingoLines > 0) "카드 #${cardIndex + 1} - ${bingoLines}줄 빙고!"
            else "카드 #${cardIndex + 1}"
            canvas.drawText(headerText, printWidth / 2f, pHeaderHeight - 16f, pHeader)

            val gridTop = pHeaderHeight
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val idx = row * gridSize + col
                    if (idx >= numbers.size) continue
                    val num = numbers[idx]
                    val cx = col * cellSize + cellSize / 2
                    val cy = gridTop + row * cellSize + cellSize / 2
                    if (num in markedNumbers) {
                        val radius = cellSize / 2 - 8f
                        canvas.drawCircle(cx, cy, radius, pCircle)
                        canvas.drawCircle(cx, cy, radius, pCircleStroke)
                    }
                    canvas.drawText(num.toString(), cx, cy + pNumber.textSize / 3, pNumber)
                }
            }
            for (i in 1 until gridSize) {
                val x = i * cellSize
                canvas.drawLine(x, gridTop, x, gridTop + gridSize * cellSize, pInnerLine)
                val y = gridTop + i * cellSize
                canvas.drawLine(0f, y, printWidth.toFloat(), y, pInnerLine)
            }
            canvas.drawRect(2f, gridTop + 2f, printWidth - 2f, gridTop + gridSize * cellSize - 2f, pOuterBorder)

            // Strike-through lines for print
            val lineColors = intArrayOf(
                Color.parseColor("#E91E63"), Color.parseColor("#2196F3"),
                Color.parseColor("#4CAF50"), Color.parseColor("#FF9800"),
                Color.parseColor("#9C27B0"),
            )
            completedLines.forEachIndexed { lineIdx, cells ->
                if (cells.size < 2) return@forEachIndexed
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = lineColors[lineIdx % lineColors.size]
                    strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                }
                val first = cells.first(); val last = cells.last()
                val r1 = first / gridSize; val c1 = first % gridSize
                val r2 = last / gridSize; val c2 = last % gridSize
                val x1 = c1 * cellSize + cellSize / 2
                val y1 = gridTop + r1 * cellSize + cellSize / 2
                val x2 = c2 * cellSize + cellSize / 2
                val y2 = gridTop + r2 * cellSize + cellSize / 2
                val dx = x2 - x1; val dy = y2 - y1
                val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (len > 0) {
                    val ext = cellSize * 0.3f
                    val ex = dx / len * ext; val ey = dy / len * ext
                    canvas.drawLine(x1 - ex, y1 - ey, x2 + ex, y2 + ey, paint)
                }
            }

            return bitmap
        }
    }
}
