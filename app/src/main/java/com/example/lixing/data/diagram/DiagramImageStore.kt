package com.example.lixing.data.diagram

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.lixing.domain.diagram.DiagramSpec
import com.example.lixing.ui.diagram.DiagramRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 [DiagramSpec] 渲染成 PNG 并**持久化**到应用私有目录。
 *
 * 与曲线图（[com.example.lixing.data.plot.PlotImageStore] 放 cacheDir）不同，
 * 框图额外落一份 **spec 台账**：
 * - PNG 放在 `filesDir/diagrams/` —— 备份按 `image_paths` 打包任意文件，
 *   因此已有备份链路天然覆盖；也不会像 cacheDir 那样被系统按需清空；
 * - 同时写 `spec_<key>.json`：万一 PNG 真的丢了（用户清理、磁盘异常），
 *   还能凭台账**原样重画**，而不是留下一个永远空白的占位。
 *
 * 任何异常都吞掉返回 null —— 画不出图绝不能影响文字回答。
 */
@Singleton
class DiagramImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(): File = File(context.filesDir, "diagrams").apply { mkdirs() }

    fun render(spec: DiagramSpec): String? {
        val key = cacheKey(spec)
        val png = File(dir(), "diagram_$key.png")
        val sidecar = File(dir(), "spec_$key.json")
        if (png.isFile && png.length() > 0) return png.absolutePath

        return runCatching {
            val bitmap = DiagramRenderer.render(spec)
            try {
                FileOutputStream(png).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } finally {
                bitmap.recycle()
            }
            if (png.length() <= 0L) {
                Log.w(TAG, "diagram png empty: ${spec.title}")
                return@runCatching null
            }
            // 台账先写后返回：PNG 丢失时可据此重建
            runCatching { sidecar.writeText(json.encodeToString(spec)) }
                .onFailure { Log.w(TAG, "diagram spec sidecar failed: ${spec.title}", it) }
            png.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "diagram render failed: title=${spec.title} nodes=${spec.nodes.size}", error)
            runCatching { if (png.isFile) png.delete() }
        }.getOrNull()
    }

    /** PNG 丢了但台账还在时，凭台账重画。 */
    fun restore(pngPath: String): String? {
        val png = File(pngPath)
        if (png.isFile && png.length() > 0) return pngPath
        val name = png.name
        if (!name.startsWith("diagram_")) return null
        val sidecar = File(png.parentFile, "spec_" + name.removePrefix("diagram_").substringBefore(".png") + ".json")
        if (!sidecar.isFile) return null
        val spec = runCatching { json.decodeFromString<DiagramSpec>(sidecar.readText()) }.getOrNull()
            ?: return null
        return render(spec)
    }

    private fun cacheKey(spec: DiagramSpec): String =
        // Bump when layout geometry or label placement changes.  Otherwise a
        // previously rendered PNG would mask the new deterministic template.
        MessageDigest.getInstance("SHA-256").digest(("v3:" + json.encodeToString(spec)).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "DiagramImageStore"
    }
}
