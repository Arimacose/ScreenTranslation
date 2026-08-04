package com.screentranslation.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.screentranslation.app.R
import com.screentranslation.app.capture.FullScreenFrameProcessor
import com.screentranslation.app.capture.resolveTranslationPlacement

internal fun fullScreenTranslationWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SECURE

internal fun fullScreenControlWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SECURE

/** Renders translated blocks immediately above their recognized source boxes. */
class FullScreenOverlayController(
    context: Context,
    private val onStop: () -> Unit,
    private val onOverlayBoundsChanged: (List<Rect>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val widgetContext = ContextThemeWrapper(
        appContext,
        R.style.Theme_ScreenTranslation_OverlayWidgets,
    )
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val visualStyle = resolveOverlayVisualStyle(appContext)
    private val blockViews = mutableMapOf<Long, TextView>()
    private val plannedBlockBounds = mutableMapOf<Long, Rect>()
    private var blockRoot: FrameLayout? = null
    private var controlRoot: LinearLayout? = null
    private var statusView: TextView? = null
    private var lastReportedBounds = emptyList<Rect>()
    private var maskTransitionPending = false
    private val measurementView: TextView by lazy(LazyThreadSafetyMode.NONE) {
        createTranslationView().apply {
            // Android 16 TextView#setText may consult LayoutParams while
            // deciding whether a detached measuring view needs relayout.
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }
    private val finishMaskTransition = Runnable {
        maskTransitionPending = false
        reportOverlayBounds()
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!maskTransitionPending) reportOverlayBounds()
    }

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(): Boolean {
        if (!hasOverlayPermission()) return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show() }
            return true
        }
        if (blockRoot != null) return true

        val blocks = FrameLayout(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val controls = createControls()
        return try {
            windowManager.addView(blocks, blockLayoutParams())
            try {
                windowManager.addView(controls, controlLayoutParams())
            } catch (error: Throwable) {
                windowManager.removeViewImmediate(blocks)
                throw error
            }
            blockRoot = blocks
            controlRoot = controls
            blocks.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            controls.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            blocks.post { reportOverlayBounds() }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun updateStatus(status: String) = runOnMain {
        statusView?.text = status
        controlRoot?.post {
            if (!maskTransitionPending) reportOverlayBounds()
        }
    }

    fun updateBlocks(blocks: List<FullScreenFrameProcessor.TranslatedScreenBlock>) = runOnMain {
        val root = blockRoot ?: return@runOnMain
        val nextBounds = blocks.associateTo(linkedMapOf()) { block ->
            block.id to measureTranslationBounds(block)
        }

        // HyperOS includes application overlays in MediaProjection frames. Tell
        // the capture thread about both the currently drawn rectangles and the
        // rectangles about to be drawn before mutating any attached TextView.
        // This closes the one-frame window in which OCR could ingest a label or
        // see only the uncovered fragments of the source below it.
        maskTransitionPending = true
        mainHandler.removeCallbacks(finishMaskTransition)
        reportOverlayBounds(plannedBlockBounds.values + nextBounds.values)

        val activeIds = blocks.mapTo(hashSetOf()) { it.id }
        blockViews.keys.filter { it !in activeIds }.forEach { id ->
            blockViews.remove(id)?.let(root::removeView)
        }
        blocks.forEach { block ->
            val view = blockViews.getOrPut(block.id) {
                createTranslationView().also(root::addView)
            }
            view.text = block.translatedText
            positionTranslation(view, nextBounds.getValue(block.id))
        }
        plannedBlockBounds.clear()
        plannedBlockBounds.putAll(nextBounds)
        mainHandler.postDelayed(finishMaskTransition, MASK_TRANSITION_SETTLE_MS)
    }

    fun close() = runOnMain {
        mainHandler.removeCallbacks(finishMaskTransition)
        blockRoot?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnGlobalLayoutListener(layoutListener)
        controlRoot?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnGlobalLayoutListener(layoutListener)
        blockRoot?.let { root ->
            runCatching { if (root.isAttachedToWindow) windowManager.removeViewImmediate(root) }
        }
        controlRoot?.let { root ->
            runCatching { if (root.isAttachedToWindow) windowManager.removeViewImmediate(root) }
        }
        blockViews.clear()
        plannedBlockBounds.clear()
        blockRoot = null
        controlRoot = null
        statusView = null
        maskTransitionPending = false
        lastReportedBounds = emptyList()
        onOverlayBoundsChanged(emptyList())
    }

    /**
     * MediaProjection on the target HyperOS build includes this app's overlay
     * windows even when FLAG_SECURE is set. Report the exact visible rectangles
     * so the capture pipeline can paint them out before change detection/OCR and
     * avoid recursively translating its own labels.
     */
    private fun reportOverlayBounds(
        blockBounds: Collection<Rect> = plannedBlockBounds.values,
    ) {
        val blockSnapshot = blockBounds.map(::Rect)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reportOverlayBounds(blockSnapshot) }
            return
        }
        val screen = windowManager.maximumWindowMetrics.bounds
        val systemInsets = windowManager.maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.navigationBars(),
            )
        val padding = dp(CAPTURE_MASK_PADDING_DP)
        val bounds = buildList {
            // System bars remain fully visible to the user, but their clock,
            // carrier, LTE/Wi-Fi labels, and gesture hints are not source-app
            // content and must not enter OCR.
            if (systemInsets.top > 0) {
                add(Rect(screen.left, screen.top, screen.right, screen.top + systemInsets.top))
            }
            if (systemInsets.bottom > 0) {
                add(
                    Rect(
                        screen.left,
                        screen.bottom - systemInsets.bottom,
                        screen.right,
                        screen.bottom,
                    ),
                )
            }
            if (systemInsets.left > 0) {
                add(Rect(screen.left, screen.top, screen.left + systemInsets.left, screen.bottom))
            }
            if (systemInsets.right > 0) {
                add(Rect(screen.right - systemInsets.right, screen.top, screen.right, screen.bottom))
            }

            controlRoot?.let { view ->
                if (!view.isAttachedToWindow || view.visibility != View.VISIBLE ||
                    view.width <= 0 || view.height <= 0
                ) {
                    return@let
                }
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                Rect(
                    (location[0] - padding).coerceAtLeast(screen.left),
                    (location[1] - padding).coerceAtLeast(screen.top),
                    (location[0] + view.width + padding).coerceAtMost(screen.right),
                    (location[1] + view.height + padding).coerceAtMost(screen.bottom),
                ).takeIf { it.width() > 0 && it.height() > 0 }?.let(::add)
            }
            blockSnapshot.forEach { block ->
                Rect(
                    (block.left - padding).coerceAtLeast(screen.left),
                    (block.top - padding).coerceAtLeast(screen.top),
                    (block.right + padding).coerceAtMost(screen.right),
                    (block.bottom + padding).coerceAtMost(screen.bottom),
                ).takeIf { it.width() > 0 && it.height() > 0 }?.let(::add)
            }
        }
        val normalizedBounds = bounds.distinct()
            .sortedWith(compareBy({ it.top }, { it.left }, { it.bottom }, { it.right }))
        if (normalizedBounds != lastReportedBounds) {
            lastReportedBounds = normalizedBounds.map(::Rect)
            onOverlayBoundsChanged(lastReportedBounds.map(::Rect))
        }
    }

    private fun createControls(): LinearLayout {
        val background = GradientDrawable().apply {
            cornerRadius = dp(visualStyle.panelCornerDp).toFloat()
            setColor(visualStyle.panelColor)
            setStroke(dp(1), visualStyle.panelStrokeColor)
        }
        return LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            this.background = background
            elevation = dp(12).toFloat()
            setPadding(dp(12), dp(6), dp(8), dp(6))
            statusView = TextView(appContext).apply {
                text = appContext.getString(R.string.full_screen_overlay_starting)
                setTextColor(visualStyle.statusTextColor)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(
                statusView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                MaterialButton(widgetContext).apply {
                    text = appContext.getString(R.string.overlay_stop)
                    isAllCaps = false
                    minHeight = dp(40)
                    cornerRadius = dp(visualStyle.controlCornerDp)
                    insetTop = 0
                    insetBottom = 0
                    backgroundTintList = ColorStateList.valueOf(Color.argb(52, 255, 69, 58))
                    setTextColor(Color.rgb(255, 105, 97))
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(Color.argb(190, 255, 69, 58))
                    setOnClickListener { onStop() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) },
            )
        }
    }

    private fun createTranslationView(): TextView {
        val background = GradientDrawable().apply {
            cornerRadius = dp(visualStyle.labelCornerDp).toFloat()
            setColor(visualStyle.labelColor)
            setStroke(dp(1), visualStyle.labelStrokeColor)
        }
        return TextView(appContext).apply {
            this.background = background
            setTextColor(visualStyle.labelTextColor)
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(5), dp(3), dp(5), dp(3))
            elevation = dp(8).toFloat()
        }
    }

    private fun measureTranslationBounds(
        block: FullScreenFrameProcessor.TranslatedScreenBlock,
    ): Rect {
        val screen = windowManager.maximumWindowMetrics.bounds
        val sourceLeft = (block.bounds.left * screen.width()).toInt()
        val sourceRight = (block.bounds.right * screen.width()).toInt()
        val desiredWidth = maxOf(sourceRight - sourceLeft, dp(96))
            .coerceAtMost((screen.width() * MAX_LABEL_WIDTH_FRACTION).toInt())
        measurementView.text = block.translatedText
        measurementView.measure(
            View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val placement = resolveTranslationPlacement(
            bounds = block.bounds,
            screenWidth = screen.width(),
            screenHeight = screen.height(),
            labelHeight = measurementView.measuredHeight,
            minimumWidth = dp(96),
            maximumWidth = (screen.width() * MAX_LABEL_WIDTH_FRACTION).toInt(),
            gap = dp(3),
        )
        return Rect(
            screen.left + placement.left,
            screen.top + placement.top,
            screen.left + placement.left + placement.width,
            screen.top + placement.top + measurementView.measuredHeight,
        )
    }

    private fun positionTranslation(view: TextView, bounds: Rect) {
        val screen = windowManager.maximumWindowMetrics.bounds
        view.layoutParams = FrameLayout.LayoutParams(
            bounds.width(),
            bounds.height(),
        ).apply {
            leftMargin = bounds.left - screen.left
            topMargin = bounds.top - screen.top
        }
    }

    private fun blockLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        fullScreenTranslationWindowFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = appContext.getString(R.string.full_screen_overlay_window_title)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }

    private fun controlLayoutParams() = WindowManager.LayoutParams(
        (windowManager.maximumWindowMetrics.bounds.width() * CONTROL_WIDTH_FRACTION).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        fullScreenControlWindowFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val safeTop = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
            )
            .top
        y = safeTop + dp(CONTROL_TOP_GAP_DP)
        title = appContext.getString(R.string.full_screen_control_window_title)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_LABEL_WIDTH_FRACTION = 0.72f
        const val CONTROL_WIDTH_FRACTION = 0.90f
        const val CONTROL_TOP_GAP_DP = 4
        const val CAPTURE_MASK_PADDING_DP = 2
        const val MASK_TRANSITION_SETTLE_MS = 100L
    }
}
