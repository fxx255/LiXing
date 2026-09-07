package com.example.lixing.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空闲同步判定的纯逻辑测试：操作停止 30 秒后才允许同步，连续操作不断顺延。 */
class IdleSyncPolicyTest {

    private val policy = IdleSyncPolicy(idleMillis = 30_000L)

    @Test
    fun `no changes means nothing to sync`() {
        assertFalse(policy.shouldSync(1_000_000L))
    }

    @Test
    fun `does not sync before the idle window elapses`() {
        policy.markDirty(0L)
        assertFalse(policy.shouldSync(29_999L))
    }

    @Test
    fun `syncs once the idle window elapses`() {
        policy.markDirty(0L)
        assertTrue(policy.shouldSync(30_000L))
        assertTrue(policy.shouldSync(300_000L))
    }

    @Test
    fun `continuous changes postpone the trigger`() {
        policy.markDirty(0L)
        policy.markDirty(25_000L) // 又操作了一下：从最后一次操作重新计时
        assertFalse(policy.shouldSync(54_999L))
        assertTrue(policy.shouldSync(55_000L))
    }

    @Test
    fun `markSynced returns to the waiting state`() {
        policy.markDirty(0L)
        policy.markSynced()
        assertNull(policy.dirtyAtMillis)
        assertFalse(policy.shouldSync(999_999L))
    }
}
