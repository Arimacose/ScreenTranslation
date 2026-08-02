package com.screentranslation.app.capture

import com.screentranslation.app.ml.ModelPreparationProgress
import com.screentranslation.app.ml.TranslationBackend
import com.screentranslation.app.ml.TranslationCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class TranslationCoordinatorTest {
    @Test
    fun `debounce retains only the latest text`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val backend = FakeBackend()
        val published = mutableListOf<String>()
        val coordinator = coordinator(backend, scheduler, clock, debounceMillis = 600L) {
            original, translated -> published += "$original=$translated"
        }

        coordinator.submit("first")
        scheduler.advanceBy(599L)
        assertTrue(backend.startedTexts.isEmpty())
        coordinator.submit("second")
        scheduler.advanceBy(599L)
        assertTrue(backend.startedTexts.isEmpty())
        scheduler.advanceBy(1L)
        assertEquals(listOf("second"), backend.startedTexts)

        backend.succeed("second-zh")
        assertEquals(listOf("second=second-zh"), published)
        coordinator.close()
    }

    @Test
    fun `new text cancels active request and suppresses stale result`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val backend = FakeBackend()
        val published = mutableListOf<String>()
        val coordinator = coordinator(backend, scheduler, clock) { original, translated ->
            published += "$original=$translated"
        }

        coordinator.submit("old")
        scheduler.runCurrent()
        coordinator.submit("latest")
        scheduler.runCurrent()

        assertEquals(listOf("old", "latest"), backend.startedTexts)
        assertEquals(1, backend.cancelCount)
        backend.succeed("new")
        assertEquals(listOf("latest=new"), published)
        coordinator.close()
    }

    @Test
    fun `minimum interval limits starts while allowing one pending text`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val backend = FakeBackend()
        val coordinator = coordinator(
            backend,
            scheduler,
            clock,
            minimumIntervalMillis = 750L,
        ) { _, _ -> }

        coordinator.submit("one")
        scheduler.runCurrent()
        backend.succeed("一")
        clock.now = 100L
        coordinator.submit("two")
        scheduler.runCurrent()
        assertEquals(listOf("one"), backend.startedTexts)
        scheduler.advanceBy(649L)
        assertEquals(listOf("one"), backend.startedTexts)
        scheduler.advanceBy(1L)
        assertEquals(listOf("one", "two"), backend.startedTexts)
        coordinator.close()
    }

    @Test
    fun `memory cache avoids a duplicate backend request`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val backend = FakeBackend()
        val published = mutableListOf<String>()
        val coordinator = coordinator(backend, scheduler, clock) { _, translated ->
            published += translated
        }

        coordinator.submit("repeat")
        scheduler.runCurrent()
        backend.succeed("重复")
        coordinator.submit("repeat")
        scheduler.runCurrent()

        assertEquals(listOf("repeat"), backend.startedTexts)
        assertEquals(listOf("重复", "重复"), published)
        coordinator.close()
    }

    @Test
    fun `reset cancels active work and publishes nothing`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val backend = FakeBackend()
        val published = mutableListOf<String>()
        val coordinator = coordinator(backend, scheduler, clock) { _, translated ->
            published += translated
        }

        coordinator.submit("before reset")
        scheduler.runCurrent()
        coordinator.reset()

        assertEquals(1, backend.cancelCount)
        assertTrue(published.isEmpty())
        coordinator.close()
    }

    private fun coordinator(
        backend: FakeBackend,
        scheduler: FakeScheduler,
        clock: FakeClock,
        debounceMillis: Long = 0L,
        minimumIntervalMillis: Long = 0L,
        onTranslation: (String, String) -> Unit,
    ) = TranslationCoordinator(
        backend = backend,
        debounceMillis = debounceMillis,
        minimumRequestIntervalMillis = minimumIntervalMillis,
        clockMillis = { clock.now },
        scheduler = scheduler,
        onTranslation = onTranslation,
        onError = { throw AssertionError(it) },
    )
}

private class FakeClock(var now: Long = 0L)

private class FakeScheduler(
    private val clock: FakeClock,
) : TranslationTaskScheduler {
    private data class Task(
        val dueAt: Long,
        val sequence: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val tasks = mutableListOf<Task>()
    private var sequence = 0L

    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTranslationTask {
        val scheduled = Task(clock.now + delayMillis, sequence++, task)
        tasks += scheduled
        return ScheduledTranslationTask { scheduled.cancelled = true }
    }

    fun runCurrent() = advanceBy(0L)

    fun advanceBy(millis: Long) {
        val target = clock.now + millis
        while (true) {
            val next = tasks
                .filter { !it.cancelled && it.dueAt <= target }
                .minWithOrNull(compareBy<Task> { it.dueAt }.thenBy { it.sequence })
                ?: break
            tasks.remove(next)
            clock.now = next.dueAt
            next.action()
        }
        clock.now = target
    }

    override fun close() {
        tasks.clear()
    }
}

private class FakeBackend : TranslationBackend {
    private data class Pending(
        val callback: (Result<String>) -> Unit,
        var cancelled: Boolean = false,
    )

    val startedTexts = mutableListOf<String>()
    var cancelCount = 0
    private var pending: Pending? = null

    override val cacheIdentity: String = "fake"

    override fun prepare(
        requireWifi: Boolean,
        warmRuntime: Boolean,
        onProgress: (ModelPreparationProgress) -> Unit,
        onResult: (Result<Unit>) -> Unit,
    ): TranslationCall {
        onResult(Result.success(Unit))
        return TranslationCall.NONE
    }

    override fun translate(
        text: String,
        onResult: (Result<String>) -> Unit,
    ): TranslationCall {
        check(pending == null) { "only one fake request may be active" }
        startedTexts += text
        val request = Pending(onResult)
        pending = request
        return TranslationCall {
            if (!request.cancelled) {
                request.cancelled = true
                cancelCount += 1
                if (pending === request) pending = null
                request.callback(Result.failure(CancellationException("cancelled")))
            }
        }
    }

    fun succeed(text: String) {
        val request = checkNotNull(pending)
        pending = null
        request.callback(Result.success(text))
    }

    override fun close() = Unit
}
