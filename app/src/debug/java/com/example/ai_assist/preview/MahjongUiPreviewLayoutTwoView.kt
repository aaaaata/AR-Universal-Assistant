package com.example.ai_assist.preview

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
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.ai_assist.scene.mahjong.R as MahjongR
import kotlin.math.min

/** Static single-eye layout matching the 376 x 282 second layout reference. */
class MahjongUiPreviewLayoutTwoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val green = Color.rgb(24, 163, 45)
    private val statusGreen = Color.argb(204, 24, 163, 45)
    private val warningYellow = Color.rgb(246, 255, 0)
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
    private val tileTypeface = runCatching { resources.getFont(MahjongR.font.mahjong_color) }.getOrNull()
    private val tileCache = mutableMapOf<String, Bitmap?>()

    private val discardTiles = listOf(tile(0x1F007), tile(0x1F008), tile(0x1F009), tile(0x1F00A))
    private val handTiles = listOf(
        tile(0x1F007), tile(0x1F008), tile(0x1F009),
        tile(0x1F00A), tile(0x1F00B), tile(0x1F00C),
        tile(0x1F00D), tile(0x1F019), tile(0x1F01A), tile(0x1F01B)
    )
    private val jinzhangTiles = listOf(
        tile(0x1F012), tile(0x1F015), tile(0x1F000),
        tile(0x1F01D), tile(0x1F01E), tile(0x1F01F)
    )
    private val pengTiles = listOf(tile(0x1F015), tile(0x1F000), tile(0x1F01D))
    private val gangTiles = listOf(tile(0x1F000))
    private var recommendationArrowTop = ARROW_TOP_MIN
    private val recommendationArrowAnimator = ValueAnimator.ofFloat(ARROW_TOP_MIN, ARROW_TOP_MAX).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            recommendationArrowTop = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        recommendationArrowAnimator.start()
    }

    override fun onDetachedFromWindow() {
        recommendationArrowAnimator.cancel()
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

        drawWarningMark(canvas, 20f, 24f, 3f, green)
        drawText(canvas, "强建议", 25f, 27f, 7f, green)
        discardTiles.forEachIndexed { index, tile -> drawTile(canvas, 17f + index * 19f, 32f, tile) }
        drawRecommendationArrow(canvas, 82f, recommendationArrowTop)
        val discardLabelBaseline = 70f + recommendationArrowTop - ARROW_TOP_MAX
        drawText(canvas, "出牌", 82f, discardLabelBaseline, 7f, green, Paint.Align.CENTER)

        drawMeldFrame(canvas)
        handTiles.take(3).forEachIndexed { index, tile -> drawTile(canvas, 137f + index * 19f, 32f, tile) }
        handTiles.drop(3).take(3).forEachIndexed { index, tile -> drawTile(canvas, 202f + index * 19f, 32f, tile) }
        handTiles.drop(6).forEachIndexed { index, tile -> drawTile(canvas, 268f + index * 19f, 32f, tile) }
        drawText(canvas, "副", 353f, 42f, MELD_LABEL_SIZE, green, Paint.Align.CENTER)
        drawText(canvas, "露", 353f, 56f, MELD_LABEL_SIZE, green, Paint.Align.CENTER)

        drawDivider(canvas, 17f, 72f, 157f)
        drawDivider(canvas, 219f, 72f, 360f)
        drawText(canvas, "操作建议", 188f, 76f, 10f, green, Paint.Align.CENTER)
        drawText(canvas, "向听数：2", 360f, 94f, SHANTEN_SIZE, green, Paint.Align.RIGHT)
        drawVerticalLabel(canvas, "进张", 17f, 96f)
        jinzhangTiles.take(5).forEachIndexed { index, tile -> drawTile(canvas, 29f + index * 22f, 84f, tile) }
        drawVerticalLabel(canvas, "可碰", 17f, 123f)
        pengTiles.forEachIndexed { index, tile -> drawTile(canvas, 29f + index * 22f, 111f, tile) }
        drawVerticalLabel(canvas, "可杠", 17f, 150f)
        gangTiles.forEachIndexed { index, tile -> drawTile(canvas, 29f + index * 22f, 138f, tile) }

        drawWarningChip(canvas)
        drawText(canvas, "本地服务运行中 · 三击结束", 360f, 265f, STATUS_SIZE, statusGreen, Paint.Align.RIGHT)
    }

    private fun drawDivider(canvas: Canvas, left: Float, y: Float, right: Float) {
        linePaint.color = green
        linePaint.strokeWidth = 1f
        canvas.drawLine(left, y, right, y, linePaint)
    }

    private fun drawMeldFrame(canvas: Canvas) {
        linePaint.color = green
        linePaint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(131f, 28f, 361f, 61f), 6f, 6f, linePaint)
    }

    private fun drawVerticalLabel(canvas: Canvas, label: String, x: Float, firstBaseline: Float) {
        label.forEachIndexed { index, character ->
            drawText(canvas, character.toString(), x, firstBaseline + index * VERTICAL_LABEL_LINE_HEIGHT, ACTION_LABEL_SIZE, green)
        }
    }

    private fun drawWarningChip(canvas: Canvas) {
        val chip = RectF(17f, 251f, 95f, 267f)
        fillPaint.color = Color.argb(82, Color.red(warningYellow), Color.green(warningYellow), Color.blue(warningYellow))
        canvas.drawRoundRect(chip, 8f, 8f, fillPaint)
        linePaint.color = warningYellow
        linePaint.strokeWidth = 1f
        canvas.drawRoundRect(chip, 8f, 8f, linePaint)

        drawWarningMark(canvas, 28f, 259f, 4f, warningInk)
        drawText(canvas, "画面太暗", 36f, 263f, WARNING_LABEL_SIZE, warningInk)
    }

    private fun drawTile(canvas: Canvas, x: Float, y: Float, tile: String) {
        val bitmap = tileCache.getOrPut(tile) { createColorTile(tile) } ?: return
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + TILE_WIDTH, y + TILE_HEIGHT), bitmapPaint)
    }

    private fun createColorTile(tile: String): Bitmap? {
        val typeface = tileTypeface ?: return null
        val bitmap = Bitmap.createBitmap(GLYPH_WIDTH, GLYPH_HEIGHT, Bitmap.Config.ARGB_8888)
        val glyphCanvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 128f
            textAlign = Paint.Align.LEFT
        }
        val bounds = Rect()
        paint.getTextBounds(tile, 0, tile.length, bounds)
        val drawX = GLYPH_WIDTH / 2f - (bounds.left + bounds.right) / 2f
        val baseline = GLYPH_HEIGHT / 2f - (bounds.top + bounds.bottom) / 2f
        glyphCanvas.drawText(tile, drawX, baseline, paint)
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
        drawText(canvas, "!", centerX, centerY + radius * 0.7f, radius * 1.35f, warningInk, Paint.Align.CENTER)
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

    private fun tile(codePoint: Int): String = String(Character.toChars(codePoint))

    private companion object {
        const val BASE_WIDTH = 376f
        const val BASE_HEIGHT = 282f
        const val TILE_WIDTH = 18f
        const val TILE_HEIGHT = 24f
        const val GLYPH_WIDTH = 120
        const val GLYPH_HEIGHT = 160
        const val MELD_LABEL_SIZE = 11f
        const val ACTION_LABEL_SIZE = 11f
        const val SHANTEN_SIZE = 11f
        const val WARNING_LABEL_SIZE = 10f
        const val STATUS_SIZE = 12f
        const val VERTICAL_LABEL_LINE_HEIGHT = 12f
        const val ARROW_TOP_MIN = 56.5f
        const val ARROW_TOP_MAX = 58.5f
    }
}
