package com.fulltech.devilscutehornsai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GatewayTokenStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("dch_secure_settings", Context.MODE_PRIVATE)

    private val alias = "dch_gateway_token_key"

    @Synchronized
    fun save(token: String) {
        require(token.isNotBlank()) {
            "Gateway token cannot be empty."
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))

        val saved = preferences.edit()
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()

        check(saved) { "Unable to save the encrypted gateway token." }
    }

    @Synchronized
    fun read(): String? {
        val ivValue = preferences.getString("token_iv", null) ?: return null
        val dataValue = preferences.getString("token_data", null) ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivValue, Base64.NO_WRAP)
            val encrypted = Base64.decode(dataValue, Base64.NO_WRAP)

            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )

            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Unable to decrypt the gateway token. Please enter it again.",
                error
            )
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = keyStore.getKey(alias, null) as? SecretKey

        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }
}
