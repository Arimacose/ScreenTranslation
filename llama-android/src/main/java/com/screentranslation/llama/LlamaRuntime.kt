package com.screentranslation.llama

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal in-process llama.cpp binding used by the Hy-MT2 Q4 experimental build.
 *
 * Native calls are serialized by the translation engine and guarded again in
 * C++, because llama model/context/sampler state is intentionally process-wide.
 */
class LlamaRuntime : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun loadModel(
        model: File,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        threads: Int = DEFAULT_THREADS,
    ): String {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        require(model.isFile && model.canRead()) { "Model is not readable: $model" }
        require(contextSize in 512..8_192) { "Invalid context size: $contextSize" }
        require(threads in 1..32) { "Invalid thread count: $threads" }
        return nativeLoadModel(model.absolutePath, contextSize, threads)
    }

    fun complete(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): String {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        require(prompt.isNotBlank()) { "Prompt is blank" }
        require(maxTokens in 1..1_024) { "Invalid prediction length: $maxTokens" }
        return nativeComplete(prompt, maxTokens)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            nativeClose()
        }
    }

        private external fun nativeLoadModel(
        modelPath: String,
        contextSize: Int,
        threads: Int,
    ): String

        private external fun nativeComplete(prompt: String, maxTokens: Int): String

        private external fun nativeClose()

    companion object {
        const val DEFAULT_CONTEXT_SIZE = 2_048
        const val DEFAULT_THREADS = 8
        const val DEFAULT_MAX_TOKENS = 256

        init {
            System.loadLibrary("hymt2_jni")
        }
    }
}
