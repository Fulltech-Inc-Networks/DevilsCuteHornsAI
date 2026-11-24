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

data class DeviceConnectCredentials(
    val deviceId: String,
    val deviceName: String,
    val deviceToken: String
)

class DeviceConnectCredentialStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        "dch_device_connect_credentials",
        Context.MODE_PRIVATE
    )

    private val keyAlias = "dch_device_connect_token_aes"

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }

    @Synchronized
    fun save(credentials: DeviceConnectCredentials) {
        require(credentials.deviceId.isNotBlank())
        require(credentials.deviceName.isNotBlank())
        require(credentials.deviceToken.matches(Regex("[A-Za-z0-9_-]{40,200}")))

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(keyAlias.toByteArray(Charsets.UTF_8))

        val encryptedToken = cipher.doFinal(
            credentials.deviceToken.toByteArray(Charsets.UTF_8)
        )

        val saved = preferences.edit()
            .putString("device_id", credentials.deviceId)
            .putString("device_name", credentials.deviceName)
            .putString("token_iv", encode(cipher.iv))
            .putString("token_data", encode(encryptedToken))
            .commit()

        check(saved) {
            "Unable to save Device Connect credentials."
        }
    }

    @Synchronized
    fun get(): DeviceConnectCredentials? {
        val deviceId = preferences.getString("device_id", null) ?: return null
        val deviceName = preferences.getString("device_name", null)
            ?: throw IllegalStateException("Device Connect name is missing.")
        val iv = preferences.getString("token_iv", null)
            ?: throw IllegalStateException("Device Connect token IV is missing.")
        val encryptedToken = preferences.getString("token_data", null)
            ?: throw IllegalStateException("Encrypted Device Connect token is missing.")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, decode(iv))
        )
        cipher.updateAAD(keyAlias.toByteArray(Charsets.UTF_8))

        val tokenBytes = cipher.doFinal(decode(encryptedToken))

        val token = try {
            tokenBytes.toString(Charsets.UTF_8)
        } finally {
            tokenBytes.fill(0)
        }

        check(token.matches(Regex("[A-Za-z0-9_-]{40,200}"))) {
            "Stored Device Connect token is invalid."
        }

        return DeviceConnectCredentials(deviceId, deviceName, token)
    }

    @Synchronized
    fun clear() {
        val cleared = preferences.edit().clear().commit()
        check(cleared) {
            "Unable to clear Device Connect credentials."
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )

        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decode(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= 8192)
        return Base64.decode(value, Base64.NO_WRAP)
    }
}
