package com.fulltech.devilscutehornsai

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class DeviceConnectSyncClient(context: Context) {

    private val credentials =
        DeviceConnectCredentialStore(context.applicationContext).get()
            ?: throw IOException("Device is not registered.")

    private val baseUrl = DeviceConnectServiceConfig.BASE_URL
        .trim()
        .trimEnd('/')

    init {
        if (baseUrl.isBlank() || !baseUrl.startsWith("https://")) {
            throw IOException("Device Connect service URL is not configured.")
        }
    }

    fun createBatch(batchId: String): JSONObject =
        request(
            "POST",
            "/v1/transfer-batches",
            JSONObject().put("batchId", batchId)
        )

    fun uploadTransfer(body: JSONObject): JSONObject =
        request("POST", "/v1/transfers", body)

    fun getBatchStatus(batchId: String): JSONObject =
        request("GET", "/v1/transfer-batches/${batchId}/status")

    fun listPendingTransfers(): JSONObject =
        request("GET", "/v1/transfers")

    fun fetchTransfer(transferId: String): JSONObject =
        request("GET", "/v1/transfers/${transferId}")

    fun acknowledge(transferId: String) {
        request(
            "POST",
            "/v1/transfers/${transferId}/ack",
            JSONObject()
        )
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${credentials.deviceToken}"
            )
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw DeviceConnectHttpException(status, response)
            }

            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    fun deviceId(): String = credentials.deviceId
    fun deviceName(): String = credentials.deviceName
}

internal class DeviceConnectHttpException(
    val statusCode: Int,
    message: String
) : IOException("Device Connect HTTP $statusCode: $message")
