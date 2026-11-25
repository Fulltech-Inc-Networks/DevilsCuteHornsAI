package com.fulltech.devilscutehornsai

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedDeviceTransfer(
    val payloadBase64: String,
    val payloadSha256: String,
    val nonce: String,
    val ephemeralPublicKey: String,
    val wrappedKey: String,
    val keyNonce: String
)

object DeviceConnectTransferCrypto {

    private const val CURVE = "secp256r1"
    private const val AES = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12

    private val random = SecureRandom()

    fun encrypt(
        answerJson: JSONObject,
        recipientPublicKeyBase64: String,
        transferId: String,
        senderDeviceId: String,
        recipientDeviceId: String
    ): EncryptedDeviceTransfer {

        require(transferId.isNotBlank())
        require(senderDeviceId.isNotBlank())
        require(recipientDeviceId.isNotBlank())
        require(senderDeviceId != recipientDeviceId)

        val recipientPublicKey = decodeBase64(recipientPublicKeyBase64)
        require(
            recipientPublicKey.size == 65 &&
                recipientPublicKey[0] == 0x04.toByte()
        ) {
            "Invalid recipient encryption public key."
        }

        val parameters = getP256Parameters()
        val recipientKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                java.security.spec.ECPoint(
                    BigInteger(1, recipientPublicKey.copyOfRange(1, 33)),
                    BigInteger(1, recipientPublicKey.copyOfRange(33, 65))
                ),
                parameters
            )
        )

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val ephemeralPair = generator.generateKeyPair()

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeralPair.private)
        agreement.doPhase(recipientKey, true)

        val sharedSecret = agreement.generateSecret()

        val contentKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        val payloadNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val keyNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val context = transferContext(
            transferId,
            senderDeviceId,
            recipientDeviceId
        )

        try {
            val payloadCipher = Cipher.getInstance(AES)
            payloadCipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(contentKey, "AES"),
                GCMParameterSpec(TAG_BITS, payloadNonce)
            )
            payloadCipher.updateAAD(
                "DCH-TRANSFER-PAYLOAD-V1\n$context".toByteArray(Charsets.UTF_8)
            )

            val plaintext = answerJson.toString().toByteArray(Charsets.UTF_8)
            val ciphertext = payloadCipher.doFinal(plaintext)

            val wrappingKey = hkdf(
                sharedSecret,
                "DCH-TRANSFER-KEY-WRAP-V1\n$context".toByteArray(Charsets.UTF_8)
            )

            try {
                val keyCipher = Cipher.getInstance(AES)
                keyCipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(wrappingKey, "AES"),
                    GCMParameterSpec(TAG_BITS, keyNonce)
                )
                keyCipher.updateAAD(
                    "DCH-TRANSFER-WRAPPED-KEY-V1\n$context".toByteArray(Charsets.UTF_8)
                )

                val wrappedKey = keyCipher.doFinal(contentKey)
                val ephemeralPublic = encodeRawPublicKey(
                    ephemeralPair.public as ECPublicKey
                )

                return EncryptedDeviceTransfer(
                    payloadBase64 = encodeBase64(ciphertext),
                    payloadSha256 = sha256Hex(ciphertext),
                    nonce = encodeBase64(payloadNonce),
                    ephemeralPublicKey = encodeBase64(ephemeralPublic),
                    wrappedKey = encodeBase64(wrappedKey),
                    keyNonce = encodeBase64(keyNonce)
                )
            } finally {
                wrappingKey.fill(0)
            }
        } finally {
            sharedSecret.fill(0)
            contentKey.fill(0)
        }
    }

    fun decrypt(
        payloadBase64: String,
        nonceBase64: String,
        ephemeralPublicKeyBase64: String,
        wrappedKeyBase64: String,
        keyNonceBase64: String,
        expectedSha256: String,
        transferId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        keyStore: DeviceConnectKeyStore
    ): JSONObject {

        val ciphertext = decodeBase64(payloadBase64)
        require(ciphertext.isNotEmpty() && ciphertext.size <= 2_097_152) {
            "Encrypted answer size is invalid."
        }

        require(sha256Hex(ciphertext).equals(expectedSha256, ignoreCase = true)) {
            "Encrypted answer checksum verification failed."
        }

        val nonce = decodeBase64(nonceBase64)
        val keyNonce = decodeBase64(keyNonceBase64)
        val wrappedKey = decodeBase64(wrappedKeyBase64)

        require(nonce.size == NONCE_BYTES)
        require(keyNonce.size == NONCE_BYTES)
        require(wrappedKey.size == KEY_BYTES + 16)

        val context = transferContext(
            transferId,
            senderDeviceId,
            recipientDeviceId
        )

        val sharedSecret = keyStore.deriveSharedSecret(
            ephemeralPublicKeyBase64
        )

        try {
            val wrappingKey = hkdf(
                sharedSecret,
                "DCH-TRANSFER-KEY-WRAP-V1\n$context".toByteArray(Charsets.UTF_8)
            )

            val contentKey = try {
                val keyCipher = Cipher.getInstance(AES)
                keyCipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(wrappingKey, "AES"),
                    GCMParameterSpec(TAG_BITS, keyNonce)
                )
                keyCipher.updateAAD(
                    "DCH-TRANSFER-WRAPPED-KEY-V1\n$context".toByteArray(Charsets.UTF_8)
                )
                keyCipher.doFinal(wrappedKey)
            } finally {
                wrappingKey.fill(0)
            }

            try {
                require(contentKey.size == KEY_BYTES) {
                    "Decrypted content key has an invalid size."
                }

                val payloadCipher = Cipher.getInstance(AES)
                payloadCipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(contentKey, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce)
                )
                payloadCipher.updateAAD(
                    "DCH-TRANSFER-PAYLOAD-V1\n$context".toByteArray(Charsets.UTF_8)
                )

                val plaintext = payloadCipher.doFinal(ciphertext)

                return try {
                    JSONObject(String(plaintext, Charsets.UTF_8))
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                contentKey.fill(0)
            }
        } finally {
            sharedSecret.fill(0)
        }
    }

    private fun transferContext(
        transferId: String,
        senderDeviceId: String,
        recipientDeviceId: String
    ): String {
        return listOf(
            transferId,
            senderDeviceId,
            recipientDeviceId
        ).joinToString("\n")
    }

    private fun hkdf(
        inputKeyMaterial: ByteArray,
        info: ByteArray
    ): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256").digest(
            "DCH-TRANSFER-HKDF-SALT-V1".toByteArray(Charsets.UTF_8)
        )

        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudorandomKey = extract.doFinal(inputKeyMaterial)

        return try {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            expand.update(info)
            expand.update(0x01.toByte())
            expand.doFinal().copyOf(KEY_BYTES)
        } finally {
            salt.fill(0)
            pseudorandomKey.fill(0)
        }
    }

    private fun getP256Parameters(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(CURVE))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun encodeRawPublicKey(publicKey: ECPublicKey): ByteArray {
        return byteArrayOf(0x04) +
            toFixedWidth(publicKey.w.affineX) +
            toFixedWidth(publicKey.w.affineY)
    }

    private fun toFixedWidth(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        val unsigned = if (source.size > 32) {
            source.copyOfRange(source.size - 32, source.size)
        } else {
            source
        }

        require(unsigned.size <= 32)
        return ByteArray(32 - unsigned.size) + unsigned
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeBase64(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= 3_000_000)
        return Base64.decode(value, Base64.NO_WRAP)
    }
}
