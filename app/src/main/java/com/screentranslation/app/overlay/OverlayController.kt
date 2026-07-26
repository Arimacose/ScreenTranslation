package com.screentranslation.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.screentranslation.app.R

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
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: FrameLayout? = null
    private var selectionView: RegionSelectionView? = null
    private var controlPanel: LinearLayout? = null
    private var statusView: TextView? = null
    private var originalView: TextView? = null
    private var translationView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var selectedRegion: Rect? = null
    private var currentOriginal = ""
    private var currentTranslation = ""
    private var currentStatus = ""

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
        }
        val selector = RegionSelectionView(appContext).apply {
            onRegionSelected = { region ->
                selectedRegion = Rect(region)
                onRegionChanged(Rect(region))
                switchToCompactMode()
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

            selector.visibility = View.VISIBLE
            selector.startSelection()
            panel.visibility = View.GONE
            currentStatus = appContext.getString(R.string.overlay_status_selecting)
            onOverlayBoundsChanged(null)

            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            safelyUpdateViewLayout(root, params)
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

    private fun switchToCompactMode() {
        val root = rootView ?: return
        val selector = selectionView ?: return
        val panel = controlPanel ?: return
        val params = layoutParams ?: return

        selector.visibility = View.GONE
        panel.visibility = View.VISIBLE
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
        val estimatedPanelHeight = dp(240)
        val region = selectedRegion
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
        safelyUpdateViewLayout(root, params)
        root.post { dispatchOverlayBounds() }
    }

    /**
     * Reports the actual overlay window rectangle in physical screen pixels.
     *
     * Some vendor MediaProjection implementations include secure application
     * overlays in captured frames. The capture pipeline masks this rectangle
     * before OCR so the result panel cannot recursively recognize itself.
     */
    private fun dispatchOverlayBounds() {
        val root = rootView
        val panel = controlPanel
        if (root == null ||
            panel?.visibility != View.VISIBLE ||
            !root.isAttachedToWindow ||
            root.width <= 0 ||
            root.height <= 0
        ) {
            onOverlayBoundsChanged(null)
            return
        }

        val location = IntArray(2)
        root.getLocationOnScreen(location)
        onOverlayBoundsChanged(
            Rect(
                location[0],
                location[1],
                location[0] + root.width,
                location[1] + root.height,
            ),
        )
    }

    private fun createControlPanel(): LinearLayout {
        val panelBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(242, 24, 24, 27))
            setStroke(dp(1), Color.argb(150, 82, 82, 91))
        }

        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = panelBackground
            elevation = dp(12).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        statusView = TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_status_running)
            setTextColor(Color.rgb(147, 197, 253))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        originalView = TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_waiting_for_text)
            setTextColor(Color.rgb(212, 212, 216))
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        translationView = TextView(appContext).apply {
            text = appContext.getString(R.string.overlay_waiting_for_translation)
            setTextColor(Color.rgb(167, 243, 208))
            textSize = 17f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(8))
        }
        val attributionView = TextView(appContext).apply {
            text = appContext.getString(R.string.translation_attribution)
            setTextColor(Color.rgb(161, 161, 170))
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val actionRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val reselectButton = Button(appContext).apply {
            text = appContext.getString(R.string.overlay_reselect)
            isAllCaps = false
            setOnClickListener { requestRegionSelection() }
        }
        val stopButton = Button(appContext).apply {
            text = appContext.getString(R.string.overlay_stop)
            isAllCaps = false
            setOnClickListener {
                onStop()
                close()
            }
        }
        actionRow.addView(
            reselectButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actionRow.addView(
            stopButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )

        panel.addView(statusView)
        panel.addView(originalView)
        panel.addView(translationView)
        panel.addView(attributionView)
        panel.addView(actionRow)
        return panel
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
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

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun clearViewReferences() {
        onOverlayBoundsChanged(null)
        rootView = null
        selectionView = null
        controlPanel = null
        statusView = null
        originalView = null
        translationView = null
        layoutParams = null
        selectedRegion = null
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()
}
