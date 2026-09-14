package com.example.lixing.data.plot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.ui.plot.PlotBitmapRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 PlotSpec 渲染成 PNG 并缓存到应用私有目录。
 *
 * 为什么落文件而不是把 Base64 塞进消息：图片不膨胀 JSON 与数据库，
 * 而且能直接复用现有的图片查看器（缩略图 + 点击放大 + 捏合缩放）——UI 零改动。
 * 放在 cacheDir 下由系统按需回收，同参数的图按内容哈希复用，不重复渲染。
 */
@Singleton
class PlotImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val plotsDir: File
        get() = File(context.cacheDir, "plots").apply { mkdirs() }

    /**
     * 渲染并返回 PNG 的绝对路径；任何异常都吞掉返回 null ——
     * 图表失败绝不能影响文字回答。
     *
     * 失败与降级都要留日志：以前这里彻底静默，线上出现「图片不显示」时
     * 既看不到异常也看不到参数，只能靠猜。现在把失败原因与 spec 摘要写进日志。
     */
    fun render(spec: PlotSpec): String? {
        val metrics = context.resources.displayMetrics
        val widthPx = (metrics.widthPixels * 0.92f).toInt().coerceIn(600, 1600)
        val heightPx = (widthPx * 0.58f).toInt()
        val file = File(plotsDir, "${cacheKey(spec, widthPx, heightPx)}.png")
        // 缓存命中：文件还在且非空
        if (file.exists() && file.length() > 0) return file.absolutePath

        return runCatching {
            val bitmap = PlotBitmapRenderer(metrics.density).render(spec, widthPx, heightPx)
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } finally {
                bitmap.recycle()
            }
            if (file.length() <= 0) {
                Log.w(TAG, "plot png written but empty: ${spec.title} -> ${file.name}")
                return@runCatching null
            }
            file.absolutePath
        }.onFailure { error ->
            // 带上关键参数，便于按日志复现（模型给的 spec 五花八门）
            Log.w(
                TAG,
                "plot render failed: title=${spec.title} series=${spec.series.size} " +
                    "size=${widthPx}x$heightPx",
                error,
            )
            // 写坏的文件立即删掉，避免下次命中一个 0 字节/半截的缓存
            runCatching { if (file.exists()) file.delete() }
        }.getOrNull()
    }

    private fun cacheKey(spec: PlotSpec, widthPx: Int, heightPx: Int): String =
        "%08x_%d_%d".format(spec.hashCode(), widthPx, heightPx)

    private companion object {
        const val TAG = "PlotImageStore"
    }
}
