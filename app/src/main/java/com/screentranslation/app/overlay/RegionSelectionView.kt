package com.screentranslation.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.screentranslation.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen drag selector used only while the overlay window is in selection mode.
 *
 * Coordinates reported by [onRegionSelected] are physical overlay-window pixels.
 * OverlayController positions the window at display origin, so these coordinates
 * map directly to the default-display MediaProjection frame.
 */
class RegionSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onRegionSelected: ((Rect) -> Unit)? = null

    /**
     * Invoked when a gesture ended without producing a usable region, including
     * a plain tap. The selector covers the whole display and swallows every
     * touch, so silently ignoring these leaves the user with no idea why the
     * screen stopped responding.
     */
    var onSelectionRejected: (() -> Unit)? = null

    val region: Rect?
        get() = selectedRegion?.let(::Rect)

    private val density = resources.displayMetrics.density
    private val minSelectionSizePx = dp(64f)
    private val handleRadiusPx = dp(5f)

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(59, 130, 246)
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(16f)
        setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isSelecting = false
    private var selectedRegion: Rect? = null

    init {
        isClickable = true
        contentDescription = context.getString(R.string.overlay_region_selector_description)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun startSelection() {
        selectedRegion = null
        isSelecting = false
        invalidate()
    }

    fun setRegion(region: Rect?) {
        selectedRegion = region?.let(::Rect)
        clampSelectionToBounds()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clampSelectionToBounds()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val selection = currentSelectionRect()

        if (selection == null || selection.isEmpty) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        } else {
            val left = selection.left.toFloat()
            val top = selection.top.toFloat()
            val right = selection.right.toFloat()
            val bottom = selection.bottom.toFloat()

            canvas.drawRect(0f, 0f, width.toFloat(), top, shadePaint)
            canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), shadePaint)
            canvas.drawRect(0f, top, left, bottom, shadePaint)
            canvas.drawRect(right, top, width.toFloat(), bottom, shadePaint)
            canvas.drawRect(left, top, right, bottom, borderPaint)

            canvas.drawCircle(left, top, handleRadiusPx, handlePaint)
            canvas.drawCircle(right, top, handleRadiusPx, handlePaint)
            canvas.drawCircle(left, bottom, handleRadiusPx, handlePaint)
            canvas.drawCircle(right, bottom, handleRadiusPx, handlePaint)
        }

        val instruction = context.getString(R.string.overlay_drag_instruction)
        val baseline = dp(44f) - (instructionPaint.ascent() + instructionPaint.descent()) / 2f
        canvas.drawText(instruction, width / 2f, baseline, instructionPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = x
                startY = y
                currentX = x
                currentY = y
                selectedRegion = null
                isSelecting = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isSelecting) return false
                currentX = x
                currentY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isSelecting) return false
                currentX = x
                currentY = y
                isSelecting = false
                parent?.requestDisallowInterceptTouchEvent(false)

                val result = normalizedRect(startX, startY, currentX, currentY)
                selectedRegion = if (
                    result.width() >= minSelectionSizePx &&
                    result.height() >= minSelectionSizePx
                ) {
                    result
                } else {
                    null
                }
                invalidate()
                performClick()
                val selection = selectedRegion
                if (selection != null) {
                    onRegionSelected?.invoke(Rect(selection))
                } else {
                    onSelectionRejected?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isSelecting = false
                parent?.requestDisallowInterceptTouchEvent(false)
                selectedRegion = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun currentSelectionRect(): Rect? {
        return when {
            isSelecting -> normalizedRect(startX, startY, currentX, currentY)
            else -> selectedRegion
        }
    }

    private fun normalizedRect(x1: Float, y1: Float, x2: Float, y2: Float): Rect {
        return Rect(
            min(x1, x2).toInt().coerceIn(0, width),
            min(y1, y2).toInt().coerceIn(0, height),
            max(x1, x2).toInt().coerceIn(0, width),
            max(y1, y2).toInt().coerceIn(0, height),
        )
    }

    private fun clampSelectionToBounds() {
        if (width <= 0 || height <= 0) return
        selectedRegion = selectedRegion?.let {
            Rect(
                it.left.coerceIn(0, width),
                it.top.coerceIn(0, height),
                it.right.coerceIn(0, width),
                it.bottom.coerceIn(0, height),
            )
        }?.takeUnless { it.isEmpty }
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
