package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import java.util.concurrent.Semaphore

/** Concurrency pools keyed by *physical resource*, not by provider. */
enum class LlmPool { LOCAL, CLOUD }

/**
 * MLX and Ollama-**local** run on the same host GPU/RAM → one shared pool. Ollama-**cloud**
 * (and every other cloud backend) is remote → the cloud pool, despite also being "Ollama".
 * Keying on [LlmBackend] (derived from the model suffix) is container-safe: it does not depend
 * on the endpoint host, so `host.docker.internal` inside a container still classifies correctly.
 */
fun poolFor(backend: LlmBackend): LlmPool = when (backend) {
    LlmBackend.MLX_LOCAL, LlmBackend.OLLAMA_LOCAL -> LlmPool.LOCAL
    else -> LlmPool.CLOUD
}

/**
 * Serializes LLM inference by resource. All local backends share ONE permit (default 1) because
 * they contend for the same GPU/RAM; cloud backends draw from a larger pool. This is the primitive
 * that eliminates local-model contention and lets bounded job-concurrency overlap cloud calls,
 * scraping, and PDF rendering without thrashing the GPU.
 */
object LlmGate {
    private val local = Semaphore(Config.LOCAL_LLM_MAX_CONCURRENCY.coerceAtLeast(1), /* fair = */ true)
    private val cloud = Semaphore(Config.CLOUD_LLM_MAX_CONCURRENCY.coerceAtLeast(1), true)

    private fun semaphore(backend: LlmBackend): Semaphore =
        if (poolFor(backend) == LlmPool.LOCAL) local else cloud

    /** Run [block] holding the pool's permit for [backend]; released even on exception. */
    fun <T> withPermit(backend: LlmBackend, block: () -> T): T {
        val sem = semaphore(backend)
        sem.acquire()
        try {
            return block()
        } finally {
            sem.release()
        }
    }
}
