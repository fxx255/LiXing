package com.example.lixing.data.sync

/** An automatic missed mark is an observation of absent data, not a user edit. */
internal object TaskExecutionSyncPolicy {
    data class State(val status: String, val actualValue: Int, val checked: Boolean, val skipReason: String) {
        val engaged: Boolean get() = status == "DONE" || status == "PARTIAL" || actualValue > 0 || checked
        val revoked: Boolean get() = skipReason == "USER_REVOKED" && !engaged
        val automatic: Boolean get() = !engaged && when (status) {
            "PENDING" -> skipReason.isBlank()
            "MISSED" -> true
            "SKIPPED" -> skipReason.isBlank()
            else -> false
        }
    }

    fun decide(local: State?, remote: State, usual: SyncMerger.Decision): SyncMerger.Decision {
        if (local == null) return usual
        if (local.engaged && remote.automatic) return SyncMerger.Decision.KEEP_LOCAL
        if (local.automatic && remote.engaged) return SyncMerger.Decision.APPLY_UPSERT
        if (local.revoked && remote.automatic) return SyncMerger.Decision.KEEP_LOCAL
        if (local.automatic && remote.revoked) return SyncMerger.Decision.APPLY_UPSERT
        return usual
    }
}
