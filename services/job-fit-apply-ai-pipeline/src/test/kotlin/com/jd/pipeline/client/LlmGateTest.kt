package com.jd.pipeline.client

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The per-resource LLM gate: MLX + Ollama-local share the LOCAL pool; Ollama-cloud and the
 * other cloud backends land in CLOUD. Classification is the correctness-critical part (it must
 * not lump Ollama-local and Ollama-cloud together).
 */
class LlmGateTest {

    @Test
    fun `local backends share the LOCAL pool`() {
        assertEquals(LlmPool.LOCAL, poolFor(LlmBackend.MLX_LOCAL))
        assertEquals(LlmPool.LOCAL, poolFor(LlmBackend.OLLAMA_LOCAL))
    }

    @Test
    fun `cloud backends — including Ollama Cloud — are NOT in the local pool`() {
        assertEquals(LlmPool.CLOUD, poolFor(LlmBackend.OLLAMA_CLOUD))
        assertEquals(LlmPool.CLOUD, poolFor(LlmBackend.DEEPSEEK_CLOUD))
        assertEquals(LlmPool.CLOUD, poolFor(LlmBackend.MINIMAX_CLOUD))
    }

    @Test
    fun `withPermit runs the block and returns its value`() {
        assertEquals(42, LlmGate.withPermit(LlmBackend.MLX_LOCAL) { 42 })
    }

    @Test
    fun `LOCAL permit=1 serializes MLX and Ollama-local (no overlap)`() {
        // Default LOCAL_LLM_MAX_CONCURRENCY=1 (no env in test). Two local calls on different
        // backends must not run concurrently — max observed overlap stays 1.
        val inFlight = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val started = CountDownLatch(2)
        val backends = listOf(LlmBackend.MLX_LOCAL, LlmBackend.OLLAMA_LOCAL)

        val threads = backends.map { backend ->
            Thread {
                LlmGate.withPermit(backend) {
                    val now = inFlight.incrementAndGet()
                    maxOverlap.updateAndGet { maxOf(it, now) }
                    started.countDown()
                    Thread.sleep(150)
                    inFlight.decrementAndGet()
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(5000) }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(1, maxOverlap.get(), "MLX and Ollama-local must not overlap on the shared LOCAL permit")
    }

    @Test
    fun `withPermit releases the permit even when the block throws (no leak)`() {
        assertFailsWith<RuntimeException> {
            LlmGate.withPermit(LlmBackend.MLX_LOCAL) { throw RuntimeException("boom") }
        }
        // If the permit leaked, a subsequent LOCAL acquire would block forever.
        val acquired = CountDownLatch(1)
        Thread { LlmGate.withPermit(LlmBackend.MLX_LOCAL) { acquired.countDown() } }.start()
        assertTrue(acquired.await(2, TimeUnit.SECONDS), "permit leaked — LOCAL acquire blocked after an exception")
    }

    @Test
    fun `CLOUD pool allows overlap (permits greater than 1)`() {
        // Default CLOUD_LLM_MAX_CONCURRENCY = 4 → concurrent cloud calls should overlap.
        val inFlight = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val threads = (1..3).map {
            Thread {
                LlmGate.withPermit(LlmBackend.OLLAMA_CLOUD) {
                    val now = inFlight.incrementAndGet()
                    maxOverlap.updateAndGet { maxOf(it, now) }
                    Thread.sleep(150)
                    inFlight.decrementAndGet()
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(5000) }
        assertTrue(maxOverlap.get() > 1, "cloud calls should run concurrently, got maxOverlap=${maxOverlap.get()}")
    }

    @Test
    fun `holding a LOCAL permit does not block CLOUD`() {
        val localHeld = CountDownLatch(1)
        val releaseLocal = CountDownLatch(1)
        Thread {
            LlmGate.withPermit(LlmBackend.MLX_LOCAL) {
                localHeld.countDown()
                releaseLocal.await()
            }
        }.start()
        assertTrue(localHeld.await(2, TimeUnit.SECONDS))

        val cloudDone = CountDownLatch(1)
        Thread { LlmGate.withPermit(LlmBackend.OLLAMA_CLOUD) { cloudDone.countDown() } }.start()
        try {
            assertTrue(cloudDone.await(2, TimeUnit.SECONDS), "CLOUD was blocked while a LOCAL permit was held")
        } finally {
            releaseLocal.countDown()
        }
    }
}
