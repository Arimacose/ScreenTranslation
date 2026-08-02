package com.screentranslation.app

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * A transparent, focusable activity that exists only while the full-screen
 * region selector is visible.
 *
 * Android limits gesture-exclusion rectangles and HyperOS can still route an
 * edge swipe to the target activity behind an application-overlay window. This
 * activity becomes the focused back target without accepting touch input, so
 * selection drags continue to reach [overlay.RegionSelectionView] while system
 * back gestures are consumed here instead of navigating the target app.
 */
class SelectionGestureGuardActivity : ComponentActivity() {
    private val backCallback = OnBackInvokedCallback {
        requestSelectionCancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guardRequested) {
            finishWithoutAnimation()
            return
        }

        activeActivity = WeakReference(this)
        window.addFlags(selectionGestureGuardWindowFlags())
        window.setDimAmount(0f)
        enterImmersiveSelectionMode()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            backCallback,
        )
    }

    override fun onResume() {
        super.onResume()
        if (!guardRequested) finishWithoutAnimation()
    }

    override fun onDestroy() {
        runCatching {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        }
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    private fun finishWithoutAnimation() {
        finishAndRemoveTask()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun requestSelectionCancel() {
        cancelRequest?.invoke() ?: dismiss()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveSelectionMode() {
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.decorView.systemUiVisibility = selectionGestureGuardSystemUiFlags()
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.decorView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.width > 0 && view.height > 0) {
                view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
            }
        }
        window.decorView.post {
            val view = window.decorView
            if (view.width > 0 && view.height > 0) {
                view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
            }
        }
    }

    companion object {
        @Volatile
        private var guardRequested = false

        @Volatile
        private var activeActivity: WeakReference<SelectionGestureGuardActivity>? = null

        @Volatile
        private var cancelRequest: (() -> Unit)? = null

        fun show(context: Context, onCancelRequest: () -> Unit) {
            guardRequested = true
            cancelRequest = onCancelRequest
            val intent = Intent(context, SelectionGestureGuardActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            runCatching { context.startActivity(intent) }.onFailure {
                guardRequested = false
                cancelRequest = null
            }
        }

        fun dismiss() {
            guardRequested = false
            cancelRequest = null
            activeActivity?.get()?.let { activity ->
                activity.runOnUiThread { activity.finishWithoutAnimation() }
            }
        }
    }
}

internal fun selectionGestureGuardWindowFlags(): Int =
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

@Suppress("DEPRECATION")
internal fun selectionGestureGuardSystemUiFlags(): Int =
    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
