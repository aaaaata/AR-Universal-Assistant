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

/** Static single-eye layout prototype derived from the approved 376 x 282 reference. */
class MahjongUiPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val green = Color.rgb(24, 163, 45)
    private val warningYellow = Color.rgb(246, 255, 0)
    private val warningInk = Color.rgb(30, 30, 30)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tileTypeface = runCatching {
        resources.getFont(MahjongR.font.mahjong_color)
    }.getOrNull()
    private val tileCache = mutableMapOf<String, Bitmap?>()

    private val handTiles = listOf(
        tile(0x1F007), tile(0x1F008), tile(0x1F009), tile(0x1F00A), tile(0x1F00B),
        tile(0x1F00C), tile(0x1F00D), tile(0x1F019), tile(0x1F01A), tile(0x1F01B)
    )
    private val meldTiles = listOf(tile(0x1F01D), tile(0x1F01D), tile(0x1F01D), tile(0x1F01D))
    private val primaryDraws = listOf(tile(0x1F00A), tile(0x1F00D), tile(0x1F01C))
    private val secondaryDraws = listOf(tile(0x1F012), tile(0x1F015), tile(0x1F000))
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
        val offsetX = (width - BASE_WIDTH * scale) / 2f
        val offsetY = (height - BASE_HEIGHT * scale) / 2f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        drawLayout(canvas)
        canvas.restore()
    }

    private fun drawLayout(canvas: Canvas) {
        val handLabel = "手牌区：出牌"
        drawText(canvas, handLabel, 29f, 46f, 21f, green)
        val confidenceLeft = 29f + measureText(handLabel, 21f) + 10f
        drawWarningMark(canvas, confidenceLeft + 6f, 37f, 6f, warningYellow)
        drawText(canvas, "低置信度", confidenceLeft + 18f, 46f, 21f, warningYellow)

        val handY = 60f
        handTiles.forEachIndexed { index, tile ->
            drawTile(canvas, CONTENT_LEFT + index * TILE_WIDTH, handY, tile, TILE_WIDTH, TILE_HEIGHT)
        }
        drawRecommendationArrow(canvas, CONTENT_LEFT + 3 * TILE_WIDTH + TILE_WIDTH / 2f, recommendationArrowTop)
        drawDivider(canvas, CONTENT_LEFT, 131f, CONTENT_RIGHT)

        drawText(canvas, "副露区", 29f, 162f, 21f, green)
        meldTiles.forEachIndexed { index, tile ->
            drawTile(canvas, CONTENT_LEFT + index * TILE_WIDTH, 175f, tile, TILE_WIDTH, TILE_HEIGHT)
        }
        drawDivider(canvas, CONTENT_LEFT, 240f, CONTENT_RIGHT)

        drawText(canvas, "操作建议", 29f, 272f, 21f, green)
        drawText(canvas, "向听数：2", CONTENT_RIGHT, 272f, 21f, green, Paint.Align.RIGHT)
        drawText(canvas, "碰", 29f, 323f, 21f, green)
        primaryDraws.forEachIndexed { index, tile ->
            drawTile(canvas, 61f + index * 52f, 286f, tile, TILE_WIDTH, TILE_HEIGHT)
        }
        drawText(canvas, "杠", 29f, 383f, 21f, green)
        secondaryDraws.forEachIndexed { index, tile ->
            drawTile(canvas, 61f + index * 52f, 347f, tile, TILE_WIDTH, TILE_HEIGHT)
        }
        drawDivider(canvas, CONTENT_LEFT, 412f, CONTENT_RIGHT)

        drawWarningChip(canvas)
        drawText(canvas, "本地服务运行中 · 三击结束", 615f, 449f, 20f, green, Paint.Align.RIGHT)
    }

    private fun drawDivider(canvas: Canvas, left: Float, y: Float, right: Float) {
        linePaint.strokeWidth = 1.2f
        canvas.drawLine(left, y, right, y, linePaint)
    }

    private fun drawWarningChip(canvas: Canvas) {
        val chip = RectF(29f, 429f, 162f, 460f)
        fillPaint.color = Color.argb(82, Color.red(warningYellow), Color.green(warningYellow), Color.blue(warningYellow))
        canvas.drawRoundRect(chip, 15.5f, 15.5f, fillPaint)
        linePaint.color = warningYellow
        linePaint.strokeWidth = 1.2f
        canvas.drawRoundRect(chip, 15.5f, 15.5f, linePaint)

        textPaint.textSize = 18f
        val label = "画面太暗"
        val iconWidth = 12f
        val gap = 6f
        val contentWidth = iconWidth + gap + textPaint.measureText(label)
        val contentLeft = chip.centerX() - contentWidth / 2f
        drawWarningMark(canvas, contentLeft + iconWidth / 2f, chip.centerY(), 6f, warningInk)
        drawText(canvas, label, contentLeft + iconWidth + gap, 450f, 18f, warningInk)
    }

    private fun drawTile(
        canvas: Canvas,
        x: Float,
        y: Float,
        tile: String,
        tileWidth: Float,
        tileHeight: Float
    ) {
        val bitmap = tileCache.getOrPut(tile) { createColorTile(tile) } ?: return
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + tileWidth, y + tileHeight), bitmapPaint)
    }

    /** Renders the existing color bitmap font directly; no synthetic outer frame is added. */
    private fun createColorTile(tile: String): Bitmap? {
        val typeface = tileTypeface ?: return null
        val bitmap = Bitmap.createBitmap(GLYPH_WIDTH, GLYPH_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 128f
            textAlign = Paint.Align.LEFT
        }
        val bounds = Rect()
        paint.getTextBounds(tile, 0, tile.length, bounds)
        val drawX = GLYPH_WIDTH / 2f - (bounds.left + bounds.right) / 2f
        val baseline = GLYPH_HEIGHT / 2f - (bounds.top + bounds.bottom) / 2f
        canvas.drawText(tile, drawX, baseline, paint)
        return bitmap
    }

    private fun drawRecommendationArrow(canvas: Canvas, centerX: Float, top: Float) {
        val path = Path().apply {
            moveTo(centerX, top)
            lineTo(centerX - 5f, top + 8f)
            lineTo(centerX + 5f, top + 8f)
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
        drawText(canvas, "!", centerX, centerY + radius * 0.72f, radius * 1.35f, warningInk, Paint.Align.CENTER)
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

    private fun measureText(text: String, size: Float): Float {
        textPaint.textSize = size
        return textPaint.measureText(text)
    }

    private fun tile(codePoint: Int): String = String(Character.toChars(codePoint))

    private companion object {
        const val BASE_WIDTH = 640f
        const val BASE_HEIGHT = 480f
        const val CONTENT_LEFT = 29f
        const val CONTENT_RIGHT = 616f
        const val TILE_WIDTH = (CONTENT_RIGHT - CONTENT_LEFT) / 14f
        const val TILE_HEIGHT = TILE_WIDTH * 4f / 3f
        const val GLYPH_WIDTH = 120
        const val GLYPH_HEIGHT = 160
        const val ARROW_TOP_MIN = 118f
        const val ARROW_TOP_MAX = 122f
    }
}
