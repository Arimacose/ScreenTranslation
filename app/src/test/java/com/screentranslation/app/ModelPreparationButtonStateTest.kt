package com.screentranslation.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPreparationButtonStateTest {
    @Test
    fun `prepared selected pair is shown ready and cannot be clicked`() {
        val state = resolveModelPreparationButtonState(
            serviceRunning = false,
            operationIdle = true,
            sameLanguage = false,
            readyForSelectedPair = true,
        )

        assertTrue(state.isReady)
        assertFalse(state.isEnabled)
    }

    @Test
    fun `changed pair restores the preparation action`() {
        val state = resolveModelPreparationButtonState(
            serviceRunning = false,
            operationIdle = true,
            sameLanguage = false,
            readyForSelectedPair = false,
        )

        assertFalse(state.isReady)
        assertTrue(state.isEnabled)
    }

    @Test
    fun `preparation stays disabled while another operation is active`() {
        val state = resolveModelPreparationButtonState(
            serviceRunning = false,
            operationIdle = false,
            sameLanguage = false,
            readyForSelectedPair = false,
        )

        assertFalse(state.isReady)
        assertFalse(state.isEnabled)
    }

    @Test
    fun `delayed initial spinner callback does not invalidate restored readiness`() {
        assertFalse(
            hasSelectedLanguagePairChanged(
                persistedSource = "en",
                persistedTarget = "zh",
                selectedSource = "en",
                selectedTarget = "zh",
            ),
        )
    }

    @Test
    fun `real language selection invalidates readiness`() {
        assertTrue(
            hasSelectedLanguagePairChanged(
                persistedSource = "en",
                persistedTarget = "zh",
                selectedSource = "ja",
                selectedTarget = "zh",
            ),
        )
    }

    @Test
    fun `retained readiness requires the same pair and current artifact identity`() {
        val pair = "en" to "zh"
        val snapshot = RetainedModelReadiness(pair, "artifact-v1", generation = 4L)

        assertTrue(retainedReadinessMatches(snapshot, pair, "artifact-v1"))
        assertFalse(retainedReadinessMatches(snapshot, "ja" to "zh", "artifact-v1"))
        assertFalse(retainedReadinessMatches(snapshot, pair, "artifact-v2"))
        assertFalse(retainedReadinessMatches(snapshot, pair, null))
    }

    @Test
    fun `deleted model or configuration cannot leave a retained false ready state`() {
        val pair = "en" to "zh"
        val viewModel = ModelReadinessViewModel()
        assertTrue(viewModel.markReady(pair, "present-model"))
        val retained = viewModel.snapshot

        assertFalse(retainedReadinessMatches(retained, pair, currentIdentity = null))
        viewModel.invalidate()
        assertNull(viewModel.snapshot)
    }

    @Test
    fun `stale verifier generation cannot publish readiness after cancellation`() {
        val pair = "en" to "zh"
        val viewModel = ModelReadinessViewModel()
        val verifierGeneration = viewModel.beginVerification()

        viewModel.invalidate()

        assertFalse(viewModel.markReady(pair, "stale", verifierGeneration))
        assertNull(viewModel.snapshot)
    }

    @Test
    fun `readiness verifier is started only while activity is started and backend is idle`() {
        assertTrue(shouldStartModelReadinessCheck(true, false, true, false))
        assertFalse(shouldStartModelReadinessCheck(false, false, true, false))
        assertFalse(shouldStartModelReadinessCheck(true, true, true, false))
        assertFalse(shouldStartModelReadinessCheck(true, false, false, false))
        assertFalse(shouldStartModelReadinessCheck(true, false, true, true))
    }

    @Test
    fun `model inventory never hashes while hidden deleting or capture service is running`() {
        assertTrue(shouldStartModelInventoryScan(true, false, false))
        assertFalse(shouldStartModelInventoryScan(false, false, false))
        assertFalse(shouldStartModelInventoryScan(true, true, false))
        assertFalse(shouldStartModelInventoryScan(true, false, true))
    }

    @Test
    fun `lifecycle cancellation owns and closes an installed verifier exactly once`() {
        val controller = GenerationOwnedResourceController<CountingCloseable>()
        val generation = controller.begin()
        val installed = CountDownLatch(1)
        val allowWorkerRelease = CountDownLatch(1)
        val installResult = AtomicBoolean(false)
        val verifier = CountingCloseable()
        val worker = Thread {
            installResult.set(controller.install(generation, verifier))
            installed.countDown()
            check(allowWorkerRelease.await(5, TimeUnit.SECONDS))
            controller.release(verifier)
        }

        worker.start()
        assertTrue(installed.await(5, TimeUnit.SECONDS))
        assertTrue(installResult.get())
        controller.cancel()
        allowWorkerRelease.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertFalse(controller.inFlight)
        assertEquals(1, verifier.closeCount.get())
        assertFalse(controller.finish(generation))
    }

    @Test
    fun `verifier created after lifecycle cancellation is rejected and closed`() {
        val controller = GenerationOwnedResourceController<CountingCloseable>()
        val generation = controller.begin()
        val verifier = CountingCloseable()

        controller.cancel()

        assertFalse(controller.install(generation, verifier))
        assertFalse(controller.inFlight)
        assertEquals(1, verifier.closeCount.get())
    }

    private class CountingCloseable : AutoCloseable {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
