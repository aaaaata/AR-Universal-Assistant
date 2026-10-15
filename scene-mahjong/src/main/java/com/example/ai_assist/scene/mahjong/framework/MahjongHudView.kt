package com.example.ai_assist.scene.mahjong.framework

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.ai_assist.scene.mahjong.R
import kotlin.math.min

/** 原版 376 x 282 单眼麻将 HUD。 */
class MahjongHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val green = Color.rgb(24, 163, 45)
    private val statusGreen = Color.argb(204, 24, 163, 45)
    private val warningYellow = Color.rgb(246, 255, 0)
    private val errorRed = Color.rgb(255, 48, 48)
    private val warningInk = Color.BLACK
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tileTypeface = runCatching { resources.getFont(R.font.mahjong_color) }.getOrNull()
    private val tileCache = mutableMapOf<String, Bitmap?>()
    private var payload = MahjongScenePayload(emptyList(), emptyList(), "")
    private var diagnosticMessage: String? = null
    private var notice: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideNotice = Runnable {
        notice = null
        invalidate()
    }
    private var recommendationArrowTop = ARROW_TOP_MIN
    private val recommendationArrowAnimator = ValueAnimator.ofFloat(
        ARROW_TOP_MIN,
        ARROW_TOP_MAX
    ).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            recommendationArrowTop = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    fun render(payload: MahjongScenePayload) {
        this.payload = payload
        invalidate()
    }

    fun renderDiagnosticWarning(message: String?) {
        diagnosticMessage = message
        invalidate()
    }

    fun renderNotice(message: String?) {
        handler.removeCallbacks(hideNotice)
        notice = message
        if (message != null) handler.postDelayed(hideNotice, NOTICE_DURATION_MILLIS)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        recommendationArrowAnimator.start()
    }

    override fun onDetachedFromWindow() {
        recommendationArrowAnimator.cancel()
        handler.removeCallbacks(hideNotice)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val scale = min(width / BASE_WIDTH, height / BASE_HEIGHT)
        canvas.save()
        canvas.translate((width - BASE_WIDTH * scale) / 2f, (height - BASE_HEIGHT * scale) / 2f)
        canvas.scale(scale, scale)
        drawLayout(canvas)
        canvas.restore()
    }

    private fun drawLayout(canvas: Canvas) {
        drawDivider(canvas, 17f, 16f, 157f)
        drawDivider(canvas, 219f, 16f, 360f)
        drawText(canvas, "手牌区", 188f, 19f, 10f, green, Paint.Align.CENTER)

        payload.userHand.forEachIndexed { index, tile ->
            drawTile(canvas, HAND_LEFT + index * TILE_STEP, 32f, tile)
        }
        if (!payload.canHu) {
            val discardIndex = payload.suggestedDiscard?.let(payload.userHand::indexOf) ?: -1
            if (discardIndex >= 0) {
                val strengthColor = if (
                    payload.recommendationStrength == RecommendationStrength.WEAK
                ) warningYellow else green
                drawWarningMark(canvas, 20f, 24f, 3f, strengthColor)
                drawText(
                    canvas,
                    payload.recommendationStrength.label,
                    25f,
                    27f,
                    7f,
                    strengthColor
                )
                val centerX = HAND_LEFT + discardIndex * TILE_STEP + TILE_WIDTH / 2f
                drawRecommendationArrow(canvas, centerX, recommendationArrowTop)
                drawText(
                    canvas,
                    "出牌",
                    centerX,
                    70f + recommendationArrowTop - ARROW_TOP_MAX,
                    7f,
                    green,
                    Paint.Align.CENTER
                )
            }
        }

        drawMeldFrame(canvas, payload.meldedTiles)
        diagnosticMessage?.let { drawDiagnostic(canvas, it) }
        drawDivider(canvas, 17f, ADVICE_DIVIDER_Y, 157f)
        drawDivider(canvas, 219f, ADVICE_DIVIDER_Y, 360f)
        drawText(canvas, "操作建议", 188f, 86f, 10f, green, Paint.Align.CENTER)
        if (payload.canHu) {
            drawText(canvas, "可胡牌", 188f, 128f, 20f, green, Paint.Align.CENTER)
        } else {
            drawText(
                canvas,
                "向听数：${payload.shanten?.toString() ?: "-"}",
                360f,
                104f,
                11f,
                green,
                Paint.Align.RIGHT
            )
            drawVerticalLabel(canvas, "进张", 17f, 106f)
            payload.jinzhangTiles.take(5).forEachIndexed { index, tile ->
                drawTile(canvas, 29f + index * 22f, 94f, tile)
            }
            drawVerticalLabel(canvas, "可碰", 17f, 133f)
            payload.pengTiles.forEachIndexed { index, tile ->
                drawTile(canvas, 29f + index * 22f, 121f, tile)
            }
            drawVerticalLabel(canvas, "可杠", 17f, 160f)
            payload.gangTiles.forEachIndexed { index, tile ->
                drawTile(canvas, 29f + index * 22f, 148f, tile)
            }
        }

        notice?.let { drawNoticeChip(canvas, it) }
        val service = if (payload.serviceMode == MahjongServiceMode.LOCAL) "本地" else "云端"
        drawText(
            canvas,
            "${service}服务运行中 · 三击结束",
            360f,
            265f,
            12f,
            statusGreen,
            Paint.Align.RIGHT
        )
    }

    private fun drawMeldFrame(canvas: Canvas, meldTiles: List<String>) {
        val frameWidth = if (meldTiles.isEmpty()) 18f else meldTiles.size * TILE_STEP + 22f
        val left = 361f - frameWidth
        linePaint.color = green
        canvas.drawRoundRect(RectF(left, 28f, 361f, 61f), 6f, 6f, linePaint)
        meldTiles.forEachIndexed { index, tile ->
            drawTile(canvas, left + 3f + index * TILE_STEP, 32f, tile)
        }
        drawText(canvas, "副", 353f, 42f, 11f, green, Paint.Align.CENTER)
        drawText(canvas, "露", 353f, 56f, 11f, green, Paint.Align.CENTER)
    }

    private fun drawDivider(canvas: Canvas, left: Float, y: Float, right: Float) {
        linePaint.color = green
        canvas.drawLine(left, y, right, y, linePaint)
    }

    private fun drawVerticalLabel(canvas: Canvas, label: String, x: Float, firstBaseline: Float) {
        label.forEachIndexed { index, character ->
            drawText(canvas, character.toString(), x, firstBaseline + index * 12f, 11f, green)
        }
    }

    private fun drawDiagnostic(canvas: Canvas, message: String) {
        drawWarningMark(canvas, 20f, 76f, 3f, errorRed)
        drawText(canvas, message, 25f, 79f, 7f, errorRed)
    }

    private fun drawNoticeChip(canvas: Canvas, label: String) {
        textPaint.textSize = 10f
        val chipWidth = (28f + textPaint.measureText(label)).coerceAtLeast(78f)
        val chip = RectF(17f, 251f, 17f + chipWidth, 267f)
        fillPaint.color = Color.argb(
            82,
            Color.red(warningYellow),
            Color.green(warningYellow),
            Color.blue(warningYellow)
        )
        canvas.drawRoundRect(chip, 8f, 8f, fillPaint)
        linePaint.color = warningYellow
        canvas.drawRoundRect(chip, 8f, 8f, linePaint)
        drawWarningMark(canvas, 28f, 259f, 4f, warningYellow)
        drawText(canvas, label, 36f, 263f, 10f, warningYellow)
    }

    private fun drawTile(canvas: Canvas, x: Float, y: Float, tile: String) {
        val bitmap = tileCache.getOrPut(tile) { createColorTile(tile) } ?: return
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + TILE_WIDTH, y + TILE_HEIGHT), bitmapPaint)
    }

    private fun createColorTile(tile: String): Bitmap? {
        val typeface = tileTypeface ?: return null
        val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        val glyphCanvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 128f
        }
        val bounds = Rect()
        paint.getTextBounds(tile, 0, tile.length, bounds)
        glyphCanvas.drawText(
            tile,
            60f - (bounds.left + bounds.right) / 2f,
            80f - (bounds.top + bounds.bottom) / 2f,
            paint
        )
        return bitmap
    }

    private fun drawRecommendationArrow(canvas: Canvas, centerX: Float, top: Float) {
        val path = Path().apply {
            moveTo(centerX, top)
            lineTo(centerX - 3.5f, top + 4.5f)
            lineTo(centerX + 3.5f, top + 4.5f)
            close()
        }
        fillPaint.color = green
        canvas.drawPath(path, fillPaint)
    }

    private fun drawWarningMark(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        color: Int
    ) {
        val path = Path().apply {
            moveTo(centerX, centerY - radius)
            lineTo(centerX - radius, centerY + radius)
            lineTo(centerX + radius, centerY + radius)
            close()
        }
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
        drawText(
            canvas,
            "!",
            centerX,
            centerY + radius * 0.7f,
            radius * 1.35f,
            warningInk,
            Paint.Align.CENTER
        )
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        canvas.drawText(text, x, baseline, textPaint)
    }

    private companion object {
        const val BASE_WIDTH = 376f
        const val BASE_HEIGHT = 282f
        const val HAND_LEFT = 17f
        const val TILE_WIDTH = 18f
        const val TILE_HEIGHT = 24f
        const val TILE_STEP = 19f
        const val ARROW_TOP_MIN = 56.5f
        const val ARROW_TOP_MAX = 58.5f
        const val ADVICE_DIVIDER_Y = 82f
        const val NOTICE_DURATION_MILLIS = 3_000L
    }
}
