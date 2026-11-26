package com.fulltech.devilscutehornsai

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

data class DevicePairingInvitation(
    val sessionId: String,
    val code: String,
    val expiresAt: Long
)

data class DevicePairingClaimResult(
    val initiatorDeviceId: String,
    val initiatorDeviceName: String
)

class DeviceConnectPairingClient(
    context: Context,
    baseUrl: String
) {
    private val serviceUrl = baseUrl.trim().trimEnd('/')
    private val credentials = DeviceConnectCredentialStore(
        context.applicationContext
    ).get() ?: throw IllegalStateException("Register this device first.")

    private val keyStore = DeviceConnectKeyStore(context.applicationContext)

    init {
        require(serviceUrl.startsWith("https://")) {
            "Device Connect requires an HTTPS service URL."
        }
    }

    fun listActivePairings(): JSONObject {
        return get("/v1/pairings")
    }

    fun createInvitation(): DevicePairingInvitation {
        val secretBytes = ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }

        val secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP)
        secretBytes.fill(0)

        val ephemeralPublicKey = keyStore.createEphemeralPublicKey()

        val response = post(
            "/v1/pairings",
            JSONObject().apply {
                put("invitationSecret", secret)
                put("initiatorEphemeralPublicKey", ephemeralPublicKey)
            }
        )

        require(response.optInt("protocolVersion") == 1)

        val sessionId = response.getString("sessionId")
        val expiresAt = response.getLong("expiresAt")

        return DevicePairingInvitation(
            sessionId = sessionId,
            expiresAt = expiresAt,
            code = listOf(
                sessionId,
                secret,
                response.getString("initiatorEphemeralPublicKey"),
                expiresAt.toString()
            ).joinToString(".")
        )
    }

    fun claimInvitation(code: String): DevicePairingClaimResult {
        val parts = code.trim().split(".")

        require(parts.size == 4) {
            "Invalid invitation code. Copy the complete code from the other device."
        }

        val (sessionId, secret, initiatorPublicKey, expiryText) = parts

        require(sessionId.matches(Regex("[0-9a-fA-F-]{36}"))) {
            "Invalid pairing session ID."
        }

        val secretBytes = Base64.decode(secret, Base64.NO_WRAP)
        val publicBytes = Base64.decode(initiatorPublicKey, Base64.NO_WRAP)
        val expiresAt = expiryText.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid invitation expiry.")

        require(secretBytes.size == 32) { "Invalid invitation secret." }
        require(publicBytes.size == 65 && publicBytes[0] == 0x04.toByte()) {
            "Invalid initiator public key."
        }
        require(System.currentTimeMillis() < expiresAt) {
            "This invitation has expired."
        }

        val recipientPublicKey = keyStore.createEphemeralPublicKey()

        val signedMessage = listOf(
            "DCH-PAIRING-CLAIM-V1",
            sessionId,
            secret,
            initiatorPublicKey,
            recipientPublicKey,
            credentials.deviceId,
            expiresAt.toString(),
            "1"
        ).joinToString("\n")

        val signature = keyStore.sign(
            signedMessage.toByteArray(Charsets.UTF_8)
        )

        val response = post(
            "/v1/pairings/$sessionId/claim",
            JSONObject().apply {
                put("invitationSecret", secret)
                put("recipientEphemeralPublicKey", recipientPublicKey)
                put(
                    "signature",
                    Base64.encodeToString(signature, Base64.NO_WRAP)
                )
            }
        )

        require(response.optString("state") == "claimed")
        require(response.optInt("protocolVersion") == 1)

        return DevicePairingClaimResult(
            initiatorDeviceId = response.getString("initiatorDeviceId"),
            initiatorDeviceName = response.optString("initiatorDeviceName", "Other device")
        )
    }

    fun waitForPairing(
        initiatorDeviceId: String,
        onStatus: (String) -> Unit
    ) {
        val deadline = System.currentTimeMillis() + 110_000L

        while (System.currentTimeMillis() < deadline) {
            val result = listActivePairings()
            val pairings = result.optJSONArray("pairings")

            if (pairings != null) {
                for (index in 0 until pairings.length()) {
                    val pairing = pairings.getJSONObject(index)
                    if (pairing.optString("deviceId") == initiatorDeviceId) {
                        onStatus("Pairing confirmed with ${pairing.optString("deviceName", "Other device")}.")
                        return
                    }
                }
            }

            onStatus("Claim sent. Waiting for the other device to confirm...")
            Thread.sleep(1500)
        }

        throw IOException("Pairing confirmation timed out. Refresh paired devices to check its status.")
    }

    fun waitForClaimAndConfirm(
        sessionId: String,
        onStatus: (String) -> Unit
    ) {
        while (true) {
            val status = get("/v1/pairings/$sessionId")

            when (status.optString("state")) {
                "pending" -> {
                    if (System.currentTimeMillis() >= status.optLong("expiresAt")) {
                        throw IOException("Pairing invitation expired.")
                    }
                    onStatus("Waiting for the other device to claim...")
                    Thread.sleep(1500)
                }

                "claimed" -> {
                    onStatus("Claim received. Confirming pairing...")

                    val message = listOf(
                        "DCH-PAIRING-CONFIRM-V1",
                        sessionId,
                        status.getString("recipientDeviceId"),
                        status.getString("initiatorEphemeralPublicKey"),
                        status.getString("recipientEphemeralPublicKey"),
                        status.getLong("claimedAt").toString(),
                        status.getLong("confirmationExpiresAt").toString(),
                        "1"
                    ).joinToString("\n")

                    val signature = keyStore.sign(
                        message.toByteArray(Charsets.UTF_8)
                    )

                    val result = post(
                        "/v1/pairings/$sessionId/confirm",
                        JSONObject().put(
                            "signature",
                            Base64.encodeToString(signature, Base64.NO_WRAP)
                        )
                    )

                    require(result.optString("state") == "confirmed")
                    onStatus("PAIRING CONFIRMED.")
                    return
                }

                "confirmed" -> {
                    onStatus("PAIRING CONFIRMED.")
                    return
                }

                "expired" -> throw IOException(
                    "Pairing expired. Create a new invitation."
                )

                else -> throw IOException(
                    "Unexpected pairing state: ${status.optString("state")}"
                )
            }
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject =
        request("POST", path, body)

    private fun get(path: String): JSONObject =
        request("GET", path, null)

    private fun request(
        method: String,
        path: String,
        body: JSONObject?
    ): JSONObject {
        val connection = (URL("$serviceUrl$path").openConnection()
            as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "Authorization",
                "Bearer ${credentials.deviceToken}"
            )

            if (body != null) {
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
            }
        }

        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0

                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > 32768) {
                        throw IOException("Pairing response is too large.")
                    }
                    output.write(buffer, 0, count)
                }

                output.toString("UTF-8")
            } ?: ""

            if (status !in 200..299) {
                val message = runCatching {
                    val json = JSONObject(responseText)
                    json.optString(
                        "message",
                        json.optString("error", "HTTP $status")
                    )
                }.getOrDefault("Pairing request failed (HTTP $status).")

                throw IOException(message)
            }

            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
