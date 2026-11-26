package com.fulltech.devilscutehornsai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeviceConnectPublicKeys(
    val signingPublicKey: String,
    val encryptionPublicKey: String,
    val keyAlgorithm: String = "P-256"
)

class DeviceConnectKeyStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        "dch_device_connect_keys",
        Context.MODE_PRIVATE
    )

    private val signingAlias = "dch_device_connect_signing_p256"
    private val encryptionWrappingAlias = "dch_device_connect_encryption_aes"

    companion object {
        private const val CURVE = "secp256r1"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val EC_TRANSFORMATION = "SHA256withECDSA"
        private const val GCM_TAG_BITS = 128
        private const val P256_COORDINATE_BYTES = 32
    }

    @Synchronized
    fun getOrCreatePublicKeys(): DeviceConnectPublicKeys {
        val signingPair = getOrCreateSigningKeyPair()
        val encryptionPublic = getOrCreateEncryptionPublicKey()

        return DeviceConnectPublicKeys(
            signingPublicKey = encodeBase64(signingPair.public.encoded),
            encryptionPublicKey = encodeBase64(encryptionPublic)
        )
    }

    @Synchronized
    fun sign(challenge: ByteArray): ByteArray {
        require(challenge.isNotEmpty()) {
            "Registration challenge cannot be empty."
        }

        val privateKey = getOrCreateSigningKeyPair().private
        val signature = Signature.getInstance(EC_TRANSFORMATION)

        signature.initSign(privateKey)
        signature.update(challenge)

        return signature.sign()
    }

    @Synchronized
    fun signRegistration(
        challengeId: String,
        challenge: String,
        deviceName: String
    ): ByteArray {
        require(challengeId.isNotBlank())
        require(challenge.isNotBlank())

        val normalizedName = java.text.Normalizer.normalize(
            deviceName.trim(),
            java.text.Normalizer.Form.NFC
        )

        require(normalizedName.isNotBlank())
        require(normalizedName.toByteArray(Charsets.UTF_8).size <= 128)

        val keys = getOrCreatePublicKeys()

        val message = listOf(
            "DCH-DEVICE-REGISTRATION-V1",
            challengeId,
            challenge,
            normalizedName,
            keys.signingPublicKey,
            keys.encryptionPublicKey,
            "P-256"
        ).joinToString("\n")

        return sign(message.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun createEphemeralPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        val pair = generator.generateKeyPair()
        return encodeBase64(encodeRawPublicKey(pair.public as ECPublicKey))
    }

    @Synchronized
    fun deriveSharedSecret(peerPublicKeyBase64: String): ByteArray {
        val peerBytes = decodeBase64(peerPublicKeyBase64)

        require(
            peerBytes.size == 65 && peerBytes[0] == 0x04.toByte()
        ) {
            "Invalid P-256 public key."
        }

        val parameters = getP256Parameters()
        val x = BigInteger(1, peerBytes.copyOfRange(1, 33))
        val y = BigInteger(1, peerBytes.copyOfRange(33, 65))

        val peerPublicKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                java.security.spec.ECPoint(x, y),
                parameters
            )
        )

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(getEncryptionPrivateKey())
        agreement.doPhase(peerPublicKey, true)

        return agreement.generateSecret()
    }

    private fun getOrCreateSigningKeyPair(): KeyPair {
        val keyStore = loadAndroidKeyStore()
        val existingCertificate = keyStore.getCertificate(signingAlias)
        val existingPrivateKey = keyStore.getKey(signingAlias, null) as? PrivateKey

        if (existingCertificate != null && existingPrivateKey != null) {
            return KeyPair(existingCertificate.publicKey, existingPrivateKey)
        }

        if (existingCertificate != null || existingPrivateKey != null) {
            throw IllegalStateException(
                "Device signing key is incomplete. Re-registration is required."
            )
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )

        generator.initialize(
            KeyGenParameterSpec.Builder(
                signingAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )

        return generator.generateKeyPair()
    }

    private fun getOrCreateEncryptionPublicKey(): ByteArray {
        val publicValue = preferences.getString("encryption_public_key", null)
        val privateValue = preferences.getString("encryption_private_data", null)
        val ivValue = preferences.getString("encryption_private_iv", null)

        if (publicValue != null && privateValue != null && ivValue != null) {
            getEncryptionPrivateKey()

            val publicBytes = decodeBase64(publicValue)
            require(publicBytes.size == 65 && publicBytes[0] == 0x04.toByte()) {
                "Stored encryption public key is invalid."
            }

            return publicBytes
        }

        if (publicValue != null || privateValue != null || ivValue != null) {
            throw IllegalStateException(
                "Device encryption keys are incomplete. Re-registration is required."
            )
        }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))

        val pair = generator.generateKeyPair()
        val publicBytes = encodeRawPublicKey(pair.public as ECPublicKey)
        val privateBytes = pair.private.encoded

        try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            cipher.updateAAD(encryptionWrappingAlias.toByteArray(Charsets.UTF_8))

            val encryptedPrivate = cipher.doFinal(privateBytes)

            val saved = preferences.edit()
                .putString("encryption_public_key", encodeBase64(publicBytes))
                .putString("encryption_private_iv", encodeBase64(cipher.iv))
                .putString("encryption_private_data", encodeBase64(encryptedPrivate))
                .commit()

            check(saved) {
                "Unable to save encrypted Device Connect keys."
            }
        } finally {
            privateBytes.fill(0)
        }

        return publicBytes
    }

    private fun getEncryptionPrivateKey(): PrivateKey {
        val ivValue = preferences.getString("encryption_private_iv", null)
            ?: throw IllegalStateException("Encryption private key is missing.")

        val dataValue = preferences.getString("encryption_private_data", null)
            ?: throw IllegalStateException("Encrypted private key data is missing.")

        val cipher = Cipher.getInstance(AES_TRANSFORMATION)

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(GCM_TAG_BITS, decodeBase64(ivValue))
        )

        cipher.updateAAD(encryptionWrappingAlias.toByteArray(Charsets.UTF_8))

        val privateBytes = cipher.doFinal(decodeBase64(dataValue))

        return try {
            KeyFactory.getInstance("EC").generatePrivate(
                PKCS8EncodedKeySpec(privateBytes)
            )
        } finally {
            privateBytes.fill(0)
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = loadAndroidKeyStore()
        val existing = keyStore.getKey(encryptionWrappingAlias, null) as? SecretKey

        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                encryptionWrappingAlias,
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

    private fun getP256Parameters(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(CURVE))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun encodeRawPublicKey(publicKey: ECPublicKey): ByteArray {
        val x = toFixedWidth(publicKey.w.affineX)
        val y = toFixedWidth(publicKey.w.affineY)
        return byteArrayOf(0x04) + x + y
    }

    private fun toFixedWidth(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        val unsigned = if (source.size > P256_COORDINATE_BYTES) {
            source.copyOfRange(source.size - P256_COORDINATE_BYTES, source.size)
        } else {
            source
        }

        require(unsigned.size <= P256_COORDINATE_BYTES)

        return ByteArray(P256_COORDINATE_BYTES - unsigned.size) + unsigned
    }

    private fun loadAndroidKeyStore(): KeyStore {
        return KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeBase64(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= 4096) {
            "Invalid encoded key."
        }
        return Base64.decode(value, Base64.NO_WRAP)
    }
}
