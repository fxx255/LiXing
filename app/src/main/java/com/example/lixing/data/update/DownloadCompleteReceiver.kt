package com.example.lixing.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 接收系统下载完成广播，并把消息转发给 [AppUpdateController]。
 *
 * @AndroidEntryPoint 让 Hilt 能完成字段注入。Receiver 必须在 Manifest 静态注册（详见 AndroidManifest.xml）。
 */
@AndroidEntryPoint
class DownloadCompleteReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: AppUpdateController

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        controller.onDownloadCompleted(id)
    }
}
