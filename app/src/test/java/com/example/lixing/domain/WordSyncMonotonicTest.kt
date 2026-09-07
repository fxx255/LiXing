package com.example.lixing.domain

import com.example.lixing.domain.usecase.planSyncValue
import com.example.lixing.data.sync.maimemo.shouldThrottle
import com.example.lixing.data.sync.maimemo.MIN_INTERVAL_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSyncMonotonicTest {

    @Test
    fun `normal mode always writes the planned value`() {
        // 非自动模式（手动「同步打卡」）：以墨墨为准，允许回退
        assertEquals(60, planSyncValue(targetValue = 100, currentValue = 90, ratio = 0.6f, finished = 60, monotonic = false))
    }

    @Test
    fun `monotonic mode advances when progress grows`() {
        assertEquals(
            60,
            planSyncValue(targetValue = 100, currentValue = 20, ratio = 0.6f, finished = 60, monotonic = true),
        )
    }

    @Test
    fun `monotonic mode never rolls back recorded progress`() {
        // 墨墨接口延迟或离线学习导致比例回落时，自动同步绝不能下调已完成的打卡
        assertNull(
            planSyncValue(targetValue = 100, currentValue = 90, ratio = 0.6f, finished = 60, monotonic = true),
        )
        assertNull(
            planSyncValue(targetValue = 100, currentValue = 100, ratio = 1f, finished = 100, monotonic = true),
        )
    }

    @Test
    fun `monotonic mode still records at least one when started`() {
        // ratio>0 但取整为 0 时记 1，避免「同步了却显示没做」
        assertEquals(
            1,
            planSyncValue(targetValue = 100, currentValue = 0, ratio = 0.004f, finished = 1, monotonic = true),
        )
    }

    @Test
    fun `throttle keeps at least the minimum interval between runs`() {
        val now = 1_000_000L
        assertTrue("距上次不足 15 分钟应跳过", shouldThrottle(now, now - MIN_INTERVAL_MILLIS + 1))
        assertTrue("刚刚同步过应跳过", shouldThrottle(now, now))
        assertFalse("超过 15 分钟可以跑", shouldThrottle(now, now - MIN_INTERVAL_MILLIS - 1))
        assertFalse("从未执行过可以直接跑", shouldThrottle(now, 0L))
    }
}
