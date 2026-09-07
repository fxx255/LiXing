package com.example.lixing.data.cloud

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

/** 使用 Android Keystore 加密百度访问凭证，明文不会落盘，也不会进入版本化备份。 */
@Singleton
internal class BaiduCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): BaiduCredentials? {
        val encoded = preferences.getString(KEY_CREDENTIALS, null) ?: return null
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
            json.decodeFromString<StoredBaiduCredentials>(plaintext.toString(Charsets.UTF_8))
                .toCredentials()
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    fun save(credentials: BaiduCredentials) {
        val plaintext = json.encodeToString(StoredBaiduCredentials.from(credentials))
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(plaintext)
        preferences.edit()
            .putString(KEY_CREDENTIALS, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_CREDENTIALS).apply()
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
        const val PREFS_NAME = "baidu_credentials_encrypted"
        const val KEY_CREDENTIALS = "credentials"
        const val KEY_ALIAS = "lixing_baidu_oauth_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
