package com.example.lixing.data

import com.example.lixing.data.assistant.mergeOcrResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOcrMergeTest {
    @Test
    fun `formula heavy latin pass is kept as an auxiliary result`() {
        val merged = mergeOcrResults(
            chinese = "已知函数，求 D(-1)",
            latin = "f(x)=2^x, D(x0)={d in R | f(x0+d)>f(x0)}",
        )

        assertTrue(merged.contains("中文 OCR 主结果"))
        assertTrue(merged.contains("公式与拉丁字符辅助结果"))
        assertTrue(merged.contains("f(x)=2^x"))
    }

    @Test
    fun `plain prose does not gain a noisy duplicate pass`() {
        assertEquals(
            "请概括文章主旨",
            mergeOcrResults("请概括文章主旨", "qing gai kuo"),
        )
    }
}
