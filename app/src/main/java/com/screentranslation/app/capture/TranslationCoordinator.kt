package com.screentranslation.app.capture

import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationCall
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal fun interface ScheduledTranslationTask {
    fun cancel()
}

internal interface TranslationTaskScheduler : AutoCloseable {
    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTranslationTask
}

private class ExecutorTranslationTaskScheduler : TranslationTaskScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "online-translation-coordinator").apply { isDaemon = true }
        }

    override fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): ScheduledTranslationTask {
        val future: ScheduledFuture<*> = executor.schedule(
            task,
            delayMillis.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        return ScheduledTranslationTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * Coordinates whole-region Online translation without blocking OCR admission.
 *
 * It keeps one active HTTP request, one latest pending text, suppresses stale
 * generations, and stores only a small process-memory LRU cache.
 */
internal class TranslationCoordinator(
    private val backend: TranslationBackend,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val minimumRequestIntervalMillis: Long = DEFAULT_MINIMUM_INTERVAL_MILLIS,
    cacheEntries: Int = DEFAULT_CACHE_ENTRIES,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val scheduler: TranslationTaskScheduler = ExecutorTranslationTaskScheduler(),
    private val onTranslation: (originalText: String, translatedText: String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val performanceTelemetry: CapturePerformanceTelemetry? = null,
) : AutoCloseable {
    private data class Pending(
        val generation: Long,
        val text: String,
        val cacheKey: String,
        val submittedAt: Long,
    )

    private class Active(
        val generation: Long,
        val text: String,
        val cacheKey: String,
    ) {
        var call: TranslationCall? = null
        var performanceToken: CapturePerformanceTelemetry.TimingToken? = null
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, String>(cacheEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>,
        ): Boolean = size > cacheEntries
    }

    private var generation = 0L
    private var pending: Pending? = null
    private var active: Active? = null
    private var scheduled: ScheduledTranslationTask? = null
    private var lastRequestStartedAt: Long? = null
    private var closed = false

    init {
        require(debounceMillis >= 0L) { "debounceMillis cannot be negative" }
        require(minimumRequestIntervalMillis >= 0L) {
            "minimumRequestIntervalMillis cannot be negative"
        }
        require(cacheEntries >= 1) { "cacheEntries must be positive" }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            generation += 1L
            pending = Pending(
                generation = generation,
                text = text,
                cacheKey = "${backend.cacheIdentity}\u0000$text",
                submittedAt = clockMillis(),
            )
            scheduled?.cancel()
            scheduled = null
            active?.call?.cancel()
            schedulePendingLocked()
        }
    }

    fun reset() {
        synchronized(lock) {
            if (closed) return
            generation += 1L
            pending = null
            scheduled?.cancel()
            scheduled = null
            active?.call?.cancel()
        }
    }

    private fun schedulePendingLocked() {
        if (closed || active != null || pending == null || scheduled != null) return
        val next = checkNotNull(pending)
        val now = clockMillis()
        val debounceRemaining = next.submittedAt + debounceMillis - now
        val intervalRemaining = lastRequestStartedAt?.let { startedAt ->
            startedAt + minimumRequestIntervalMillis - now
        } ?: 0L
        val delay = maxOf(0L, debounceRemaining, intervalRemaining)
        scheduled = scheduler.schedule(delay, ::startPending)
    }

    private fun startPending() {
        var cacheHit: Pair<Pending, String>? = null
        var slot: Active? = null
        synchronized(lock) {
            scheduled = null
            if (closed || active != null) return
            val next = pending ?: return

            val now = clockMillis()
            val debounceRemaining = next.submittedAt + debounceMillis - now
            val intervalRemaining = lastRequestStartedAt?.let { startedAt ->
                startedAt + minimumRequestIntervalMillis - now
            } ?: 0L
            if (debounceRemaining > 0L || intervalRemaining > 0L) {
                schedulePendingLocked()
                return
            }

            pending = null
            val cached = cache[next.cacheKey]
            if (cached != null) {
                cacheHit = next to cached
            } else {
                slot = Active(next.generation, next.text, next.cacheKey).also {
                    active = it
                }
                lastRequestStartedAt = now
            }
        }

        cacheHit?.let { (next, translated) ->
            performanceTelemetry?.recordTranslationCacheHit()
            val publish = synchronized(lock) {
                !closed && next.generation == generation && pending == null
            }
            if (publish) onTranslation(next.text, translated)
            return
        }

        val request = checkNotNull(slot)
        request.performanceToken = performanceTelemetry?.startTranslation()
        val call = backend.translate(request.text) { result ->
            complete(request, result)
        }
        synchronized(lock) {
            if (active === request && request.generation == generation && !closed) {
                request.call = call
            } else {
                call.cancel()
            }
        }
    }

    private fun complete(
        request: Active,
        result: Result<String>,
    ) {
        var published: Pair<String, String>? = null
        var failure: Throwable? = null
        synchronized(lock) {
            if (active !== request) return
            active = null
            request.performanceToken?.let {
                performanceTelemetry?.finishTranslation(it, result.isSuccess)
            }

            result.fold(
                onSuccess = { translated ->
                    if (translated.isNotBlank()) {
                        cache[request.cacheKey] = translated
                        if (!closed && request.generation == generation && pending == null) {
                            published = request.text to translated
                        }
                    } else if (!closed && request.generation == generation && pending == null) {
                        failure = IllegalStateException("Translation service returned empty text")
                    }
                },
                onFailure = { error ->
                    if (!closed && request.generation == generation && pending == null) {
                        failure = error
                    }
                },
            )
            schedulePendingLocked()
        }
        published?.let { (original, translated) -> onTranslation(original, translated) }
        failure?.let(onError)
    }

    override fun close() {
        var unfinishedToken: CapturePerformanceTelemetry.TimingToken? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            pending = null
            scheduled?.cancel()
            scheduled = null
            unfinishedToken = active?.performanceToken
            active?.call?.cancel()
            active = null
            cache.clear()
        }
        unfinishedToken?.let { performanceTelemetry?.finishTranslation(it, successful = false) }
        scheduler.close()
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 600L
        const val DEFAULT_MINIMUM_INTERVAL_MILLIS = 750L
        const val DEFAULT_CACHE_ENTRIES = 128
    }
}
