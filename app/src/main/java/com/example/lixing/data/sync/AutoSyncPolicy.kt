package com.example.lixing.data.sync

/** Monotonic time, foreground pulls, debounced edits and retained retries. */
internal class AutoSyncPolicy {
    private var observedClock: Long? = null
    private var dirtyAt: Long? = null
    private var lastAttempt: Long? = null
    private var lastSuccess: Long? = null
    private var foregroundRequested = true
    private var failures = 0

    @Synchronized fun observe(clock: Long, now: Long) {
        if (observedClock != null && observedClock != clock) dirtyAt = now
        observedClock = clock
    }
    @Synchronized fun requestForeground() { foregroundRequested = true; failures = 0 }
    @Synchronized fun shouldSync(now: Long, foreground: Boolean): Boolean {
        val retryDelay = if (failures == 0) MIN_ATTEMPT_INTERVAL else
            (RETRY_INTERVAL * (1L shl (failures - 1).coerceAtMost(4))).coerceAtMost(MAX_RETRY_INTERVAL)
        if (lastAttempt?.let { now - it < retryDelay } == true) return false
        val dirty = dirtyAt?.let { now - it >= IdleSyncPolicy.DEFAULT_IDLE_MILLIS } == true
        val pull = foreground && (foregroundRequested || lastSuccess?.let { now - it >= PULL_INTERVAL } != false)
        return dirty || pull
    }
    @Synchronized fun started(now: Long) { lastAttempt = now }
    @Synchronized fun finished(success: Boolean, now: Long, publishedClock: Long = -1) {
        if (!success) { failures++; return }
        failures = 0
        lastSuccess = now
        foregroundRequested = false
        if ((observedClock ?: -1) <= publishedClock) {
            observedClock = publishedClock
            dirtyAt = null
        }
    }
    companion object {
        const val MIN_ATTEMPT_INTERVAL = 30_000L
        const val RETRY_INTERVAL = 60_000L
        const val MAX_RETRY_INTERVAL = 15 * 60_000L
        const val PULL_INTERVAL = 2 * 60_000L
    }
}
