package com.example.lixing.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskExecutionSyncPolicyTest {
    private fun state(status: String, reason: String = "") =
        TaskExecutionSyncPolicy.State(status, if (status == "DONE") 1 else 0, status == "DONE", reason)

    @Test fun `checkin wins against later automatic missed settlement`() {
        assertEquals(SyncMerger.Decision.KEEP_LOCAL,
            TaskExecutionSyncPolicy.decide(state("DONE"), state("MISSED"), SyncMerger.Decision.APPLY_UPSERT))
        assertEquals(SyncMerger.Decision.APPLY_UPSERT,
            TaskExecutionSyncPolicy.decide(state("MISSED"), state("DONE"), SyncMerger.Decision.KEEP_LOCAL))
    }

    @Test fun `deliberate day off and revoked checkin keep ordinary conflict ordering`() {
        assertEquals(SyncMerger.Decision.APPLY_UPSERT,
            TaskExecutionSyncPolicy.decide(state("DONE"), state("SKIPPED", "DAY_OFF"),
                SyncMerger.Decision.APPLY_UPSERT))
        assertEquals(SyncMerger.Decision.KEEP_LOCAL,
            TaskExecutionSyncPolicy.decide(state("DONE"), state("PENDING", "USER_REVOKED"),
                SyncMerger.Decision.KEEP_LOCAL))
    }
}
