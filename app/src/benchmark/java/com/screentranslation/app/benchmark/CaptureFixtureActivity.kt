package com.screentranslation.app.benchmark

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/** A deterministic target-app fixture for region and full-screen endurance runs. */
class CaptureFixtureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fixtureView: FixtureView
    private var startedAt = 0L
    private var changeIntervalMs = DEFAULT_CHANGE_INTERVAL_MS
    private var durationMs = DEFAULT_DURATION_MS
    private var currentCycle = -1

    private val update = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val cycle = (elapsed / changeIntervalMs).toInt()
            if (cycle != currentCycle) {
                currentCycle = cycle
                fixtureView.cycle = cycle
                fixtureView.invalidate()
                Log.i(TAG, "CAPTURE_FIXTURE_V1 cycle=$cycle elapsed_ms=$elapsed")
            }
            if (elapsed < durationMs) {
                // Handler time is based on uptimeMillis while the fixture's
                // evidence timestamps use elapsedRealtime. Mixing those clocks
                // schedules the next transition late after any device sleep.
                val delayToNextBoundary = changeIntervalMs - (elapsed % changeIntervalMs)
                handler.postDelayed(this, max(50L, delayToNextBoundary))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(18, 24, 38)
        window.navigationBarColor = Color.rgb(18, 24, 38)
        changeIntervalMs = intent.getLongExtra(EXTRA_CHANGE_INTERVAL_MS, DEFAULT_CHANGE_INTERVAL_MS)
            .coerceIn(MIN_CHANGE_INTERVAL_MS, MAX_CHANGE_INTERVAL_MS)
        durationMs = intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        fixtureView = FixtureView()
        setContentView(fixtureView)
        startedAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "CAPTURE_FIXTURE_V1 started elapsed_realtime_ms=$startedAt " +
                "change_interval_ms=$changeIntervalMs duration_ms=$durationMs",
        )
        handler.post(update)
    }

    override fun onDestroy() {
        handler.removeCallbacks(update)
        Log.i(TAG, "CAPTURE_FIXTURE_V1 stopped cycle=$currentCycle")
        super.onDestroy()
    }

    private inner class FixtureView : View(this) {
        var cycle: Int = 0
        private val background = Paint().apply { color = Color.rgb(245, 247, 250) }
        private val panel = Paint().apply { color = Color.WHITE }
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 35, 60)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 35, 48) }
        private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(86, 96, 112) }
        private val chinese = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 104, 74) }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(29, 78, 216)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.scaledDensity
            val padding = 34f * density
            val contentWidth = width - (padding * 2f)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

            title.textSize = 24f * density
            body.textSize = 18f * density
            muted.textSize = 16f * density
            chinese.textSize = 18f * density
            accent.textSize = 17f * density

            var y = padding + title.textSize
            canvas.drawText("PP-OCRv6 ENDURANCE FIXTURE", padding, y, title)
            y += 28f * density
            canvas.drawText(
                "Cycle %02d  Phase %s".format(cycle, if (cycle % 2 == 0) "ALPHA" else "BETA"),
                padding,
                y,
                accent,
            )

            y += 26f * density
            val panelTop = y
            val panelBottom = height - padding
            canvas.drawRoundRect(
                padding / 2f,
                panelTop,
                width - padding / 2f,
                panelBottom,
                18f * density,
                18f * density,
                panel,
            )
            y += 38f * density
            y = drawWrappedText(
                canvas,
                "The translation service remains responsive while the foreground content " +
                    "changes in one controlled tile.",
                padding,
                y,
                contentWidth,
                body,
                8f * density,
            )
            y += 18f * density
            y = drawWrappedText(
                canvas,
                "A small subtitle changed from ${if (cycle % 2 == 0) "alpha" else "beta"} " +
                    "to ${if (cycle % 2 == 0) "beta" else "alpha"} without moving.",
                padding,
                y,
                contentWidth,
                muted,
                6f * density,
            )
            y += 26f * density
            y = drawWrappedText(
                canvas,
                "Order XT-2048 totals USD 1,249.50 on 2026-07-31; " +
                    "details remain at https://example.com/releases/2.1.",
                padding,
                y,
                contentWidth,
                accent,
                8f * density,
            )
            y += 30f * density
            y = drawWrappedText(
                canvas,
                "中文目标文本应保持原样，不应再次翻译，也不应产生译文覆盖标签。",
                padding,
                y,
                contentWidth,
                chinese,
                8f * density,
            )

            val smallY = panelBottom - 72f * density
            muted.textSize = 14f * density
            drawWrappedText(
                canvas,
                "Small subtitle ${if (cycle % 2 == 0) "BATON" else "BATMAN"}: " +
                    "dirty-tile verification must replace stale labels.",
                padding,
                smallY,
                contentWidth,
                muted,
                5f * density,
            )
        }

        private fun drawWrappedText(
            canvas: Canvas,
            text: String,
            left: Float,
            top: Float,
            maximumWidth: Float,
            paint: Paint,
            lineGap: Float,
        ): Float {
            var remaining = text.trim()
            var baseline = top
            val lineHeight = paint.fontMetrics.run { bottom - top } + lineGap
            while (remaining.isNotEmpty()) {
                var count = paint.breakText(remaining, true, maximumWidth, null).coerceAtLeast(1)
                if (count < remaining.length) {
                    val space = remaining.lastIndexOf(' ', count - 1)
                    if (space > 0) count = space
                }
                val line = remaining.substring(0, count).trimEnd()
                canvas.drawText(line, left, baseline, paint)
                remaining = remaining.substring(count).trimStart()
                baseline += lineHeight
            }
            return baseline
        }
    }

    companion object {
        const val EXTRA_CHANGE_INTERVAL_MS = "change_interval_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
        private const val TAG = "CaptureFixture"
        private const val DEFAULT_CHANGE_INTERVAL_MS = 30_000L
        private const val MIN_CHANGE_INTERVAL_MS = 5_000L
        private const val MAX_CHANGE_INTERVAL_MS = 120_000L
        private const val DEFAULT_DURATION_MS = 20L * 60L * 1_000L
        private const val MIN_DURATION_MS = 60_000L
        private const val MAX_DURATION_MS = 60L * 60L * 1_000L
    }
}
