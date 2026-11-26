package com.fulltech.devilscutehornsai

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal class DeviceConnectSendWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    companion object {
        const val KEY_SESSION_ID = "device_connect_session_id"
        private const val PREFS = "dch_device_connect_sync"
        private const val BATCH_PREFIX = "sender_batch_"
    }

    override fun doWork(): Result {
        if (DeviceConnectServiceConfig.BASE_URL.isBlank()) {
            return Result.success()
        }

        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?: return Result.failure()

        return try {
            val repository = SessionRepository(applicationContext)
            val session = repository.getSession(sessionId)
                ?: return Result.success()

            // Never transfer unfinished or unsaved answers.
            if (session.answerText.isBlank()) return Result.success()

            val question = session.questionTextForSpeech
                .ifBlank { session.approvedText }
                .ifBlank { session.reconstructedText }

            if (question.isBlank()) return Result.success()

            val completedAt = session.answerAttemptAt
                .takeIf { it > 0L }
                ?: return Result.success()

            val client = DeviceConnectSyncClient(applicationContext)

            // Keep one batch ID per completed session, including across retries.
            val preferences = applicationContext.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            val batchKey = BATCH_PREFIX + sessionId
            val batchId = preferences.getString(batchKey, null)
                ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString(batchKey, it).apply()
                }

            val batch = try {
                client.createBatch(batchId)
            } catch (error: DeviceConnectHttpException) {
                // No active paired recipients is not a failed answer.
                if (error.statusCode == 409) return Result.success()
                throw error
            }

            val recipients = batch.optJSONArray("recipients")
                ?: return Result.success()

            val initialStatus = client.getBatchStatus(batchId)
            val payloadPrefix = "sender_payload_${batchId}_"

            if (initialStatus.optString("status") == "expired") {
                preferences.edit().apply {
                    preferences.all.keys
                        .filter { it.startsWith(payloadPrefix) }
                        .forEach { remove(it) }
                }.apply()
                return Result.success()
            }

            val initialRows = initialStatus.optJSONArray("recipients")
            val initialDelivery = buildMap {
                if (initialRows != null) {
                    for (i in 0 until initialRows.length()) {
                        val row = initialRows.optJSONObject(i) ?: continue
                        put(
                            row.optString("recipient_device_id"),
                            row.optString("status")
                        )
                    }
                }
            }

            for (index in 0 until recipients.length()) {
                val recipient = recipients.optJSONObject(index)
                    ?: continue

                val recipientId = recipient.optString("deviceId")
                if (recipientId.isBlank()) continue

                val payloadKey = "sender_payload_${batchId}_$recipientId"
                val deliveryStatus = initialDelivery[recipientId]

                if (deliveryStatus == "delivered" ||
                    deliveryStatus == "expired"
                ) {
                    preferences.edit().remove(payloadKey).apply()
                    continue
                }

                if (!recipient.optBoolean("active", false)) continue

                val recipientPublicKey = recipient.optString("publicKey")

                if (recipientId.isBlank() || recipientPublicKey.isBlank()) {
                    continue
                }

                // Reuse the transfer ID assigned to this batch roster entry.
                val transferId = recipient.optString("transferId")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: UUID.nameUUIDFromBytes(
                        "$batchId:$recipientId".toByteArray(Charsets.UTF_8)
                    ).toString()

                val payload = JSONObject()
                    .put("version", 1)
                    .put("transferId", transferId)
                    .put("senderDeviceId", client.deviceId())
                    .put("senderDeviceName", client.deviceName())
                    .put("questionText", question)
                    .put("answerText", session.answerText)
                    .put("model", session.model)
                    .put("completedAt", completedAt)

                // Persist ciphertext before upload so every retry sends
                // exactly the same encrypted transfer.
                val encryptedPayload = preferences.getString(payloadKey, null)
                    ?.let { JSONObject(it) }
                    ?: run {
                        val encrypted = DeviceConnectTransferCrypto.encrypt(
                            answerJson = payload,
                            recipientPublicKeyBase64 = recipientPublicKey,
                            transferId = transferId,
                            senderDeviceId = client.deviceId(),
                            recipientDeviceId = recipientId
                        )

                        JSONObject()
                            .put("transferId", transferId)
                            .put("payloadBase64", encrypted.payloadBase64)
                            .put("payloadSha256", encrypted.payloadSha256)
                            .put("nonce", encrypted.nonce)
                            .put("ephemeralPublicKey", encrypted.ephemeralPublicKey)
                            .put("wrappedKey", encrypted.wrappedKey)
                            .put("keyNonce", encrypted.keyNonce)
                            .also {
                                check(
                                    preferences.edit()
                                        .putString(payloadKey, it.toString())
                                        .commit()
                                ) {
                                    "Unable to persist encrypted transfer."
                                }
                            }
                    }

                require(encryptedPayload.getString("transferId") == transferId) {
                    "Persisted transfer ID mismatch."
                }

                client.uploadTransfer(
                    JSONObject()
                        .put("batchId", batchId)
                        .put("transferId", transferId)
                        .put("recipientDeviceId", recipientId)
                        .put("payloadBase64", encryptedPayload.getString("payloadBase64"))
                        .put("payloadSha256", encryptedPayload.getString("payloadSha256"))
                        .put("nonce", encryptedPayload.getString("nonce"))
                        .put("ephemeralPublicKey", encryptedPayload.getString("ephemeralPublicKey"))
                        .put("wrappedKey", encryptedPayload.getString("wrappedKey"))
                        .put("keyNonce", encryptedPayload.getString("keyNonce"))
                )
            }

            // Reconcile delivery after uploads. Keep ciphertext for pending
            // recipients so a later retry can upload the identical payload.
            val finalStatus = client.getBatchStatus(batchId)
            val finalRows = finalStatus.optJSONArray("recipients")

            if (finalStatus.optString("status") == "expired") {
                preferences.edit().apply {
                    preferences.all.keys
                        .filter { it.startsWith(payloadPrefix) }
                        .forEach { remove(it) }
                }.apply()
            } else if (finalRows != null) {
                for (i in 0 until finalRows.length()) {
                    val row = finalRows.optJSONObject(i) ?: continue
                    val status = row.optString("status")

                    if (status == "delivered" || status == "expired") {
                        val recipientId = row.optString("recipient_device_id")
                        if (recipientId.isNotBlank()) {
                            preferences.edit()
                                .remove(payloadPrefix + recipientId)
                                .apply()
                        }
                    }
                }
            }

            Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Exception) {
            // Invalid data or cryptographic failures should not loop forever.
            Result.failure()
        }
    }
}

internal class DeviceConnectDeliveryMonitorWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    companion object {
        private const val PREFS = "dch_device_connect_sync"
        private const val BATCH_PREFIX = "sender_batch_"
    }

    override fun doWork(): Result {
        if (DeviceConnectServiceConfig.BASE_URL.isBlank()) {
            return Result.success()
        }

        return try {
            val client = DeviceConnectSyncClient(applicationContext)
            val preferences = applicationContext.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            val batches = preferences.all.entries
                .filter {
                    it.key.startsWith(BATCH_PREFIX) &&
                        it.value is String
                }
                .map { it.key to (it.value as String) }

            for ((batchKey, batchId) in batches) {
                if (batchId.isBlank()) continue

                val status = client.getBatchStatus(batchId)
                val payloadPrefix = "sender_payload_${batchId}_"

                val batchState = status.optString("status")

                if (batchState == "completed" || batchState == "expired") {
                    preferences.edit().apply {
                        preferences.all.keys
                            .filter { it.startsWith(payloadPrefix) }
                            .forEach { remove(it) }
                        remove(batchKey)
                    }.apply()
                    continue
                }

                val rows = status.optJSONArray("recipients")
                    ?: continue

                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val recipientStatus = row.optString("status")

                    if (recipientStatus == "delivered" ||
                        recipientStatus == "expired"
                    ) {
                        val recipientId =
                            row.optString("recipient_device_id")

                        if (recipientId.isNotBlank()) {
                            preferences.edit()
                                .remove(payloadPrefix + recipientId)
                                .apply()
                        }
                    }
                }
            }

            Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.retry()
        }
    }
}

internal class DeviceConnectReceiveWorker(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    override fun doWork(): Result {
        if (DeviceConnectServiceConfig.BASE_URL.isBlank()) {
            return Result.success()
        }

        return try {
            val client = DeviceConnectSyncClient(applicationContext)
            val keyStore = DeviceConnectKeyStore(applicationContext)
            val repository = ReceivedAnswerRepository(applicationContext)

            val transfers = client.listPendingTransfers()
                .optJSONArray("transfers")
                ?: return Result.success()

            for (index in 0 until transfers.length()) {
                val item = transfers.optJSONObject(index) ?: continue
                val transferId = item.optString("id")
                if (transferId.isBlank()) continue

                try {
                    val encrypted = client.fetchTransfer(transferId)

                    val senderDeviceId =
                        encrypted.optString("senderDeviceId")

                    if (senderDeviceId.isBlank() ||
                        senderDeviceId != item.optString("sender_device_id")
                    ) {
                        throw IOException("Sender identity mismatch.")
                    }

                    val payload = DeviceConnectTransferCrypto.decrypt(
                        payloadBase64 = encrypted.getString("payloadBase64"),
                        nonceBase64 = encrypted.getString("nonce"),
                        ephemeralPublicKeyBase64 =
                            encrypted.getString("ephemeralPublicKey"),
                        wrappedKeyBase64 = encrypted.getString("wrappedKey"),
                        keyNonceBase64 = encrypted.getString("keyNonce"),
                        expectedSha256 = encrypted.getString("payloadSha256"),
                        transferId = transferId,
                        senderDeviceId = senderDeviceId,
                        recipientDeviceId = client.deviceId(),
                        keyStore = keyStore
                    )

                    require(payload.optInt("version") == 1)
                    require(payload.getString("transferId") == transferId)
                    require(payload.getString("senderDeviceId") == senderDeviceId)

                    val received = ReceivedAnswer(
                        transferId = transferId,
                        senderDeviceId = senderDeviceId,
                        senderDeviceName = payload.optString("senderDeviceName")
                            .ifBlank { "Paired device" },
                        questionText = payload.getString("questionText"),
                        answerText = payload.getString("answerText"),
                        model = payload.optString("model"),
                        completedAt = payload.getLong("completedAt"),
                        receivedAt = System.currentTimeMillis()
                    )

                    // ACK only after the local repository confirms the save.
                    repository.save(received)
                    client.acknowledge(transferId)
                } catch (error: IOException) {
                    // Leave unacknowledged. A later run can fetch it again.
                    throw error
                } catch (error: Exception) {
                    // Do not acknowledge invalid or undecryptable transfers.
                    throw IOException("Could not safely receive transfer.", error)
                }
            }

            Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.retry()
        }
    }
}
