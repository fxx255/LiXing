package com.example.lixing.data.assistant

import com.example.lixing.data.assistant.AssistantModelClient.Companion.SYSTEM_PROMPT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preserve mathematical meaning while letting the assistant choose readable plot ranges. */
class AssistantPlotPromptTest {
    @Test fun rangeUsesExtremaAndBaselineRatherThanMandatoryFlattening() {
        assertTrue(SYSTEM_PROMPT.contains("根据图像特征自主选择"))
        assertTrue(SYSTEM_PROMPT.contains("零基线到峰值"))
        assertTrue(SYSTEM_PROMPT.contains("1.15~1.35"))
        assertTrue(SYSTEM_PROMPT.contains("不是所有图必须遵守的固定比例"))
        assertFalse(SYSTEM_PROMPT.contains("曲线只占纵轴的约 1/3"))
        assertFalse(SYSTEM_PROMPT.contains("y 范围 ≈ 数据跨度 × 3"))
    }

    @Test fun exampleRangeIncludesActualQuadraticMaximum() {
        assertTrue(SYSTEM_PROMPT.contains("\"min\":-5,\"max\":5"))
        assertTrue(SYSTEM_PROMPT.contains("\"min\":0,\"max\":30"))
        assertTrue(SYSTEM_PROMPT.contains("峰值为 25"))
    }

    @Test fun nonzeroOffsetAndUnspecifiedParameterRelationsRemainExplicit() {
        assertTrue(SYSTEM_PROMPT.contains("不能漏掉常数项"))
        assertTrue(SYSTEM_PROMPT.contains("示意假设"))
        assertTrue(SYSTEM_PROMPT.contains("仅在题设或适用的物理条件支持"))
        assertTrue(SYSTEM_PROMPT.contains("不能把某个比例说成唯一答案"))
    }

    @Test fun hidingTicksIsDistinguishedFromHidingGrid() {
        assertTrue(SYSTEM_PROMPT.contains("\"ticks\":[]"))
        assertTrue(SYSTEM_PROMPT.contains("不会隐藏刻度"))
    }

    @Test fun existingPlotProtocolRemainsIntact() {
        assertTrue(SYSTEM_PROMPT.contains("不做任何外扩"))
        assertTrue(SYSTEM_PROMPT.contains("坐标刻度也必须用符号"))
        assertTrue(SYSTEM_PROMPT.contains("不要写 \\frac B2"))
        assertTrue(SYSTEM_PROMPT.contains("[[FIGURE:1]]"))
        assertTrue(SYSTEM_PROMPT.contains("一张图最多 6 条曲线"))
    }
}
