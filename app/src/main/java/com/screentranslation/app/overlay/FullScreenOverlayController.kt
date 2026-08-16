package com.screentranslation.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.screentranslation.app.R
import com.screentranslation.app.capture.FullScreenFrameProcessor
import com.screentranslation.app.capture.OverlayTranslationBlock
import com.screentranslation.app.capture.TranslationObstacle
import com.screentranslation.app.capture.mergeAdjacentOverlayBlocks
import com.screentranslation.app.capture.resolveNonOverlappingTranslationPlacement

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
    private val onPausedChanged: (Boolean) -> Unit = {},
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
    private var pauseButton: MaterialButton? = null
    private var visibilityButton: MaterialButton? = null
    private var readingButton: MaterialButton? = null
    private var fontButton: MaterialButton? = null
    private var opacityButton: MaterialButton? = null
    private var readingScrollView: ScrollView? = null
    private var readingList: LinearLayout? = null
    private var highlightView: View? = null
    private var highlightBounds: Rect? = null
    private var currentBlocks = emptyList<FullScreenFrameProcessor.TranslatedScreenBlock>()
    private var unplacedBlockIds = emptySet<Long>()
    private var currentStatus = ""
    private var paused = false
    private var labelsVisible = true
    private var readingMode = false
    private var fontSizeIndex = DEFAULT_FONT_SIZE_INDEX
    private var opacityIndex = DEFAULT_OPACITY_INDEX
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
    private val clearHighlight = Runnable {
        highlightView?.let { view -> blockRoot?.removeView(view) }
        highlightView = null
        highlightBounds = null
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
        currentStatus = status
        renderStatus()
        controlRoot?.post {
            if (!maskTransitionPending) reportOverlayBounds()
        }
    }

    fun updateBlocks(blocks: List<FullScreenFrameProcessor.TranslatedScreenBlock>) = runOnMain {
        currentBlocks = blocks.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.id }))
        renderReadingSurface()
        renderTranslationBlocks()
    }

    private fun renderTranslationBlocks() {
        val root = blockRoot ?: return
        val plan = if (labelsVisible) {
            planTranslationBounds(currentBlocks)
        } else {
            TranslationRenderPlan(linkedMapOf(), emptySet(), currentBlocks.mapTo(linkedSetOf()) { it.id })
        }
        val nextBounds = plan.labels.mapValues { it.value.bounds }
        unplacedBlockIds = plan.hiddenIds
        renderStatus()

        // HyperOS includes application overlays in MediaProjection frames. Tell
        // the capture thread about both the currently drawn rectangles and the
        // rectangles about to be drawn before mutating any attached TextView.
        // This closes the one-frame window in which OCR could ingest a label or
        // see only the uncovered fragments of the source below it.
        maskTransitionPending = true
        mainHandler.removeCallbacks(finishMaskTransition)
        reportOverlayBounds(plannedBlockBounds.values + nextBounds.values)

        val activeIds = plan.labels.keys
        blockViews.keys.filter { it !in activeIds }.forEach { id ->
            blockViews.remove(id)?.let(root::removeView)
        }
        plan.labels.forEach { (key, label) ->
            val view = blockViews.getOrPut(key) {
                createTranslationView().also(root::addView)
            }
            view.text = label.block.displayTranslation
            view.contentDescription = null
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            positionTranslation(view, label.bounds)
        }
        plannedBlockBounds.clear()
        plannedBlockBounds.putAll(nextBounds)
        mainHandler.postDelayed(finishMaskTransition, MASK_TRANSITION_SETTLE_MS)
    }

    fun close() = runOnMain {
        mainHandler.removeCallbacks(finishMaskTransition)
        mainHandler.removeCallbacks(clearHighlight)
        if (paused) onPausedChanged(false)
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
        pauseButton = null
        visibilityButton = null
        readingButton = null
        fontButton = null
        opacityButton = null
        readingScrollView = null
        readingList = null
        highlightView = null
        highlightBounds = null
        currentBlocks = emptyList()
        unplacedBlockIds = emptySet()
        paused = false
        labelsVisible = true
        readingMode = false
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
        val blockSnapshot = (blockBounds + listOfNotNull(highlightBounds)).map(::Rect)
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
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = createPanelBackground()
            elevation = dp(12).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val statusRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(appContext).apply {
            text = appContext.getString(R.string.full_screen_overlay_starting)
            setTextColor(visualStyle.statusTextColor)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(
            statusView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val stop = createControlButton(destructive = true).apply {
            text = appContext.getString(R.string.overlay_stop)
            contentDescription = appContext.getString(R.string.full_screen_accessibility_stop)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
                onStop()
            }
        }
        statusRow.addView(
            stop,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
        root.addView(statusRow)

        val actionRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pauseButton = createControlButton().apply {
            setOnClickListener {
                paused = !paused
                onPausedChanged(paused)
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                renderControlState()
            }
        }
        visibilityButton = createControlButton().apply {
            setOnClickListener {
                labelsVisible = !labelsVisible
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                renderControlState()
                renderTranslationBlocks()
            }
        }
        readingButton = createControlButton().apply {
            setOnClickListener {
                readingMode = !readingMode
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                renderControlState()
                renderReadingSurface()
                root.post { reportOverlayBounds() }
            }
        }
        fontButton = createControlButton().apply {
            setOnClickListener {
                fontSizeIndex = (fontSizeIndex + 1) % LABEL_TEXT_SIZES_SP.size
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                measurementView.textSize = LABEL_TEXT_SIZES_SP[fontSizeIndex]
                blockViews.values.forEach { it.textSize = LABEL_TEXT_SIZES_SP[fontSizeIndex] }
                renderControlState()
                renderTranslationBlocks()
            }
        }
        opacityButton = createControlButton().apply {
            setOnClickListener {
                opacityIndex = (opacityIndex + 1) % BACKGROUND_OPACITIES.size
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                root.background = createPanelBackground()
                blockViews.values.forEach { it.background = createLabelBackground() }
                renderControlState()
                renderTranslationBlocks()
            }
        }
        listOf(
            pauseButton,
            visibilityButton,
            readingButton,
            fontButton,
            opacityButton,
        ).forEachIndexed { index, button ->
            actionRow.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) marginStart = dp(6) },
            )
        }
        root.addView(
            HorizontalScrollView(appContext).apply {
                isHorizontalScrollBarEnabled = false
                addView(actionRow)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )

        readingList = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        readingScrollView = ScrollView(appContext).apply {
            visibility = View.GONE
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            contentDescription = appContext.getString(R.string.full_screen_reading_panel_description)
            addView(
                readingList,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            readingScrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (windowManager.maximumWindowMetrics.bounds.height() * READING_HEIGHT_FRACTION)
                    .toInt()
                    .coerceAtMost(dp(MAX_READING_HEIGHT_DP)),
            ).apply { topMargin = dp(6) },
        )
        renderControlState()
        return root
    }

    private fun createControlButton(destructive: Boolean = false): MaterialButton {
        val activeColor = if (destructive) Color.rgb(255, 69, 58) else visualStyle.accentColor
        return MaterialButton(widgetContext).apply {
            isAllCaps = false
            minHeight = dp(48)
            minWidth = dp(48)
            cornerRadius = dp(visualStyle.controlCornerDp)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(10), 0, dp(10), 0)
            backgroundTintList = ColorStateList.valueOf(withAlpha(activeColor, 52))
            setTextColor(activeColor)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(activeColor, 190))
        }
    }

    private fun renderControlState() {
        pauseButton?.apply {
            text = appContext.getString(
                if (paused) R.string.full_screen_resume else R.string.full_screen_pause,
            )
            contentDescription = text
            ViewCompat.setStateDescription(
                this,
                appContext.getString(
                    if (paused) {
                        R.string.full_screen_state_paused
                    } else {
                        R.string.full_screen_state_running
                    },
                ),
            )
        }
        visibilityButton?.apply {
            text = appContext.getString(
                if (labelsVisible) R.string.full_screen_hide else R.string.full_screen_show,
            )
            contentDescription = text
            ViewCompat.setStateDescription(
                this,
                appContext.getString(
                    if (labelsVisible) {
                        R.string.full_screen_state_labels_visible
                    } else {
                        R.string.full_screen_state_labels_hidden
                    },
                ),
            )
        }
        readingButton?.apply {
            text = appContext.getString(
                if (readingMode) R.string.full_screen_reading_close else R.string.full_screen_reading,
                currentBlocks.size,
            )
            contentDescription = text
            ViewCompat.setStateDescription(
                this,
                appContext.getString(
                    if (readingMode) {
                        R.string.full_screen_state_reading_open
                    } else {
                        R.string.full_screen_state_reading_closed
                    },
                ),
            )
        }
        fontButton?.apply {
            text = appContext.getString(
                R.string.full_screen_font_size,
                LABEL_TEXT_SIZES_SP[fontSizeIndex].toInt(),
            )
            contentDescription = appContext.getString(R.string.full_screen_font_size_description)
            ViewCompat.setStateDescription(this, text)
        }
        opacityButton?.apply {
            text = appContext.getString(
                R.string.full_screen_background_opacity,
                (BACKGROUND_OPACITIES[opacityIndex] * 100).toInt(),
            )
            contentDescription = appContext.getString(
                R.string.full_screen_background_opacity_description,
            )
            ViewCompat.setStateDescription(this, text)
        }
        readingScrollView?.visibility = if (readingMode) View.VISIBLE else View.GONE
        renderStatus()
    }

    private fun renderStatus() {
        val base = currentStatus.ifBlank {
            appContext.getString(R.string.full_screen_overlay_starting)
        }
        val detail = when {
            paused -> appContext.getString(R.string.full_screen_state_paused)
            !labelsVisible -> appContext.getString(
                R.string.full_screen_status_labels_hidden,
                currentBlocks.size,
            )
            unplacedBlockIds.isNotEmpty() -> appContext.getString(
                R.string.full_screen_status_hidden_count,
                unplacedBlockIds.size,
            )
            else -> appContext.getString(
                R.string.full_screen_status_visible_count,
                currentBlocks.size,
            )
        }
        val rendered = "$base · $detail"
        statusView?.let { view ->
            if (view.text.toString() != rendered) view.text = rendered
            ViewCompat.setStateDescription(view, rendered)
        }
        readingButton?.text = appContext.getString(
            if (readingMode) R.string.full_screen_reading_close else R.string.full_screen_reading,
            currentBlocks.size,
        )
    }

    private fun renderReadingSurface() {
        val list = readingList ?: return
        list.removeAllViews()
        if (currentBlocks.isEmpty()) {
            list.addView(readingTextView(appContext.getString(R.string.full_screen_reading_empty)))
            return
        }
        currentBlocks.forEachIndexed { index, block ->
            val item = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(visualStyle.controlCornerDp).toFloat()
                    setColor(withAlpha(visualStyle.labelColor, 224))
                    setStroke(dp(1), visualStyle.labelStrokeColor)
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = appContext.getString(
                    R.string.full_screen_reading_item_description,
                    index + 1,
                    block.originalText,
                    block.translatedText,
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    highlightSource(block)
                }
            }
            item.addView(readingTextView(appContext.getString(R.string.full_screen_block_id, block.id)))
            item.addView(readingTextView(block.originalText, original = true))
            item.addView(readingTextView(block.translatedText))
            item.addView(
                createControlButton().apply {
                    text = appContext.getString(R.string.full_screen_copy_pair)
                    contentDescription = appContext.getString(
                        R.string.full_screen_copy_pair_description,
                        index + 1,
                    )
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        copyPair(block)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END },
            )
            list.addView(
                item,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = dp(6) },
            )
        }
    }

    private fun readingTextView(
        value: String,
        original: Boolean = false,
    ): TextView = TextView(appContext).apply {
        text = value
        setTextColor(
            if (original) visualStyle.originalTextColor else visualStyle.translationTextColor,
        )
        textSize = if (original) 13f else 15f
        maxLines = Int.MAX_VALUE
        ellipsize = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun highlightSource(block: FullScreenFrameProcessor.TranslatedScreenBlock) {
        val root = blockRoot ?: return
        mainHandler.removeCallbacks(clearHighlight)
        highlightView?.let(root::removeView)
        val screen = windowManager.maximumWindowMetrics.bounds
        val left = (block.bounds.left * screen.width()).toInt()
        val top = (block.bounds.top * screen.height()).toInt()
        val right = (block.bounds.right * screen.width()).toInt()
        val bottom = (block.bounds.bottom * screen.height()).toInt()
        val bounds = Rect(
            screen.left + left,
            screen.top + top,
            screen.left + right,
            screen.top + bottom,
        )
        val highlight = View(appContext).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), visualStyle.accentColor)
                cornerRadius = dp(4).toFloat()
            }
        }
        highlight.layoutParams = FrameLayout.LayoutParams(
            bounds.width().coerceAtLeast(dp(4)),
            bounds.height().coerceAtLeast(dp(4)),
        ).apply {
            leftMargin = bounds.left - screen.left
            topMargin = bounds.top - screen.top
        }
        root.addView(highlight)
        highlightView = highlight
        highlightBounds = bounds
        reportOverlayBounds()
        mainHandler.postDelayed(clearHighlight, HIGHLIGHT_DURATION_MS)
    }

    private fun copyPair(block: FullScreenFrameProcessor.TranslatedScreenBlock) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                appContext.getString(R.string.full_screen_clipboard_label),
                "${block.originalText}\n${block.translatedText}",
            ),
        )
        Toast.makeText(appContext, R.string.overlay_copy_success, Toast.LENGTH_SHORT).show()
    }

    private fun createPanelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(visualStyle.panelCornerDp).toFloat()
        setColor(withAlpha(visualStyle.panelColor, 245))
        setStroke(dp(1), visualStyle.panelStrokeColor)
    }

    private fun createLabelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(visualStyle.labelCornerDp).toFloat()
        setColor(withAlpha(visualStyle.labelColor, (BACKGROUND_OPACITIES[opacityIndex] * 255).toInt()))
        setStroke(dp(1), visualStyle.labelStrokeColor)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun createTranslationView(): TextView {
        return TextView(appContext).apply {
            background = createLabelBackground()
            setTextColor(visualStyle.labelTextColor)
            textSize = LABEL_TEXT_SIZES_SP[fontSizeIndex]
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(5), dp(3), dp(5), dp(3))
            elevation = dp(8).toFloat()
        }
    }

    private fun planTranslationBounds(
        blocks: List<FullScreenFrameProcessor.TranslatedScreenBlock>,
    ): TranslationRenderPlan {
        val screen = windowManager.maximumWindowMetrics.bounds
        val insets = windowManager.maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.navigationBars(),
            )
        val gap = dp(LABEL_GAP_DP)
        val minimumTop = insets.top
        val maximumBottom = (screen.height() - insets.bottom).coerceAtLeast(minimumTop)
        val occupied = mutableListOf<TranslationObstacle>()
        controlRoot?.takeIf {
            it.isAttachedToWindow && it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
        }?.let { control ->
            val location = IntArray(2)
            control.getLocationOnScreen(location)
            occupied += TranslationObstacle(
                left = location[0] - screen.left,
                top = location[1] - screen.top,
                right = location[0] - screen.left + control.width,
                bottom = location[1] - screen.top + control.height,
            )
        }

        val groups = mergeAdjacentOverlayBlocks(
            blocks.map { block ->
                OverlayTranslationBlock(
                    ids = listOf(block.id),
                    originalTexts = listOf(block.originalText),
                    translatedTexts = listOf(block.translatedText),
                    bounds = block.bounds,
                )
            },
        )
        val result = linkedMapOf<Long, PlannedTranslationLabel>()
        val visibleIds = linkedSetOf<Long>()
        val hiddenIds = linkedSetOf<Long>()
        groups.forEach { block ->
                val sourceLeft = (block.bounds.left * screen.width()).toInt()
                val sourceRight = (block.bounds.right * screen.width()).toInt()
                val desiredWidth = maxOf(sourceRight - sourceLeft, dp(MIN_LABEL_WIDTH_DP))
                    .coerceAtMost((screen.width() * MAX_LABEL_WIDTH_FRACTION).toInt())
                measurementView.text = block.displayTranslation
                measurementView.measure(
                    View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val placement = resolveNonOverlappingTranslationPlacement(
                    bounds = block.bounds,
                    screenWidth = screen.width(),
                    screenHeight = screen.height(),
                    labelHeight = measurementView.measuredHeight,
                    minimumWidth = dp(MIN_LABEL_WIDTH_DP),
                    maximumWidth = (screen.width() * MAX_LABEL_WIDTH_FRACTION).toInt(),
                    gap = gap,
                    minimumTop = minimumTop,
                    maximumBottom = maximumBottom,
                    occupied = occupied,
                )
                if (placement == null) {
                    hiddenIds += block.ids
                    return@forEach
                }
                val relative = TranslationObstacle(
                    left = placement.left,
                    top = placement.top,
                    right = placement.left + placement.width,
                    bottom = placement.top + measurementView.measuredHeight,
                )
                occupied += relative
                visibleIds += block.ids
                val bounds = Rect(
                    screen.left + relative.left,
                    screen.top + relative.top,
                    screen.left + relative.right,
                    screen.top + relative.bottom,
                )
                result[block.ids.first()] = PlannedTranslationLabel(block, bounds)
            }
        return TranslationRenderPlan(result, visibleIds, hiddenIds)
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
        const val MIN_LABEL_WIDTH_DP = 96
        const val LABEL_GAP_DP = 3
        const val CONTROL_WIDTH_FRACTION = 0.90f
        const val CONTROL_TOP_GAP_DP = 4
        const val CAPTURE_MASK_PADDING_DP = 2
        const val MASK_TRANSITION_SETTLE_MS = 100L
        const val HIGHLIGHT_DURATION_MS = 2_000L
        const val READING_HEIGHT_FRACTION = 0.52f
        const val MAX_READING_HEIGHT_DP = 520
        val LABEL_TEXT_SIZES_SP = floatArrayOf(12f, 14f, 18f, 22f)
        val BACKGROUND_OPACITIES = floatArrayOf(0.56f, 0.72f, 0.88f, 1f)
        const val DEFAULT_FONT_SIZE_INDEX = 1
        const val DEFAULT_OPACITY_INDEX = 2
    }

    private data class PlannedTranslationLabel(
        val block: OverlayTranslationBlock,
        val bounds: Rect,
    )

    private data class TranslationRenderPlan(
        val labels: LinkedHashMap<Long, PlannedTranslationLabel>,
        val visibleIds: Set<Long>,
        val hiddenIds: Set<Long>,
    )
}
