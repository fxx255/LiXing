package com.example.lixing.data.sync

import org.junit.Assert.*
import org.junit.Test

class AutoSyncPolicyTest {
    @Test fun `read only foreground device periodically pulls remote changes`() {
        val policy = AutoSyncPolicy()
        policy.observe(10, 0); policy.started(0); policy.finished(true, 0, 10)
        assertFalse(policy.shouldSync(119_999, true))
        assertTrue(policy.shouldSync(120_000, true))
        assertFalse(policy.shouldSync(120_000, false))
    }
    @Test fun `offline or failed push is retried without another local edit`() {
        val policy = AutoSyncPolicy()
        policy.observe(10, 0); policy.started(0); policy.finished(true, 0, 10)
        policy.observe(11, 1_000)
        assertTrue(policy.shouldSync(31_000, false))
        policy.started(31_000); policy.finished(false, 31_000)
        assertFalse(policy.shouldSync(90_999, false))
        assertTrue(policy.shouldSync(91_000, false))
    }
    @Test fun `edits made during upload remain pending`() {
        val policy = AutoSyncPolicy()
        policy.observe(10, 0); policy.started(0)
        policy.observe(11, 1_000); policy.finished(true, 2_000, 10)
        assertTrue(policy.shouldSync(31_000, false))
    }
    @Test fun `returning from background requests a throttled pull`() {
        val policy = AutoSyncPolicy()
        policy.observe(10, 0); policy.started(0); policy.finished(true, 0, 10)
        policy.requestForeground()
        assertFalse(policy.shouldSync(5_000, true))
        assertTrue(policy.shouldSync(30_000, true))
    }
}
