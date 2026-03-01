package com.betona.printdriver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Custom View that draws a ladder game (사다리 게임).
 * Supports path animation when a player name is tapped.
 */
class LadderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var generator: LadderGenerator? = null
    private var names: List<String> = emptyList()
    private var results: List<String> = emptyList()
    private var showResults: Boolean = true

    // Reveal state — tracks which destination rails have been uncovered
    private val revealedRails = mutableSetOf<Int>()

    // Animation state
    private var animPath: List<Pair<Int, Int>>? = null
    private var animProgress: Float = 0f
    private var animSelectedRail: Int = -1
    private var animator: ValueAnimator? = null

    var onPathComplete: ((startRail: Int, endRail: Int) -> Unit)? = null

    // Layout constants
    private val topMargin = 100f
    private val bottomMargin = 100f
    private val nameAreaHeight = 60f
    private val resultAreaHeight = 80f
    private val sideMargin = 40f

    // Paints
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val selectedNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val selectedResultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3F2FD")
        style = Paint.Style.FILL
    }

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val selectedCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCDD2")
        style = Paint.Style.FILL
    }

    private val selectedCircleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val bottomNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    fun setFont(typeface: Typeface) {
        namePaint.typeface = typeface
        resultPaint.typeface = typeface
        selectedNamePaint.typeface = typeface
        selectedResultPaint.typeface = typeface
        bottomNumberPaint.typeface = typeface
        invalidate()
    }

    fun setLadder(generator: LadderGenerator, names: List<String>, results: List<String>) {
        this.generator = generator
        this.names = names
        this.results = results
        this.animPath = null
        this.animSelectedRail = -1
        this.revealedRails.clear()
        animator?.cancel()

        // Calculate desired height
        val desiredHeight = (topMargin + nameAreaHeight + generator.stepCount * getStepHeight(generator) +
                resultAreaHeight + bottomMargin).toInt()
        layoutParams?.let {
            it.height = desiredHeight
            layoutParams = it
        }

        requestLayout()
        invalidate()
    }

    fun setShowResults(show: Boolean) {
        showResults = show
        invalidate()
    }

    /** Reveal a single destination rail's result */
    fun revealResult(destinationRail: Int) {
        revealedRails.add(destinationRail)
        invalidate()
    }

    /** Reveal all results at once */
    fun revealAll() {
        val gen = generator ?: return
        for (i in 0 until gen.playerCount) {
            revealedRails.add(i)
        }
        invalidate()
    }

    /** Reset revealed state (call when generating a new ladder) */
    fun resetRevealed() {
        revealedRails.clear()
        invalidate()
    }

    fun isAllRevealed(): Boolean {
        val gen = generator ?: return false
        return revealedRails.size >= gen.playerCount
    }

    private fun getStepHeight(gen: LadderGenerator): Float {
        return when {
            gen.stepCount <= 10 -> 40f
            gen.stepCount <= 20 -> 28f
            else -> 20f
        }
    }

    private fun getRailX(rail: Int): Float {
        val gen = generator ?: return 0f
        val usableWidth = width - sideMargin * 2
        return if (gen.playerCount == 1) {
            width / 2f
        } else {
            sideMargin + rail * usableWidth / (gen.playerCount - 1)
        }
    }

    private fun getStepY(step: Int): Float {
        val gen = generator ?: return 0f
        val ladderTop = topMargin + nameAreaHeight
        return ladderTop + step * getStepHeight(gen)
    }

    private fun getLadderBottom(): Float {
        val gen = generator ?: return 0f
        return getStepY(gen.stepCount)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val gen = generator
        if (gen == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (topMargin + nameAreaHeight + gen.stepCount * getStepHeight(gen) +
                resultAreaHeight + bottomMargin).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gen = generator ?: return
        canvas.drawColor(Color.WHITE)

        val ladderTop = topMargin + nameAreaHeight
        val ladderBottom = getLadderBottom()

        // Draw vertical rails
        for (rail in 0 until gen.playerCount) {
            val x = getRailX(rail)
            canvas.drawLine(x, ladderTop, x, ladderBottom, railPaint)
        }

        // Draw horizontal bridges
        for (step in 0 until gen.stepCount) {
            val y = getStepY(step) + getStepHeight(gen) / 2
            for (rail in 0 until gen.playerCount - 1) {
                if (gen.bridges[step][rail]) {
                    val x1 = getRailX(rail)
                    val x2 = getRailX(rail + 1)
                    canvas.drawLine(x1, y, x2, y, bridgePaint)
                }
            }
        }

        // Draw names at top
        val nameY = topMargin + nameAreaHeight / 2
        for (i in names.indices) {
            if (i >= gen.playerCount) break
            val x = getRailX(i)
            val isSelected = (i == animSelectedRail)
            val bgPaint = if (isSelected) selectedCirclePaint else circlePaint
            val strokePaint = if (isSelected) selectedCircleStrokePaint else circleStrokePaint
            val txtPaint = if (isSelected) selectedNamePaint else namePaint

            // Truncate name if needed
            val displayName = truncateText(names[i], txtPaint, getColumnWidth(gen) - 8f)

            val textWidth = txtPaint.measureText(displayName)
            val halfW = (textWidth / 2 + 12f).coerceAtLeast(24f)
            val halfH = 22f

            canvas.drawRoundRect(x - halfW, nameY - halfH, x + halfW, nameY + halfH, 12f, 12f, bgPaint)
            canvas.drawRoundRect(x - halfW, nameY - halfH, x + halfW, nameY + halfH, 12f, 12f, strokePaint)
            canvas.drawText(displayName, x, nameY + 10f, txtPaint)
        }

        // Draw bottom numbers
        val bottomNumberY = ladderBottom + 20f
        for (i in 0 until gen.playerCount) {
            val x = getRailX(i)
            canvas.drawText("${i + 1}", x, bottomNumberY, bottomNumberPaint)
        }

        // Draw results at bottom
        if (showResults) {
            val resultY = ladderBottom + resultAreaHeight / 2 + 12f
            for (i in results.indices) {
                if (i >= gen.playerCount) break
                val x = getRailX(i)
                val isRevealed = i in revealedRails
                val destRail = if (animSelectedRail >= 0 && animProgress >= 1f) {
                    gen.getDestination(animSelectedRail)
                } else -1
                val isSelected = (i == destRail && isRevealed)
                val bgPaint = if (isSelected) selectedCirclePaint else circlePaint
                val strokePaint = if (isSelected) selectedCircleStrokePaint else circleStrokePaint
                val txtPaint = if (isSelected) selectedResultPaint else resultPaint

                val displayText = if (isRevealed) {
                    truncateText(results[i], txtPaint, getColumnWidth(gen) - 8f)
                } else {
                    "?"
                }

                val textWidth = txtPaint.measureText(displayText)
                val halfW = (textWidth / 2 + 12f).coerceAtLeast(24f)
                val halfH = 20f

                canvas.drawRoundRect(x - halfW, resultY - halfH, x + halfW, resultY + halfH, 12f, 12f, bgPaint)
                canvas.drawRoundRect(x - halfW, resultY - halfH, x + halfW, resultY + halfH, 12f, 12f, strokePaint)
                canvas.drawText(displayText, x, resultY + 9f, txtPaint)
            }
        }

        // Draw animated path
        drawAnimatedPath(canvas, gen)
    }

    private fun getColumnWidth(gen: LadderGenerator): Float {
        val usableWidth = width - sideMargin * 2
        return if (gen.playerCount <= 1) usableWidth else usableWidth / (gen.playerCount - 1)
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0 || paint.measureText(text) <= maxWidth) return text
        for (len in text.length - 1 downTo 1) {
            val truncated = text.substring(0, len) + "…"
            if (paint.measureText(truncated) <= maxWidth) return truncated
        }
        return "…"
    }

    private fun drawAnimatedPath(canvas: Canvas, gen: LadderGenerator) {
        val path = animPath ?: return
        if (path.size < 2) return

        val drawPath = Path()
        var started = false
        val totalSegments = path.size - 1
        val segmentsToDraw = (animProgress * totalSegments).toInt()
        val partialProgress = (animProgress * totalSegments) - segmentsToDraw

        for (i in 0..minOf(segmentsToDraw, totalSegments - 1)) {
            val (step1, rail1) = path[i]
            val x1 = getRailX(rail1)
            val y1 = getYForPathPoint(step1, rail1, gen)

            if (!started) {
                drawPath.moveTo(x1, y1)
                started = true
            }

            if (i < segmentsToDraw && i + 1 < path.size) {
                val (step2, rail2) = path[i + 1]
                val x2 = getRailX(rail2)
                val y2 = getYForPathPoint(step2, rail2, gen)
                drawPath.lineTo(x2, y2)
            } else if (i == segmentsToDraw && i + 1 < path.size) {
                val (step2, rail2) = path[i + 1]
                val x2 = getRailX(rail2)
                val y2 = getYForPathPoint(step2, rail2, gen)
                val interpX = x1 + (x2 - x1) * partialProgress
                val interpY = y1 + (y2 - y1) * partialProgress
                drawPath.lineTo(interpX, interpY)

                // Draw current position dot
                canvas.drawCircle(interpX, interpY, 8f, dotPaint)
            }
        }

        canvas.drawPath(drawPath, pathPaint)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getYForPathPoint(step: Int, rail: Int, gen: LadderGenerator): Float {
        return when (step) {
            -1 -> topMargin + nameAreaHeight // top of ladder
            gen.stepCount -> getLadderBottom() // bottom of ladder
            else -> getStepY(step) + getStepHeight(gen) / 2
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val gen = generator ?: return false
            // Check if user tapped on a name
            val tapX = event.x
            val tapY = event.y
            val nameY = topMargin + nameAreaHeight / 2
            if (tapY in (nameY - 40f)..(nameY + 40f)) {
                for (i in 0 until gen.playerCount) {
                    val railX = getRailX(i)
                    val colWidth = getColumnWidth(gen)
                    if (tapX in (railX - colWidth / 2)..(railX + colWidth / 2)) {
                        animatePathForRail(i)
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    fun animatePathForRail(rail: Int) {
        val gen = generator ?: return
        if (rail < 0 || rail >= gen.playerCount) return

        animator?.cancel()
        animSelectedRail = rail
        animPath = gen.tracePath(rail)
        animProgress = 0f

        var pathCompleted = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                animProgress = animation.animatedValue as Float
                invalidate()
                if (animProgress >= 1f && !pathCompleted) {
                    pathCompleted = true
                    onPathComplete?.invoke(rail, gen.getDestination(rail))
                }
            }
            start()
        }
    }

    /**
     * Render the ladder to a Bitmap for printing.
     * @param includeResults whether to include results at the bottom
     * @param printWidth target width in pixels for thermal printer
     */
    fun toBitmap(includeResults: Boolean = true, printWidth: Int = DevicePrinter.PRINT_WIDTH_PX): Bitmap {
        val gen = generator ?: return Bitmap.createBitmap(printWidth, 100, Bitmap.Config.ARGB_8888)

        // Render at a size proportional to print width (guard width=0)
        val viewWidth = if (width > 0) width else printWidth
        val scale = printWidth.toFloat() / viewWidth
        val stepH = getStepHeight(gen) * scale
        val topM = topMargin * scale
        val bottomM = bottomMargin * scale
        val nameH = nameAreaHeight * scale
        val resultH = resultAreaHeight * scale
        val sideM = sideMargin * scale

        val bmpHeight = (topM + nameH + gen.stepCount * stepH +
                (if (includeResults) resultH + bottomM else bottomM / 2)).toInt()
        val bitmap = Bitmap.createBitmap(printWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // Scale paints for print
        val pRail = Paint(railPaint).apply { strokeWidth = 6f * scale.coerceAtLeast(1f) }
        val pBridge = Paint(bridgePaint).apply { strokeWidth = 6f * scale.coerceAtLeast(1f) }
        val pName = Paint(namePaint).apply { textSize = 28f * scale.coerceAtLeast(1f) }
        val pResult = Paint(resultPaint).apply { textSize = 24f * scale.coerceAtLeast(1f) }

        fun railX(rail: Int): Float {
            val usableW = printWidth - sideM * 2
            return if (gen.playerCount == 1) printWidth / 2f
            else sideM + rail * usableW / (gen.playerCount - 1)
        }

        fun stepY(step: Int): Float = topM + nameH + step * stepH

        val ladderTop = topM + nameH
        val ladderBottom = stepY(gen.stepCount)

        // Vertical rails
        for (rail in 0 until gen.playerCount) {
            canvas.drawLine(railX(rail), ladderTop, railX(rail), ladderBottom, pRail)
        }

        // Horizontal bridges
        for (step in 0 until gen.stepCount) {
            val y = stepY(step) + stepH / 2
            for (rail in 0 until gen.playerCount - 1) {
                if (gen.bridges[step][rail]) {
                    canvas.drawLine(railX(rail), y, railX(rail + 1), y, pBridge)
                }
            }
        }

        // Names at top
        val nameYPos = topM + nameH / 2 + 10f * scale
        for (i in names.indices) {
            if (i >= gen.playerCount) break
            canvas.drawText(names[i], railX(i), nameYPos, pName)
        }

        // Bottom numbers
        val pBottomNum = Paint(bottomNumberPaint).apply { textSize = 18f * scale.coerceAtLeast(1f) }
        val bottomNumYPos = ladderBottom + 18f * scale
        for (i in 0 until gen.playerCount) {
            canvas.drawText("${i + 1}", railX(i), bottomNumYPos, pBottomNum)
        }

        // Results at bottom
        if (includeResults) {
            val resultYPos = ladderBottom + resultH / 2 + 16f * scale
            for (i in results.indices) {
                if (i >= gen.playerCount) break
                canvas.drawText(results[i], railX(i), resultYPos, pResult)
            }
        }

        return bitmap
    }

    /**
     * Create a fold-line separator bitmap for "fold here" printing.
     */
    fun createFoldLineBitmap(printWidth: Int = DevicePrinter.PRINT_WIDTH_PX): Bitmap {
        val height = 60
        val bitmap = Bitmap.createBitmap(printWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 20f
            textAlign = Paint.Align.CENTER
            typeface = namePaint.typeface
        }

        // Dashed line
        canvas.drawLine(20f, height / 2f, printWidth - 20f, height / 2f, dashPaint)

        // "여기를 접으세요" text
        val textBg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val text = "▼ 여기를 접으세요 ▼"
        val tw = textPaint.measureText(text)
        canvas.drawRect(printWidth / 2f - tw / 2 - 8, 8f, printWidth / 2f + tw / 2 + 8, height - 8f, textBg)
        canvas.drawText(text, printWidth / 2f, height / 2f + 7f, textPaint)

        return bitmap
    }
}
