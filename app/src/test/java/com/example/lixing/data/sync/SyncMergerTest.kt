package com.example.lixing.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并算法的纯函数单测：Lamport 时钟 + 设备 ID 平局裁决 + 墓碑语义。 */
class SyncMergerTest {

    private val local = SyncMerger.LocalState()

    private fun row(clock: Long, deleted: Boolean = false, table: String = "english_entry", rowId: String = "r1") =
        SyncRow(table = table, rowId = rowId, clock = clock, deleted = deleted)

    // ---------------- 时钟比较 ----------------

    @Test
    fun `larger clock always wins regardless of device id`() {
        // 对端时钟更大，即使对端设备 ID 字典序更小，也是对端胜
        assertTrue(SyncMerger.compare(aClock = 10, aDeviceId = "aaa", bClock = 5, bDeviceId = "zzz") > 0)
        assertTrue(SyncMerger.compare(aClock = 5, aDeviceId = "zzz", bClock = 10, bDeviceId = "aaa") < 0)
    }

    @Test
    fun `equal clock breaks tie by device id`() {
        assertTrue(SyncMerger.compare(aClock = 5, aDeviceId = "b-device", bClock = 5, bDeviceId = "a-device") > 0)
        assertTrue(SyncMerger.compare(aClock = 5, aDeviceId = "a-device", bClock = 5, bDeviceId = "b-device") < 0)
    }

    @Test
    fun `same device and same clock is a draw keeping local`() {
        val decision = SyncMerger.decide(
            local = local.copy(rowClock = 7),
            remote = row(7),
            localDeviceId = "x",
            remoteDeviceId = "x",
        )
        assertEquals(SyncMerger.Decision.KEEP_LOCAL, decision)
    }

    // ---------------- 远端新于本端 ----------------

    @Test
    fun `remote newer row applies upsert`() {
        val decision = SyncMerger.decide(
            local = local.copy(rowClock = 3),
            remote = row(4),
            localDeviceId = "local",
            remoteDeviceId = "remote",
        )
        assertEquals(SyncMerger.Decision.APPLY_UPSERT, decision)
    }

    @Test
    fun `remote newer deletion applies delete even if local has newer-ish row at lower clock`() {
        val decision = SyncMerger.decide(
            local = local.copy(rowClock = 9),
            remote = row(10, deleted = true),
            localDeviceId = "local",
            remoteDeviceId = "remote",
        )
        assertEquals(SyncMerger.Decision.APPLY_DELETE, decision)
    }

    @Test
    fun `row absent locally always takes remote`() {
        assertEquals(
            SyncMerger.Decision.APPLY_UPSERT,
            SyncMerger.decide(local, row(1), "local", "remote"),
        )
        assertEquals(
            SyncMerger.Decision.APPLY_DELETE,
            SyncMerger.decide(local, row(1, deleted = true), "local", "remote"),
        )
    }

    // ---------------- 本端新于远端 ----------------

    @Test
    fun `local newer row keeps local`() {
        assertEquals(
            SyncMerger.Decision.KEEP_LOCAL,
            SyncMerger.decide(
                local = local.copy(rowClock = 8),
                remote = row(7),
                localDeviceId = "local",
                remoteDeviceId = "remote",
            ),
        )
    }

    @Test
    fun `equal clock with higher local device id keeps local`() {
        assertEquals(
            SyncMerger.Decision.KEEP_LOCAL,
            SyncMerger.decide(
                local = local.copy(rowClock = 8),
                remote = row(8),
                localDeviceId = "z-local",
                remoteDeviceId = "a-remote",
            ),
        )
    }

    @Test
    fun `equal clock with higher remote device id applies remote`() {
        assertEquals(
            SyncMerger.Decision.APPLY_UPSERT,
            SyncMerger.decide(
                local = local.copy(rowClock = 8),
                remote = row(8),
                localDeviceId = "a-local",
                remoteDeviceId = "z-remote",
            ),
        )
    }

    // ---------------- 墓碑语义 ----------------

    @Test
    fun `local tombstone newer than remote row keeps row deleted`() {
        // 本端在时钟 6 删掉了行；对端还停留在时钟 5 的旧内容
        assertEquals(
            SyncMerger.Decision.KEEP_LOCAL,
            SyncMerger.decide(
                local = SyncMerger.LocalState(rowClock = null, tombstoneClock = 6),
                remote = row(5),
                localDeviceId = "local",
                remoteDeviceId = "remote",
            ),
        )
    }

    @Test
    fun `remote row newer than local tombstone resurrects row`() {
        assertEquals(
            SyncMerger.Decision.APPLY_UPSERT,
            SyncMerger.decide(
                local = SyncMerger.LocalState(rowClock = null, tombstoneClock = 6),
                remote = row(7),
                localDeviceId = "local",
                remoteDeviceId = "remote",
            ),
        )
    }

    @Test
    fun `remote tombstone newer than local row deletes it`() {
        assertEquals(
            SyncMerger.Decision.APPLY_DELETE,
            SyncMerger.decide(
                local = SyncMerger.LocalState(rowClock = 4, tombstoneClock = null),
                remote = row(5, deleted = true),
                localDeviceId = "local",
                remoteDeviceId = "remote",
            ),
        )
    }

    @Test
    fun `local row newer than remote tombstone survives`() {
        assertEquals(
            SyncMerger.Decision.KEEP_LOCAL,
            SyncMerger.decide(
                local = SyncMerger.LocalState(rowClock = 9, tombstoneClock = null),
                remote = row(8, deleted = true),
                localDeviceId = "local",
                remoteDeviceId = "remote",
            ),
        )
    }

    @Test
    fun `effective clock of deleted row comes from tombstone`() {
        val deleted = SyncMerger.LocalState(rowClock = null, tombstoneClock = 11)
        assertEquals(11L, deleted.effectiveClock)
        assertTrue(deleted.isDeleted)
        assertTrue(!deleted.exists)
    }
}
