package com.example.lixing.data.plot

import android.content.Context
import android.graphics.Bitmap
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
     */
    fun render(spec: PlotSpec): String? = runCatching {
        val metrics = context.resources.displayMetrics
        val widthPx = (metrics.widthPixels * 0.92f).toInt().coerceIn(600, 1600)
        val heightPx = (widthPx * 0.58f).toInt()
        val file = File(plotsDir, "${cacheKey(spec, widthPx, heightPx)}.png")
        if (file.exists() && file.length() > 0) return@runCatching file.absolutePath

        val bitmap = PlotBitmapRenderer(metrics.density).render(spec, widthPx, heightPx)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
        file.absolutePath
    }.getOrNull()

    private fun cacheKey(spec: PlotSpec, widthPx: Int, heightPx: Int): String =
        "%08x_%d_%d".format(spec.hashCode(), widthPx, heightPx)
}
