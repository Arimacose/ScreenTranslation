package com.screentranslation.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val visualStyle = resolveOverlayVisualStyle(appContext)
    private val blockViews = mutableMapOf<Long, TextView>()
    private var blockRoot: FrameLayout? = null
    private var controlRoot: LinearLayout? = null
    private var statusView: TextView? = null

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
            true
        } catch (_: SecurityException) {
            false
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun updateStatus(status: String) = runOnMain {
        statusView?.text = status
    }

    fun updateBlocks(blocks: List<FullScreenFrameProcessor.TranslatedScreenBlock>) = runOnMain {
        val root = blockRoot ?: return@runOnMain
        val activeIds = blocks.mapTo(hashSetOf()) { it.id }
        blockViews.keys.filter { it !in activeIds }.forEach { id ->
            blockViews.remove(id)?.let(root::removeView)
        }
        blocks.forEach { block ->
            val view = blockViews.getOrPut(block.id) {
                createTranslationView().also(root::addView)
            }
            view.text = block.translatedText
            positionTranslation(view, block)
        }
    }

    fun close() = runOnMain {
        blockRoot?.let { root ->
            runCatching { if (root.isAttachedToWindow) windowManager.removeViewImmediate(root) }
        }
        controlRoot?.let { root ->
            runCatching { if (root.isAttachedToWindow) windowManager.removeViewImmediate(root) }
        }
        blockViews.clear()
        blockRoot = null
        controlRoot = null
        statusView = null
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
                MaterialButton(appContext).apply {
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

    private fun positionTranslation(
        view: TextView,
        block: FullScreenFrameProcessor.TranslatedScreenBlock,
    ) {
        val screen = windowManager.maximumWindowMetrics.bounds
        val sourceLeft = (block.bounds.left * screen.width()).toInt()
        val sourceTop = (block.bounds.top * screen.height()).toInt()
        val sourceRight = (block.bounds.right * screen.width()).toInt()
        val desiredWidth = maxOf(sourceRight - sourceLeft, dp(96))
            .coerceAtMost((screen.width() * MAX_LABEL_WIDTH_FRACTION).toInt())
        val left = sourceLeft.coerceIn(0, (screen.width() - desiredWidth).coerceAtLeast(0))
        val initialTop = (sourceTop - dp(34)).coerceAtLeast(0)
        view.layoutParams = FrameLayout.LayoutParams(
            desiredWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = left
            topMargin = initialTop
        }
        view.post {
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@post
            val placement = resolveTranslationPlacement(
                bounds = block.bounds,
                screenWidth = screen.width(),
                screenHeight = screen.height(),
                labelHeight = view.measuredHeight,
                minimumWidth = dp(96),
                maximumWidth = (screen.width() * MAX_LABEL_WIDTH_FRACTION).toInt(),
                gap = dp(3),
            )
            if (params.leftMargin != placement.left ||
                params.topMargin != placement.top ||
                params.width != placement.width
            ) {
                params.leftMargin = placement.left
                params.topMargin = placement.top
                params.width = placement.width
                view.layoutParams = params
            }
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
        y = dp(12)
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
        const val CONTROL_WIDTH_FRACTION = 0.72f
    }
}
