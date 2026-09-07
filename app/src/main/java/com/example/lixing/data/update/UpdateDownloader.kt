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
 *  2. 提供根据 [downloadId] 查询 / 取消 / 拿本地路径的入口
 *
 * 不含 UI、不含节流、不含校验——状态机由 [AppUpdateController] 在内存里维护。
 * 进度变化由 [DownloadCompleteReceiver] 捕获 ACTION_DOWNLOAD_COMPLETE 与
 * COLUMN_STATUS 轮询两个渠道兜底。
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dm: android.app.DownloadManager
        get() = context.getSystemService() ?: error("DownloadManager 不可用")

    /** APK 缓存目录的根。我们用 App 专属 external-files-path 下的 /apk/ 子目录。 */
    private val apkDir: File
        get() = File(context.getExternalFilesDir(null), "apk").also { it.mkdirs() }

    /**
     * 开始下载。
     *
     * @param url APK 直链（云端清单里的 apkUrl）
     * @param suggestedFilename 期望的保存文件名（如 "LiXing-1.0.1.apk"）
     * @return 系统分配的 [downloadId]，可用于 [queryStatus] / [cancel]
     */
    fun enqueue(url: String, suggestedFilename: String): Long {
        // 先清掉旧的 apk 文件，避免下载时空间不足
        apkDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".apk")) it.delete() }

        val uri = Uri.parse(url)
        val req = android.app.DownloadManager.Request(uri)
            .setTitle("力行 新版本安装包")
            .setDescription("正在下载新版 APK，请保持网络")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "apk/$suggestedFilename",
            )
        return dm.enqueue(req)
    }

    /**
     * 查询当前状态。返回 null 表示下载已被用户或系统移除（罕见，主要发生在通知被点掉时）。
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

    /** 删除系统下载记录与本地文件。 */
    fun cancel(downloadId: Long) {
        runCatching { dm.remove(downloadId) }
        // 兜底清掉本地 apk 文件
        apkDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".apk")) it.delete() }
    }

    /** 当前 APK 缓存目录里实际存在的 APK 文件（已下载完成、但用户尚未安装时使用）。 */
    fun findDownloadedApk(): File? =
        apkDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".apk") }

    data class Status(
        val rawStatus: Int,
        val reason: Int,
        val localUri: String?,
    ) {
        val isRunning: Boolean get() = rawStatus == android.app.DownloadManager.STATUS_RUNNING
        val isSuccessful: Boolean get() = rawStatus == android.app.DownloadManager.STATUS_SUCCESSFUL
        val isFailed: Boolean get() = rawStatus == android.app.DownloadManager.STATUS_FAILED ||
            rawStatus == android.app.DownloadManager.STATUS_PAUSED && reason != android.app.DownloadManager.PAUSED_WAITING_TO_RETRY
        // 注：STATUS_PAUSED 在「等待重试」时不算失败；其余 PAUSED 一般也不会被 UI 看到。
    }
}
