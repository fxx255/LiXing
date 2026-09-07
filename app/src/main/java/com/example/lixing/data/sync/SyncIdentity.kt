package com.example.lixing.data.sync

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// 单独一个 DataStore 文件：UserPreferencesRepository.replaceAll() 会清空主偏好文件，
// 设备身份必须活过备份恢复，否则两台设备会拿到同一个身份、互相覆盖彼此的日志文件。
private val Context.syncIdentityStore: DataStore<Preferences> by preferencesDataStore(name = "lixing_sync_identity")
private val KEY_DEVICE_ID = stringPreferencesKey("device_id")

/**
 * 本设备身份：随机 UUID，只存在本机，**不进入** `.lixingbackup`（每台设备必须有自己的身份）。
 */
@Singleton
class SyncIdentity @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    suspend fun deviceId(): String {
        val stored = context.syncIdentityStore.data.first()[KEY_DEVICE_ID]
        if (!stored.isNullOrBlank()) return stored
        val generated = UUID.randomUUID().toString()
        context.syncIdentityStore.edit { it[KEY_DEVICE_ID] = generated }
        return generated
    }

    /** 设备名只用于 UI 展示，不参与任何比较。 */
    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
