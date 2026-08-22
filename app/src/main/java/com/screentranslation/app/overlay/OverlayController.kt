package com.screentranslation.app.overlay

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.android.material.button.MaterialButton
import androidx.core.view.ViewCompat
import com.screentranslation.app.R
import com.screentranslation.app.RegionPresetActivity
import com.screentranslation.app.SelectionGestureGuardActivity

/**
 * Keeps the translation panel visible in user-initiated screenshots and
 * recordings. The capture pipeline excludes the reported panel rectangle
 * before OCR, so preventing recursive recognition does not depend on
 * [WindowManager.LayoutParams.FLAG_SECURE].
 */
internal fun overlayWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

internal fun selectionOverlayWindowFlags(): Int =
    overlayWindowFlags() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()

internal data class RegionOverlayContent(
    val original: String,
    val translation: String,
)

private const val REGION_ACTION_MAX_COLUMNS = 3

/**
 * Keeps the region controls visible without relying on a horizontally scrolled
 * row. Five actions become a 3 + 2 grid so the longer "reselect region" label
 * and the destructive stop action always have half of the panel width.
 */
internal fun <T> regionActionRows(actions: List<T>): List<List<T>> =
    actions.chunked(REGION_ACTION_MAX_COLUMNS)

internal enum class SelectionExitTrigger {
    EXPLICIT_BUTTON,
    SYSTEM_BACK,
}

internal fun shouldExitSelection(trigger: SelectionExitTrigger): Boolean =
    trigger == SelectionExitTrigger.EXPLICIT_BUTTON

/**
 * Region-mode requests are latest-wins, but a transient Online failure must
 * not erase the last useful translation while the next source is pending.
 * Keeping this transition pure lets the production failure contract exercise
 * the same state rule without constructing an Android window in a JVM test.
 */
internal object RegionOverlayContentPolicy {
    fun success(
        original: String,
        translation: String,
    ): RegionOverlayContent = RegionOverlayContent(
        original = original,
        translation = translation,
    )

    @Suppress("UNUSED_PARAMETER")
    fun pending(
        currentOriginal: String,
        currentTranslation: String,
        nextOriginal: String,
    ): RegionOverlayContent = RegionOverlayContent(
        // The latest source belongs to an unfinished request. Keep the last
        // successful pair atomic until that request produces its translation.
        original = currentOriginal,
        translation = currentTranslation,
    )

    fun failure(
        currentOriginal: String,
        currentTranslation: String,
    ): RegionOverlayContent = RegionOverlayContent(
        original = currentOriginal,
        translation = currentTranslation,
    )
}

/**
 * Owns exactly one TYPE_APPLICATION_OVERLAY window.
 *
 * The window temporarily fills the display while the user drags a capture ROI.
 * Once selected, the same root window is resized to a compact, touchable panel;
 * the rest of the screen is therefore usable while translation continues.
 */
class OverlayController(
    context: Context,
    private val onRegionChanged: (Rect) -> Unit,
    private val onOverlayBoundsChanged: (Rect?) -> Unit,
    private val onStop: () -> Unit,
    /**
     * Signals that the previously selected region is no longer valid. Without
     * it the capture pipeline keeps recognizing the old rectangle while the
     * overlay is asking for a new one.
     */
    private val onRegionCleared: () -> Unit = {},
    /**
     * Signals that the result panel was expanded or collapsed. An expanded panel
     * is tall enough to cover the selected region, and the capture pipeline masks
     * the panel rectangle before OCR, so leaving recognition running would replace
     * the very text the user expanded in order to read.
     */
    private val onExpandedChanged: (Boolean) -> Unit = {},
    private val onFrozenChanged: (Boolean) -> Unit = {},
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

    private var rootView: FrameLayout? = null
    private var selectionView: RegionSelectionView? = null
    private var selectionExitButton: Button? = null
    private var controlPanel: LinearLayout? = null
    private var statusView: TextView? = null
    private var originalView: TextView? = null
    private var translationView: TextView? = null
    private var attributionView: TextView? = null
    private var textScrollView: ScrollView? = null
    private var reselectButton: Button? = null
    private var expandButton: Button? = null
    private var copyOriginalButton: Button? = null
    private var copyTranslationButton: Button? = null
    private var freezeButton: Button? = null
    private var presetButton: Button? = null
    private var textExpanded = false
    private var frozen = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private var selectedRegion: Rect? = null
    private var currentOriginal = ""
    private var currentTranslation = ""
    private var currentStatus = ""
    private var selectionModeActive = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var panelDragging = false
    private var registeredBackDispatcher: OnBackInvokedDispatcher? = null
    private val selectionBackCallback = OnBackInvokedCallback {
        handleSelectionExit(SelectionExitTrigger.SYSTEM_BACK)
    }

    val isShowing: Boolean
        get() = rootView?.isAttachedToWindow == true

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    /**
     * Adds the overlay and enters ROI selection mode.
     *
     * A false return means the special "display over other apps" permission is
     * not currently granted (or WindowManager rejected the add operation).
     */
    fun show(): Boolean {
        if (!hasOverlayPermission()) return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show() }
            return true
        }
        if (rootView != null) return true

        val root = FrameLayout(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                dispatchOverlayBounds()
            }
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ ->
                if (selectionModeActive && keyCode == KeyEvent.KEYCODE_BACK) {
                    handleSelectionExit(SelectionExitTrigger.SYSTEM_BACK)
                    true
                } else {
                    false
                }
            }
        }
        val selector = RegionSelectionView(appContext).apply {
            onRegionSelected = { region ->
                selectedRegion = Rect(region)
                onRegionChanged(Rect(region))
                switchToCompactMode()
            }
            onSelectionRejected = {
                val message = appContext.getString(R.string.overlay_selection_too_small)
                updateStatus(message)
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
        val selectionExit = createOverlayButton(destructive = true).apply {
            text = appContext.getString(R.string.overlay_exit_selection)
            contentDescription = appContext.getString(R.string.overlay_exit_selection_description)
            visibility = View.GONE
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
                handleSelectionExit(SelectionExitTrigger.EXPLICIT_BUTTON)
            }
        }
        val panel = createControlPanel()

        root.addView(
            selector,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            selectionExit,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(84)
                marginEnd = dp(16)
            },
        )

        val params = createLayoutParams()
        return try {
            windowManager.addView(root, params)
            rootView = root
            selectionView = selector
            selectionExitButton = selectionExit
            controlPanel = panel
            layoutParams = params
            requestRegionSelection()
            root.post { dispatchOverlayBounds() }
            true
        } catch (_: SecurityException) {
            clearViewReferences()
            false
        } catch (_: WindowManager.BadTokenException) {
            clearViewReferences()
            false
        }
    }

    fun requestRegionSelection() {
        runOnMain {
            val root = rootView ?: return@runOnMain
            val selector = selectionView ?: return@runOnMain
            val panel = controlPanel ?: return@runOnMain
            val params = layoutParams ?: return@runOnMain

            // Expansion is a "hold still so I can read this" state. Asking for a
            // new region ends it -- otherwise recognition stays paused and the
            // freshly selected region never produces anything.
            clearExpanded()
            clearFrozen()

            selector.visibility = View.VISIBLE
            selector.startSelection()
            selector.setGestureExclusionEnabled(true)

            // Selection stays visually transparent: the selector itself draws
            // one small instruction pill and the active rectangle. Hiding the
            // result/control panel avoids duplicating the hint or covering the
            // very subtitle the user is trying to locate.
            panel.visibility = View.GONE
            selectionExitButton?.visibility = View.VISIBLE

            currentStatus = appContext.getString(R.string.overlay_status_selecting)
            statusView?.text = currentStatus
            selectedRegion = null
            onOverlayBoundsChanged(null)
            onRegionCleared()

            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            params.flags = selectionOverlayWindowFlags()
            selectionModeActive = true
            safelyUpdateViewLayout(root, params)
            root.requestFocus()
            root.post { registerSelectionBackCallback(root) }
            SelectionGestureGuardActivity.show(appContext) {
                handleSelectionExit(SelectionExitTrigger.SYSTEM_BACK)
            }
        }
    }

    fun updateContent(original: String, translation: String) {
        val successful = RegionOverlayContentPolicy.success(original, translation)
        runOnMain {
            currentOriginal = successful.original
            currentTranslation = successful.translation
            originalView?.text = successful.original.ifBlank {
                appContext.getString(R.string.overlay_waiting_for_text)
            }
            translationView?.text = successful.translation.ifBlank {
                appContext.getString(R.string.overlay_waiting_for_translation)
            }
            copyOriginalButton?.isEnabled = successful.original.isNotBlank()
            copyTranslationButton?.isEnabled = successful.translation.isNotBlank()
            rootView?.post { dispatchOverlayBounds() }
        }
    }

    /** Applies a normalized preset converted by the service for the current display. */
    fun applySelectedRegion(region: Rect) {
        runOnMain {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val clamped = Rect(
                region.left.coerceIn(0, bounds.width() - 1),
                region.top.coerceIn(0, bounds.height() - 1),
                region.right.coerceIn(1, bounds.width()),
                region.bottom.coerceIn(1, bounds.height()),
            )
            if (clamped.right <= clamped.left || clamped.bottom <= clamped.top) return@runOnMain
            clearExpanded()
            clearFrozen()
            selectedRegion = clamped
            selectionView?.setRegion(clamped)
            onRegionChanged(Rect(clamped))
            switchToCompactMode()
            rootView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    fun updateOriginal(text: String) {
        val pending = RegionOverlayContentPolicy.pending(
            currentOriginal = currentOriginal,
            currentTranslation = currentTranslation,
            nextOriginal = text,
        )
        updateContent(pending.original, pending.translation)
    }

    fun preserveContentAfterFailure() {
        val preserved = RegionOverlayContentPolicy.failure(
            currentOriginal = currentOriginal,
            currentTranslation = currentTranslation,
        )
        updateContent(preserved.original, preserved.translation)
    }

    fun updateStatus(status: String) {
        runOnMain {
            currentStatus = status
            statusView?.text = status
            statusView?.let { ViewCompat.setStateDescription(it, status) }
        }
    }

    fun close() {
        runOnMain {
            val root = rootView
            if (root != null) {
                try {
                    if (root.isAttachedToWindow) {
                        windowManager.removeViewImmediate(root)
                    }
                } catch (_: IllegalArgumentException) {
                    // Already removed by the service/window manager.
                }
            }
            clearViewReferences()
        }
    }

    private fun switchToCompactMode() = collapseToPanel(showResults = true)

    /**
     * Dismisses the full-screen selector and shrinks the window back to the
     * floating panel, leaving the capture session running.
     *
     * [showResults] is false when collapsing without a region: there is nothing
     * to display yet, and the panel exists only so the user can navigate to
     * another app and start selection from there.
     */
    private fun collapseToPanel(showResults: Boolean) {
        val root = rootView ?: return
        val selector = selectionView ?: return
        val panel = controlPanel ?: return
        val params = layoutParams ?: return

        // Nothing to keep expanded when there are no results on screen.
        if (!showResults) clearExpanded()

        selector.visibility = View.GONE
        selector.setGestureExclusionEnabled(false)
        root.systemGestureExclusionRects = emptyList()
        SelectionGestureGuardActivity.dismiss()
        unregisterSelectionBackCallback()
        selectionModeActive = false
        selectionExitButton?.visibility = View.GONE
        panel.visibility = View.VISIBLE
        setPanelGravity(panel, Gravity.TOP)
        applyTextExpansion()
        textScrollView?.visibility = if (showResults) View.VISIBLE else View.GONE
        attributionView?.visibility = if (showResults) View.VISIBLE else View.GONE
        copyOriginalButton?.visibility = if (showResults) View.VISIBLE else View.GONE
        copyTranslationButton?.visibility = if (showResults) View.VISIBLE else View.GONE
        expandButton?.visibility = if (showResults) View.VISIBLE else View.GONE
        reselectButton?.visibility = View.VISIBLE

        if (!showResults) {
            selectedRegion = null
            currentStatus = appContext.getString(R.string.overlay_status_idle)
        }
        statusView?.text = currentStatus.ifBlank {
            appContext.getString(R.string.overlay_status_running)
        }
        originalView?.text = currentOriginal.ifBlank {
            appContext.getString(R.string.overlay_waiting_for_text)
        }
        translationView?.text = currentTranslation.ifBlank {
            appContext.getString(R.string.overlay_waiting_for_translation)
        }

        val margin = dp(12)
        val bounds = windowManager.maximumWindowMetrics.bounds
        // An expanded panel is far taller than the compact estimate, so keeping
        // the region-relative placement would push it off the bottom edge.
        val estimatedPanelHeight = if (textExpanded) {
            (bounds.height() * EXPANDED_HEIGHT_FRACTION).toInt() + dp(160)
        } else {
            // Includes both rows of region actions. Keeping the estimate in
            // sync prevents the lower row from being placed below a safe inset.
            dp(304)
        }
        val region = if (textExpanded) null else selectedRegion
        val maxY = (bounds.height() - estimatedPanelHeight - margin).coerceAtLeast(margin)
        val panelY = when {
            region == null -> dp(28)
            region.top >= estimatedPanelHeight + (margin * 2) ->
                region.top - estimatedPanelHeight - margin
            else -> (region.bottom + margin).coerceAtMost(maxY)
        }.coerceAtLeast(margin)

        params.width = (bounds.width() - (margin * 2)).coerceAtLeast(dp(240))
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        params.x = margin
        params.y = panelY
        params.flags = overlayWindowFlags()
        safelyUpdateViewLayout(root, params)
        root.post { dispatchOverlayBounds() }
    }

    /**
     * Reports the actual overlay window rectangle in physical screen pixels.
     *
     * The window deliberately remains capturable so user screenshots and
     * recordings include the translation. MediaProjection therefore also sees
     * the panel on the target ROM; the capture pipeline masks this rectangle
     * before OCR so the result panel cannot recursively recognize itself.
     */
    private fun dispatchOverlayBounds() {
        val root = rootView
        val panel = controlPanel
        // Measured from the panel, not the root: in selection mode the root
        // fills the display, and reporting that would mask the entire frame.
        if (root == null ||
            panel == null ||
            panel.visibility != View.VISIBLE ||
            !root.isAttachedToWindow ||
            panel.width <= 0 ||
            panel.height <= 0
        ) {
            onOverlayBoundsChanged(null)
            return
        }

        val location = IntArray(2)
        panel.getLocationOnScreen(location)
        onOverlayBoundsChanged(
            Rect(
                location[0],
                location[1],
                location[0] + panel.width,
                location[1] + panel.height,
            ),
        )
    }

    private fun setPanelGravity(panel: View, verticalGravity: Int) {
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val target = verticalGravity or Gravity.START
        if (params.gravity == target) return
        params.gravity = target
        panel.layoutParams = params
    }

    private fun createControlPanel(): LinearLayout {
        val panelBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(visualStyle.panelCornerDp).toFloat()
            setColor(visualStyle.panelColor)
            setStroke(dp(1), visualStyle.panelStrokeColor)
        }

        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = panelBackground
            elevation = dp(12).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            // Absorb touches that land on the panel but miss a button, so they
            // do not fall through and start a selection drag underneath.
            isClickable = true
        }

        statusView = TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_status_running)
            setTextColor(visualStyle.statusTextColor)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = appContext.getString(R.string.overlay_drag_panel_description)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnTouchListener(::handlePanelDrag)
        }
        originalView = TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_waiting_for_text)
            setTextColor(visualStyle.originalTextColor)
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        translationView = TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_waiting_for_translation)
            setTextColor(visualStyle.translationTextColor)
            textSize = 17f
            setPadding(0, dp(4), 0, dp(8))
        }

        // Long passages have to stay readable. Collapsed keeps the panel out of
        // the way; expanded caps the height and scrolls instead of growing until
        // the panel covers the very screen it is translating.
        val textColumn = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(originalView)
            addView(translationView)
        }
        textScrollView = ScrollView(appContext).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                textColumn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        attributionView = TextView(appContext).apply {
            text = appContext.getString(R.string.translation_attribution)
            setTextColor(visualStyle.attributionTextColor)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val copyRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        copyOriginalButton = createOverlayButton().apply {
            text = appContext.getString(R.string.overlay_copy_original)
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                copyToClipboard(
                    label = appContext.getString(R.string.overlay_clipboard_original_label),
                    text = currentOriginal,
                )
            }
        }
        copyTranslationButton = createOverlayButton().apply {
            text = appContext.getString(R.string.overlay_copy_translation)
            isAllCaps = false
            isEnabled = false
            setOnClickListener {
                copyToClipboard(
                    label = appContext.getString(R.string.overlay_clipboard_translation_label),
                    text = currentTranslation,
                )
            }
        }
        copyRow.addView(
            copyOriginalButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        copyRow.addView(
            copyTranslationButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            },
        )

        val actionGroup = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        expandButton = createOverlayButton().apply {
            isAllCaps = false
            setOnClickListener {
                textExpanded = !textExpanded
                // Pause first so the status text is settled before the relayout
                // below reads it, then rerun the full compact-mode layout: the
                // window has to be repositioned, not just grown in place.
                onExpandedChanged(textExpanded)
                collapseToPanel(showResults = true)
            }
        }
        freezeButton = createOverlayButton().apply {
            isAllCaps = false
            setOnClickListener {
                frozen = !frozen
                onFrozenChanged(frozen)
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                renderFreezeState()
            }
        }
        presetButton = createOverlayButton().apply {
            text = appContext.getString(R.string.overlay_presets)
            isAllCaps = false
            contentDescription = appContext.getString(R.string.overlay_presets_description)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                launchPresetManager()
            }
        }
        reselectButton = createOverlayButton().apply {
            text = appContext.getString(R.string.overlay_reselect)
            isAllCaps = false
            setOnClickListener { requestRegionSelection() }
        }
        val stopButton = createOverlayButton(destructive = true).apply {
            text = appContext.getString(R.string.overlay_stop)
            isAllCaps = false
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.REJECT)
                onStop()
                close()
            }
        }
        regionActionRows(
            listOfNotNull(
                expandButton,
                freezeButton,
                presetButton,
                reselectButton,
                stopButton,
            ),
        ).forEachIndexed { rowIndex, actions ->
            val actionRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.forEachIndexed { columnIndex, button ->
                actionRow.addView(
                    button,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        if (columnIndex > 0) marginStart = dp(8)
                    },
                )
            }
            actionGroup.addView(
                actionRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (rowIndex > 0) topMargin = dp(8)
                },
            )
        }

        panel.addView(statusView)
        panel.addView(textScrollView)
        panel.addView(attributionView)
        panel.addView(copyRow)
        panel.addView(actionGroup)
        applyTextExpansion()
        renderFreezeState()
        return panel
    }

    private fun createOverlayButton(destructive: Boolean = false): MaterialButton {
        val activeColor = if (destructive) Color.rgb(255, 69, 58) else visualStyle.accentColor
        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        )
        return MaterialButton(widgetContext).apply {
            isAllCaps = false
            minHeight = dp(48)
            minWidth = dp(48)
            cornerRadius = dp(visualStyle.controlCornerDp)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)
            backgroundTintList = ColorStateList(
                states,
                intArrayOf(
                    Color.argb(24, 142, 142, 147),
                    withAlpha(activeColor, 82),
                    withAlpha(activeColor, 42),
                ),
            )
            setTextColor(
                ColorStateList(
                    states,
                    intArrayOf(
                        Color.rgb(142, 142, 147),
                        activeColor,
                        activeColor,
                    ),
                ),
            )
            strokeWidth = dp(1)
            strokeColor = ColorStateList(
                states,
                intArrayOf(
                    Color.argb(70, 142, 142, 147),
                    activeColor,
                    withAlpha(activeColor, 170),
                ),
            )
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Applies the collapsed/expanded presentation of the result text.
     *
     * Collapsed clips to a couple of lines so the panel stays small. Expanded
     * removes the line cap but bounds the scroll view, otherwise a long passage
     * would grow the panel until it covers the content being translated.
     */
    /**
     * Leaves the expanded state and lets the capture pipeline resume. Safe to
     * call when already collapsed.
     */
    private fun clearExpanded() {
        if (!textExpanded) return
        textExpanded = false
        applyTextExpansion()
        onExpandedChanged(false)
    }

    private fun clearFrozen() {
        if (!frozen) return
        frozen = false
        onFrozenChanged(false)
        renderFreezeState()
    }

    private fun renderFreezeState() {
        freezeButton?.apply {
            text = appContext.getString(
                if (frozen) R.string.overlay_unfreeze else R.string.overlay_freeze,
            )
            contentDescription = text
            ViewCompat.setStateDescription(
                this,
                appContext.getString(
                    if (frozen) R.string.overlay_state_frozen else R.string.overlay_state_live,
                ),
            )
        }
    }

    private fun launchPresetManager() {
        val metrics = windowManager.maximumWindowMetrics.bounds
        val region = selectedRegion
        val intent = Intent(appContext, RegionPresetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (region != null && metrics.width() > 0 && metrics.height() > 0) {
                putExtra(RegionPresetActivity.EXTRA_LEFT, region.left.toFloat() / metrics.width())
                putExtra(RegionPresetActivity.EXTRA_TOP, region.top.toFloat() / metrics.height())
                putExtra(RegionPresetActivity.EXTRA_RIGHT, region.right.toFloat() / metrics.width())
                putExtra(RegionPresetActivity.EXTRA_BOTTOM, region.bottom.toFloat() / metrics.height())
            }
        }
        appContext.startActivity(intent)
    }

    private fun handlePanelDrag(view: View, event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        val root = rootView ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                panelDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - dragStartRawX).toInt()
                val deltaY = (event.rawY - dragStartRawY).toInt()
                if (!panelDragging && (kotlin.math.abs(deltaX) > dp(4) || kotlin.math.abs(deltaY) > dp(4))) {
                    panelDragging = true
                }
                if (panelDragging) {
                    val metrics = windowManager.maximumWindowMetrics
                    val screen = metrics.bounds
                    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                    )
                    val margin = dp(8)
                    val safeLeft = insets.left + margin
                    val safeTop = insets.top + margin
                    val safeRight = screen.width() - insets.right - margin
                    val safeBottom = screen.height() - insets.bottom - margin
                    val panelWidth = root.width.takeIf { it > 0 } ?: params.width.coerceAtLeast(dp(240))
                    val panelHeight = root.height.takeIf { it > 0 } ?: dp(160)
                    params.x = (dragStartWindowX + deltaX).coerceIn(
                        safeLeft,
                        (safeRight - panelWidth).coerceAtLeast(safeLeft),
                    )
                    params.y = (dragStartWindowY + deltaY).coerceIn(
                        safeTop,
                        (safeBottom - panelHeight).coerceAtLeast(safeTop),
                    )
                    safelyUpdateViewLayout(root, params)
                    root.post { dispatchOverlayBounds() }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (panelDragging) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.performClick()
                }
                panelDragging = false
                return true
            }
        }
        return false
    }

    private fun applyTextExpansion() {
        val scroll = textScrollView ?: return

        if (textExpanded) {
            originalView?.maxLines = Int.MAX_VALUE
            originalView?.ellipsize = null
            translationView?.maxLines = Int.MAX_VALUE
            translationView?.ellipsize = null
        } else {
            originalView?.maxLines = COLLAPSED_ORIGINAL_LINES
            originalView?.ellipsize = TextUtils.TruncateAt.END
            translationView?.maxLines = COLLAPSED_TRANSLATION_LINES
            translationView?.ellipsize = TextUtils.TruncateAt.END
        }

        val height = if (textExpanded) {
            (windowManager.maximumWindowMetrics.bounds.height() * EXPANDED_HEIGHT_FRACTION).toInt()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scroll.layoutParams = (scroll.layoutParams as? LinearLayout.LayoutParams)
            ?.apply { this.height = height }
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

        expandButton?.text = appContext.getString(
            if (textExpanded) R.string.overlay_collapse else R.string.overlay_expand,
        )
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayWindowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = appContext.getString(R.string.overlay_window_title)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
    }

    private fun safelyUpdateViewLayout(
        root: View,
        params: WindowManager.LayoutParams,
    ) {
        try {
            if (root.isAttachedToWindow) {
                windowManager.updateViewLayout(root, params)
            }
        } catch (_: IllegalArgumentException) {
            // The service is concurrently shutting down.
        }
    }

    private fun registerSelectionBackCallback(root: View) {
        if (!selectionModeActive || registeredBackDispatcher != null) return
        root.findOnBackInvokedDispatcher()?.let { dispatcher ->
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                selectionBackCallback,
            )
            registeredBackDispatcher = dispatcher
        }
    }

    private fun unregisterSelectionBackCallback() {
        registeredBackDispatcher?.unregisterOnBackInvokedCallback(selectionBackCallback)
        registeredBackDispatcher = null
    }

    private fun handleSelectionExit(trigger: SelectionExitTrigger) {
        if (!selectionModeActive) return
        if (!shouldExitSelection(trigger)) return
        runOnMain {
            onStop()
            close()
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        rootView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(appContext, R.string.overlay_copy_success, Toast.LENGTH_SHORT).show()
    }

    private fun clearViewReferences() {
        if (frozen) onFrozenChanged(false)
        SelectionGestureGuardActivity.dismiss()
        unregisterSelectionBackCallback()
        selectionModeActive = false
        onOverlayBoundsChanged(null)
        rootView = null
        selectionView = null
        selectionExitButton = null
        controlPanel = null
        statusView = null
        originalView = null
        translationView = null
        attributionView = null
        textScrollView = null
        reselectButton = null
        expandButton = null
        copyOriginalButton = null
        copyTranslationButton = null
        freezeButton = null
        presetButton = null
        textExpanded = false
        frozen = false
        panelDragging = false
        layoutParams = null
        selectedRegion = null
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val COLLAPSED_ORIGINAL_LINES = 2
        const val COLLAPSED_TRANSLATION_LINES = 3

        /** Bounds the expanded panel so it cannot cover the screen it translates. */
        const val EXPANDED_HEIGHT_FRACTION = 0.45f
    }
}
