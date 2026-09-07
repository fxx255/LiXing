package com.example.lixing.data.sync.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 凭据（账号 + 应用密码）的加密存储。
 *
 * 沿用百度凭据的做法：Android Keystore 里的 AES-GCM 密钥加密后落 SharedPreferences，
 * 明文不落盘；这个 prefs 文件在 `backup_rules.xml` / `data_extraction_rules.xml` 里
 * 已被排除，不会跟着备份跑到另一台设备上（每台设备的云配置是各自的）。
 */
@Singleton
class WebDavCredentialStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): WebDavConfig? {
        val encoded = preferences.getString(KEY_CONFIG, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, packed.copyOfRange(0, IV_BYTES)),
            )
            val plaintext = cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
            json.decodeFromString<WebDavConfig>(plaintext.toString(Charsets.UTF_8))
        }.getOrElse {
            // 密钥换了（换了手机 / 重装系统）就解不开了，清掉让用户在设置里重填
            clear()
            null
        }
    }

    @Synchronized
    fun save(config: WebDavConfig) {
        val plaintext = json.encodeToString(config).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(plaintext)
        preferences.edit()
            .putString(KEY_CONFIG, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_CONFIG).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "webdav_credentials_encrypted"
        const val KEY_CONFIG = "config"
        const val KEY_ALIAS = "lixing_webdav_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
