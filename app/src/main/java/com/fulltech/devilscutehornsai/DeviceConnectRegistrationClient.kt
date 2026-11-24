package com.fulltech.devilscutehornsai

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

class DeviceConnectRegistrationClient(
    context: Context,
    baseUrl: String
) {
    private val appContext = context.applicationContext
    private val serviceUrl = baseUrl.trim().trimEnd('/')

    private val keyStore = DeviceConnectKeyStore(appContext)
    private val credentialStore = DeviceConnectCredentialStore(appContext)

    init {
        require(serviceUrl.startsWith("https://")) {
            "Device Connect requires an HTTPS service URL."
        }
    }

    fun register(deviceName: String): DeviceConnectCredentials {
        val normalizedName = Normalizer.normalize(
            deviceName.trim(),
            Normalizer.Form.NFC
        )

        require(normalizedName.isNotBlank())
        require(normalizedName.toByteArray(Charsets.UTF_8).size <= 128)

        val challengeResponse = post(
            "/v1/registration/challenge",
            JSONObject()
        )

        val challengeId = challengeResponse.getString("challengeId")
        val challenge = challengeResponse.getString("challenge")

        require(challengeResponse.optString("algorithm") == "ECDSA-P256-SHA256") {
            "The registration challenge algorithm is unsupported."
        }

        val publicKeys = keyStore.getOrCreatePublicKeys()

        val signature = keyStore.signRegistration(
            challengeId = challengeId,
            challenge = challenge,
            deviceName = normalizedName
        )

        val requestBody = JSONObject().apply {
            put("challengeId", challengeId)
            put("challenge", challenge)
            put("deviceName", normalizedName)
            put("signingPublicKey", publicKeys.signingPublicKey)
            put("encryptionPublicKey", publicKeys.encryptionPublicKey)
            put(
                "signature",
                Base64.encodeToString(signature, Base64.NO_WRAP)
            )
        }

        val registrationResponse = post(
            "/v1/registration",
            requestBody
        )

        val credentials = DeviceConnectCredentials(
            deviceId = registrationResponse.getString("deviceId"),
            deviceName = registrationResponse.getString("deviceName"),
            deviceToken = registrationResponse.getString("deviceToken")
        )

        require(registrationResponse.optString("tokenType") == "Bearer")
        require(registrationResponse.optString("keyAlgorithm") == "P-256")

        credentialStore.save(credentials)

        return credentials
    }

    private fun post(
        path: String,
        body: JSONObject
    ): JSONObject {
        val connection = (URL("$serviceUrl$path").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            val requestBytes = body.toString().toByteArray(Charsets.UTF_8)

            connection.setFixedLengthStreamingMode(requestBytes.size)

            connection.outputStream.use { output ->
                output.write(requestBytes)
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0

                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break

                    total += count
                    if (total > 32_768) {
                        throw IOException("Device Connect response is too large.")
                    }

                    output.write(buffer, 0, count)
                }

                output.toString("UTF-8")
            } ?: ""

            if (status !in 200..299) {
                val message = try {
                    val json = JSONObject(responseText)
                    json.optString(
                        "message",
                        json.optString("error", "HTTP $status")
                    )
                } catch (_: Exception) {
                    "Device Connect request failed (HTTP $status)."
                }

                throw IOException(message)
            }

            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
