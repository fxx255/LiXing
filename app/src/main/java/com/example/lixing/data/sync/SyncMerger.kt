package com.example.lixing.data.sync

/**
 * 合并判定：**Lamport 逻辑时钟大者胜**，相等时按设备 ID 字典序打破平局。
 *
 * 刻意写成不依赖数据库与网络的纯函数，方便单测覆盖各种冲突组合。
 *
 * 不用墙钟的原因：两台设备的系统时间即使只差几分钟，也可能让"旧改动"盖掉"新改动"；
 * 逻辑时钟只有在真正发生改动和同步时才前进，天然表达因果关系。
 */
object SyncMerger {

    /** 本端一行的状态：行时钟（无行 = null）与墓碑时钟（未删 = null）。 */
    data class LocalState(
        val rowClock: Long? = null,
        val tombstoneClock: Long? = null,
    ) {
        val exists: Boolean get() = rowClock != null
        val isDeleted: Boolean get() = !exists && tombstoneClock != null

        /** 本端对这行的"有效版本"：删掉之后行本身没了，只能靠墓碑时钟代表。 */
        val effectiveClock: Long get() = maxOf(rowClock ?: 0L, tombstoneClock ?: 0L)
    }

    enum class Decision {
        /** 远端更新：写入/覆盖本端行，并清掉墓碑。 */
        APPLY_UPSERT,

        /** 远端更新：删除本端行，并写墓碑（删除才能同步给第三台设备）。 */
        APPLY_DELETE,

        /** 本端更新，忽略远端这一条。 */
        KEEP_LOCAL,
    }

    /** 时钟相同返回 0，a 胜返回正数，b 胜返回负数。相等时设备 ID 字典序大者胜。 */
    fun compare(
        aClock: Long,
        aDeviceId: String,
        bClock: Long,
        bDeviceId: String,
    ): Int = when {
        aClock != bClock -> aClock.compareTo(bClock)
        aDeviceId != bDeviceId -> aDeviceId.compareTo(bDeviceId)
        else -> 0
    }

    fun decide(
        local: LocalState,
        remote: SyncRow,
        localDeviceId: String,
        remoteDeviceId: String,
    ): Decision {
        // 远端不比本端新 → 保留本端。
        // （时钟相同且设备 ID 也相同只可能是自身日志回放，一样按"不覆盖"处理。）
        val cmp = compare(remote.clock, remoteDeviceId, local.effectiveClock, localDeviceId)
        if (cmp <= 0) return Decision.KEEP_LOCAL
        return if (remote.deleted) Decision.APPLY_DELETE else Decision.APPLY_UPSERT
    }
}
