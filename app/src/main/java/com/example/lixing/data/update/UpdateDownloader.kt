package com.example.lixing.data.update

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统 [android.app.DownloadManager] 的薄封装。
 *
 * 唯一职责：
 *  1. 把 APK 排进下载队列
 *  2. 根据下载 ID 查询状态 / 定位落盘文件 / 取消
 *
 * 关键坑（踩过）：**保存目录必须与查找目录完全一致**。
 * 统一使用 `getExternalFilesDir(Download)/apk/`（即 Android/data/&lt;包&gt;/files/Download/apk/），
 * 与 AndroidManifest 里 FileProvider 注册的 `Download/apk/` 路径保持一致。
 * 部分系统（鸿蒙）DownloadManager 落盘行为特殊，查找时优先用 COLUMN_LOCAL_URI
 * 精确问系统要路径，再兜底扫描候选目录。
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dm: android.app.DownloadManager
        get() = context.getSystemService() ?: error("DownloadManager 不可用")

    /**
     * APK 缓存目录（唯一保存点）。
     * 注意与 [enqueue] 的目标目录保持同一个：files/Download/apk/。
     */
    private val apkDir: File
        get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "apk")
            .also { it.mkdirs() }

    /** 兼容旧版本可能的落盘位置：files/apk/。 */
    private val legacyApkDir: File
        get() = File(context.getExternalFilesDir(null), "apk")

    /**
     * 开始下载。
     *
     * @param url APK 直链（云端清单里的 apkUrl，可以是加速镜像地址）
     * @param suggestedFilename 期望的保存文件名（如 "LiXing-1.0.2.apk"）
     * @return 系统分配的下载 ID，可用于 [queryStatus] / [cancel]
     */
    fun enqueue(url: String, suggestedFilename: String): Long {
        cleanOldApks()

        val req = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle("砺行 新版本安装包")
            .setDescription("正在下载新版本，完成后将提示安装")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            // 与 [apkDir] 同一个目录：files/Download/apk/<name>
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "apk/$suggestedFilename",
            )
        return dm.enqueue(req)
    }

    /**
     * 查询当前状态。返回 null 表示下载记录已被系统/用户移除。
     */
    fun queryStatus(downloadId: Long): Status? {
        return dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (!c.moveToFirst()) return@use null
            val colIndex = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
            val reasonCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_REASON)
            val localUriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI)
            if (colIndex < 0) return@use null
            val raw = c.getInt(colIndex)
            val reason = if (reasonCol >= 0) c.getInt(reasonCol) else 0
            val localUri = if (localUriCol >= 0) c.getString(localUriCol) else null
            Status(
                rawStatus = raw,
                reason = reason,
                localUri = localUri,
            )
        }
    }

    /**
     * 定位下载完成的 APK 文件。
     *
     * 查找顺序：
     *  1. **精确**：问 DownloadManager 这条下载的 COLUMN_LOCAL_URI（file:// 开头才可信）；
     *  2. **兜底**：扫描 `files/Download/apk/` 与旧位置 `files/apk/`，取最新的 .apk；
     *  3. 都没有 → 返回 null（调用方报「未找到 APK 文件」）。
     *
     * 部分系统/ROM（尤其鸿蒙）可能返回 content:// 或落盘到别处，第 1 步拿不到
     * file:// 时自动落到第 2 步的目录扫描。
     */
    fun findDownloadedApk(downloadId: Long): File? {
        // 1) 精确路径
        dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (c.moveToFirst()) {
                val col = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI)
                val local = if (col >= 0) c.getString(col) else null
                if (!local.isNullOrBlank() && local.startsWith("file://")) {
                    runCatching {
                        val f = File(Uri.parse(local).path ?: "")
                        if (f.isFile && f.length() > 0) return f
                    }
                }
            }
        }
        // 2) 兜底：扫描候选目录，取最新的 apk
        val candidates = sequenceOf(apkDir, legacyApkDir)
            .mapNotNull { dir ->
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
            }
            .toList()
        return candidates.maxByOrNull { it.lastModified() }
    }

    /** 删除系统下载记录与本地 APK 缓存（两个候选目录都清）。 */
    fun cancel(downloadId: Long) {
        runCatching { dm.remove(downloadId) }
        cleanOldApks()
    }

    /** 清掉所有本地安装包（覆盖安装成功后调用，安装包已无用处）。 */
    fun cleanAllApks() = cleanOldApks()

    /** 只保留最新下载的一个安装包，其余删除（磁盘占用上限 = 一个 APK）。 */
    fun keepOnlyLatestApk() {
        val all = sequenceOf(apkDir, legacyApkDir)
            .mapNotNull { dir ->
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
            }
            .toList()
        val latest = all.maxByOrNull { it.lastModified() } ?: return
        all.filter { it != latest }.forEach { runCatching { it.delete() } }
    }

    /** 清空两个候选目录里的旧 APK。 */
    private fun cleanOldApks() {
        sequenceOf(apkDir, legacyApkDir).forEach { dir ->
            runCatching {
                dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".apk")) it.delete() }
            }
        }
    }

    data class Status(
        val rawStatus: Int,
        val reason: Int,
        val localUri: String?,
    ) {
        val isRunning: Boolean get() = rawStatus == android.app.DownloadManager.STATUS_RUNNING
        val isSuccessful: Boolean get() = rawStatus == android.app.DownloadManager.STATUS_SUCCESSFUL
        val isFailed: Boolean get() = rawStatus == android.app.DownloadManager.STATUS_FAILED ||
            rawStatus == android.app.DownloadManager.STATUS_PAUSED && reason != android.app.DownloadManager.PAUSED_WAITING_TO_RETRY
    }
}
