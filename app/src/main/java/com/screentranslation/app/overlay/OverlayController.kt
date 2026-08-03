package com.screentranslation.app.overlay

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import com.screentranslation.app.R
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
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val visualStyle = resolveOverlayVisualStyle(appContext)

    private var rootView: FrameLayout? = null
    private var selectionView: RegionSelectionView? = null
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
    private var textExpanded = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private var selectedRegion: Rect? = null
    private var currentOriginal = ""
    private var currentTranslation = ""
    private var currentStatus = ""
    private var selectionModeActive = false
    private var registeredBackDispatcher: OnBackInvokedDispatcher? = null
    private val selectionBackCallback = OnBackInvokedCallback {
        cancelSelectionFromBack()
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
                if (selectionModeActive) {
                    systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
                }
            }
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ ->
                selectionModeActive && keyCode == KeyEvent.KEYCODE_BACK
            }
        }
        val selector = RegionSelectionView(appContext).apply {
            onRegionSelected = { region ->
                selectedRegion = Rect(region)
                onRegionChanged(Rect(region))
                switchToCompactMode()
            }
            onSelectionRejected = {
                updateStatus(appContext.getString(R.string.overlay_selection_too_small))
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

        val params = createLayoutParams()
        return try {
            windowManager.addView(root, params)
            rootView = root
            selectionView = selector
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

            selector.visibility = View.VISIBLE
            selector.startSelection()
            selector.setGestureExclusionEnabled(true)

            // Selection stays visually transparent: the selector itself draws
            // one small instruction pill and the active rectangle. Hiding the
            // result/control panel avoids duplicating the hint or covering the
            // very subtitle the user is trying to locate.
            panel.visibility = View.GONE

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
            SelectionGestureGuardActivity.show(appContext, ::cancelSelectionFromBack)
        }
    }

    fun updateContent(original: String, translation: String) {
        runOnMain {
            currentOriginal = original
            currentTranslation = translation
            originalView?.text = original.ifBlank {
                appContext.getString(R.string.overlay_waiting_for_text)
            }
            translationView?.text = translation.ifBlank {
                appContext.getString(R.string.overlay_waiting_for_translation)
            }
            copyOriginalButton?.isEnabled = original.isNotBlank()
            copyTranslationButton?.isEnabled = translation.isNotBlank()
            rootView?.post { dispatchOverlayBounds() }
        }
    }

    fun updateOriginal(text: String) {
        updateContent(text, currentTranslation)
    }

    fun updateTranslation(text: String) {
        updateContent(currentOriginal, text)
    }

    fun updateStatus(status: String) {
        runOnMain {
            currentStatus = status
            statusView?.text = status
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
            dp(240)
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

        val actionRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
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
        reselectButton = createOverlayButton().apply {
            text = appContext.getString(R.string.overlay_reselect)
            isAllCaps = false
            setOnClickListener { requestRegionSelection() }
        }
        val stopButton = createOverlayButton(destructive = true).apply {
            text = appContext.getString(R.string.overlay_stop)
            isAllCaps = false
            setOnClickListener {
                onStop()
                close()
            }
        }
        actionRow.addView(
            expandButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actionRow.addView(
            reselectButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
        actionRow.addView(
            stopButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )

        panel.addView(statusView)
        panel.addView(textScrollView)
        panel.addView(attributionView)
        panel.addView(copyRow)
        panel.addView(actionRow)
        applyTextExpansion()
        return panel
    }

    private fun createOverlayButton(destructive: Boolean = false): MaterialButton {
        val activeColor = if (destructive) Color.rgb(255, 69, 58) else visualStyle.accentColor
        val states = arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        )
        return MaterialButton(appContext).apply {
            isAllCaps = false
            minHeight = dp(40)
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

    private fun cancelSelectionFromBack() {
        if (!selectionModeActive) return
        runOnMain { collapseToPanel(showResults = false) }
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
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(appContext, R.string.overlay_copy_success, Toast.LENGTH_SHORT).show()
    }

    private fun clearViewReferences() {
        SelectionGestureGuardActivity.dismiss()
        unregisterSelectionBackCallback()
        selectionModeActive = false
        onOverlayBoundsChanged(null)
        rootView = null
        selectionView = null
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
        textExpanded = false
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
