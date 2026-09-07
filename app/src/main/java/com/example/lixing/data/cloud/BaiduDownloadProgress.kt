package com.example.lixing.data.cloud

/** 百度网盘版本包下载进度。 */
data class BaiduDownloadProgress(
    /** 是否有正在进行的下载。 */
    val active: Boolean = false,
    /** 文件名。 */
    val fileName: String = "",
    /** 已下载字节数。 */
    val downloadedBytes: Long = 0,
    /** 总字节数（未知时为 0，进度条按不确定态展示）。 */
    val totalBytes: Long = 0,
) {
    /** 0f～1f；总大小未知时返回 0f。 */
    val fraction: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else 0f

    /** 百分比文案（总大小未知时给空串，让 UI 走不确定态）。 */
    val percentText: String
        get() = if (totalBytes > 0) " ${downloadedBytes * 100 / totalBytes}%" else ""
}
