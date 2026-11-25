package com.fulltech.devilscutehornsai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceConnectTransferCryptoTest {

    @Test
    fun encryptDecryptAndRejectTampering() {
        val context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        val keyStore = DeviceConnectKeyStore(context)
        val recipientKeys = keyStore.getOrCreatePublicKeys()

        assertNotNull(recipientKeys.encryptionPublicKey)

        val transferId = UUID.randomUUID().toString()
        val senderDeviceId = UUID.randomUUID().toString()
        val recipientDeviceId = UUID.randomUUID().toString()

        val originalAnswer = JSONObject()
            .put("question", "What is photosynthesis?")
            .put("answer", "The process by which green plants make food using light.")
            .put("model", "test-model")
            .put("completedAt", "2026-11-25T10:00:00Z")

        val encrypted = DeviceConnectTransferCrypto.encrypt(
            answerJson = originalAnswer,
            recipientPublicKeyBase64 = recipientKeys.encryptionPublicKey,
            transferId = transferId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId
        )

        val decrypted = DeviceConnectTransferCrypto.decrypt(
            payloadBase64 = encrypted.payloadBase64,
            nonceBase64 = encrypted.nonce,
            ephemeralPublicKeyBase64 = encrypted.ephemeralPublicKey,
            wrappedKeyBase64 = encrypted.wrappedKey,
            keyNonceBase64 = encrypted.keyNonce,
            expectedSha256 = encrypted.payloadSha256,
            transferId = transferId,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            keyStore = keyStore
        )

        assertEquals(originalAnswer.toString(), decrypted.toString())

        var tamperingRejected = false

        try {
            DeviceConnectTransferCrypto.decrypt(
                payloadBase64 = encrypted.payloadBase64.dropLast(4) + "AAAA",
                nonceBase64 = encrypted.nonce,
                ephemeralPublicKeyBase64 = encrypted.ephemeralPublicKey,
                wrappedKeyBase64 = encrypted.wrappedKey,
                keyNonceBase64 = encrypted.keyNonce,
                expectedSha256 = encrypted.payloadSha256,
                transferId = transferId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                keyStore = keyStore
            )
        } catch (_: Exception) {
            tamperingRejected = true
        }

        assertEquals(true, tamperingRejected)
    }
}
