package com.example.lixing.rendering

import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object LatexRendering {
    private val lock = Any()

    val executor: ExecutorService = object : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor()

        override fun execute(command: Runnable) {
            delegate.execute { synchronized(lock) { command.run() } }
        }

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            delegate.awaitTermination(timeout, unit)
    }

    fun build(latex: String, textSizePx: Float, color: Int? = null): JLatexMathDrawable =
        synchronized(lock) {
            JLatexMathDrawable.builder(latex).textSize(textSizePx).apply {
                if (color != null) color(color)
            }.build()
        }
}
