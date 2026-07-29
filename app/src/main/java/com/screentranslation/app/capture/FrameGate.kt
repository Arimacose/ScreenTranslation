package com.screentranslation.app.capture

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Admission control for the capture pipeline: frame throttling, single-flight,
 * and generation invalidation.
 *
 * This is deliberately free of Android types. The rules are pure bookkeeping but
 * they are also where the pipeline is most likely to deadlock — a missed
 * [release] wedges it permanently — so they are separated out to be unit
 * testable on a plain JVM with a fake clock rather than needing an emulator.
 */
class FrameGate(
    private val frameIntervalMs: Long,
    private val elapsedRealtime: () -> Long,
) {
    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    @Volatile
    private var enabled = true

    /**
     * Tracked separately from [lastAcceptedAt] on purpose. Using 0 as the
     * "nothing accepted yet" sentinel makes it indistinguishable from a frame
     * legitimately accepted at timestamp 0, which silently disables throttling
     * for the frame after it.
     */
    @Volatile
    private var hasAcceptedFrame = false

    @Volatile
    private var lastAcceptedAt = 0L

    init {
        require(frameIntervalMs >= 0L) {
            "frameIntervalMs cannot be negative"
        }
    }

    val isClosed: Boolean
        get() = closed.get()

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * Claims the single-flight slot and returns the generation that this frame's
     * asynchronous work must be tagged with, or `null` when the frame has to be
     * dropped (closed, disabled, throttled, or another frame is in flight).
     *
     * A non-null result *must* be paired with an eventual [release].
     */
    fun tryAcquire(): Long? {
        if (closed.get() || !enabled || processing.get()) return null

        val now = elapsedRealtime()
        if (hasAcceptedFrame && now - lastAcceptedAt < frameIntervalMs) return null

        if (!processing.compareAndSet(false, true)) return null
        lastAcceptedAt = now
        hasAcceptedFrame = true
        return generation.get()
    }

    fun release() {
        processing.set(false)
    }

    /**
     * Invalidates work in flight. Results tagged with an older generation are
     * stale — the crop or the display geometry changed underneath them.
     */
    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(frameGeneration: Long): Boolean =
        !closed.get() && generation.get() == frameGeneration

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        enabled = false
        generation.incrementAndGet()
    }
}
