package com.screentranslation.app.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystoreSecretCipher(
    context: Context,
) : ApiKeySecretStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    override fun save(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "API key is blank" }
        require(normalized.length <= MAX_API_KEY_LENGTH) { "API key is too long" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val ciphertext = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit(commit = true) {
            putInt(KEY_FORMAT_VERSION, FORMAT_VERSION)
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    override fun load(): String? {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        if (preferences.getInt(KEY_FORMAT_VERSION, 0) != FORMAT_VERSION) {
            delete()
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                Charsets.UTF_8,
            ).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            delete()
            null
        }
    }

    override fun hasSecret(): Boolean =
        preferences.getInt(KEY_FORMAT_VERSION, 0) == FORMAT_VERSION &&
            !preferences.getString(KEY_IV, null).isNullOrBlank() &&
            !preferences.getString(KEY_CIPHERTEXT, null).isNullOrBlank()

    override fun delete() {
        preferences.edit(commit = true) { clear() }
        runCatching {
            keyStore().apply {
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "screen_translation_online_api_key_v1"
        const val TAG_LENGTH_BITS = 128
        const val FORMAT_VERSION = 1
        const val MAX_API_KEY_LENGTH = 8_192
        const val PREFERENCES_FILE = "online_translation_api_key"
        const val KEY_FORMAT_VERSION = "format_version"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
    }
}
