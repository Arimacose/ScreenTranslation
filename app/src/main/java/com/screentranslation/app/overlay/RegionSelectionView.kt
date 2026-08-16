package com.screentranslation.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.screentranslation.app.R
import kotlin.math.max
import kotlin.math.min

internal const val MIN_SELECTION_SIZE_DP = 32f

internal fun isSelectionSizeAccepted(widthPx: Int, heightPx: Int, minSizePx: Float): Boolean =
    widthPx >= minSizePx && heightPx >= minSizePx

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
    private val visualStyle = resolveOverlayVisualStyle(context)
    private val minSelectionSizePx = dp(MIN_SELECTION_SIZE_DP)
    private val handleOuterRadiusPx = dp(7f)
    private val handleInnerRadiusPx = dp(4f)

    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.selectionFillColor
        style = Paint.Style.FILL
    }
    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.labelTextColor
        strokeWidth = dp(6f)
        style = Paint.Style.STROKE
    }
    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.accentColor
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
    }
    private val handleOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.labelTextColor
        style = Paint.Style.FILL
    }
    private val handleInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.accentColor
        style = Paint.Style.FILL
    }
    private val instructionBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.instructionColor
        style = Paint.Style.FILL
    }
    private val instructionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visualStyle.labelTextColor
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isSelecting = false
    private var selectedRegion: Rect? = null
    private var gestureExclusionEnabled = false

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

    fun setGestureExclusionEnabled(enabled: Boolean) {
        gestureExclusionEnabled = enabled
        updateGestureExclusionRects()
    }

    fun setRegion(region: Rect?) {
        selectedRegion = region?.let(::Rect)
        clampSelectionToBounds()
        updateGestureExclusionRects()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clampSelectionToBounds()
        if (visibility == VISIBLE) setGestureExclusionEnabled(true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val selection = currentSelectionRect()

        if (selection != null && !selection.isEmpty) {
            val left = selection.left.toFloat()
            val top = selection.top.toFloat()
            val right = selection.right.toFloat()
            val bottom = selection.bottom.toFloat()

            canvas.drawRect(left, top, right, bottom, selectionFillPaint)
            canvas.drawRect(left, top, right, bottom, outerBorderPaint)
            canvas.drawRect(left, top, right, bottom, innerBorderPaint)

            listOf(left to top, right to top, left to bottom, right to bottom).forEach { point ->
                canvas.drawCircle(point.first, point.second, handleOuterRadiusPx, handleOuterPaint)
                canvas.drawCircle(point.first, point.second, handleInnerRadiusPx, handleInnerPaint)
            }
        }

        val instruction = context.getString(R.string.overlay_drag_instruction)
        val horizontalPadding = dp(14f)
        val pillCenterY = dp(50f)
        val pillHalfHeight = dp(18f)
        val pillHalfWidth = instructionPaint.measureText(instruction) / 2f + horizontalPadding
        canvas.drawRoundRect(
            width / 2f - pillHalfWidth,
            pillCenterY - pillHalfHeight,
            width / 2f + pillHalfWidth,
            pillCenterY + pillHalfHeight,
            pillHalfHeight,
            pillHalfHeight,
            instructionBackgroundPaint,
        )
        val baseline = pillCenterY - (instructionPaint.ascent() + instructionPaint.descent()) / 2f
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
                updateGestureExclusionRects()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isSelecting) return false
                currentX = x
                currentY = y
                updateGestureExclusionRects()
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
                    isSelectionSizeAccepted(result.width(), result.height(), minSelectionSizePx)
                ) {
                    result
                } else {
                    null
                }
                updateGestureExclusionRects()
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
                updateGestureExclusionRects()
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

    private fun updateGestureExclusionRects() {
        if (!gestureExclusionEnabled || width <= 0 || height <= 0) {
            systemGestureExclusionRects = emptyList()
            return
        }
        val padding = dp(12f).toInt()
        val instruction = instructionBounds().apply { inset(-padding, -padding) }
        val active = currentSelectionRect()?.let { selection ->
            Rect(
                (selection.left - padding).coerceIn(0, width),
                (selection.top - padding).coerceIn(0, height),
                (selection.right + padding).coerceIn(0, width),
                (selection.bottom + padding).coerceIn(0, height),
            )
        }
        systemGestureExclusionRects = listOfNotNull(instruction, active)
            .filter { !it.isEmpty }
    }

    private fun instructionBounds(): Rect {
        val instruction = context.getString(R.string.overlay_drag_instruction)
        val horizontalPadding = dp(14f)
        val pillCenterY = dp(50f)
        val pillHalfHeight = dp(18f)
        val pillHalfWidth = instructionPaint.measureText(instruction) / 2f + horizontalPadding
        return Rect(
            (width / 2f - pillHalfWidth).toInt().coerceIn(0, width),
            (pillCenterY - pillHalfHeight).toInt().coerceIn(0, height),
            (width / 2f + pillHalfWidth).toInt().coerceIn(0, width),
            (pillCenterY + pillHalfHeight).toInt().coerceIn(0, height),
        )
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
