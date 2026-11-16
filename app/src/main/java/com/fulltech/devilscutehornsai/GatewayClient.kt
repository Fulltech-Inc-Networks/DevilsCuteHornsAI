package com.fulltech.devilscutehornsai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class GatewayResult(
    val text: String,
    val model: String
)

class GatewayException(
    message: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null
) : Exception(message)

object GatewayClient {

    private const val BASE_URL =
        "https://dch-ai-gateway.fullgee510.workers.dev"

    private const val MAX_COMPRESSED_BYTES = 4 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 1800

    fun reconstruct(
        imageFile: File,
        pageNumber: Int,
        totalPages: Int,
        token: String
    ): GatewayResult {
        val image = prepareImage(imageFile)

        val body = JSONObject().apply {
            put("image", JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", Base64.encodeToString(image, Base64.NO_WRAP))
            })
            put("pageNumber", pageNumber)
            put("totalPages", totalPages)
        }

        return post("/v1/reconstruct", body, token)
    }

    fun answer(
        questionText: String,
        instruction: String,
        token: String
    ): GatewayResult {
        val body = JSONObject().apply {
            put("questionText", questionText)
            put("instruction", instruction)
        }

        return post("/v1/answer", body, token)
    }

    fun checkHealth(): GatewayResult {
        var connection: HttpURLConnection? = null

        try {
            connection = URL("$BASE_URL/health")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            val response = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            }

            val json = JSONObject(response)

            if (status !in 200..299 ||
                !json.optString("status").equals("online", ignoreCase = true)
            ) {
                throw GatewayException("The AI gateway is not available.")
            }

            return GatewayResult(
                text = "Gateway is online.",
                model = json.optString("model", "unknown")
            )
        } catch (error: GatewayException) {
            throw error
        } catch (error: Exception) {
            throw GatewayException(
                error.localizedMessage ?: "Unable to reach the AI gateway."
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun prepareImage(file: File): ByteArray {
        if (!file.exists() || file.length() <= 0L) {
            throw GatewayException("The captured image is missing or empty.")
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw GatewayException("The captured image could not be decoded.")
        }

        var sample = 1

        while (
            bounds.outWidth / sample > MAX_IMAGE_DIMENSION * 2 ||
            bounds.outHeight / sample > MAX_IMAGE_DIMENSION * 2
        ) {
            sample *= 2
        }

        var bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: throw GatewayException("Unable to prepare the captured image.")

        try {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )

            val matrix = Matrix()

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    matrix.postRotate(90f)

                ExifInterface.ORIENTATION_ROTATE_180 ->
                    matrix.postRotate(180f)

                ExifInterface.ORIENTATION_ROTATE_270 ->
                    matrix.postRotate(270f)

                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    matrix.postScale(-1f, 1f)

                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    matrix.postScale(1f, -1f)

                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(90f)
                }

                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postScale(-1f, 1f)
                    matrix.postRotate(270f)
                }
            }

            if (!matrix.isIdentity()) {
                val corrected = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )

                if (corrected !== bitmap) {
                    bitmap.recycle()
                    bitmap = corrected
                }
            }

            val longest = maxOf(bitmap.width, bitmap.height)

            if (longest > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / longest

                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )

                if (resized !== bitmap) {
                    bitmap.recycle()
                    bitmap = resized
                }
            }

            var quality = 82

            while (quality >= 60) {
                val output = ByteArrayOutputStream()

                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    quality,
                    output
                )

                val bytes = output.toByteArray()

                if (bytes.size <= MAX_COMPRESSED_BYTES) {
                    return bytes
                }

                quality -= 7
            }

            throw GatewayException(
                "This image is too large to upload safely. " +
                    "Please capture the page again."
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun post(
        path: String,
        body: JSONObject,
        token: String
    ): GatewayResult {
        if (token.isBlank()) {
            throw GatewayException("Gateway token is not configured.")
        }

        var connection: HttpURLConnection? = null

        try {
            connection = URL(BASE_URL + path)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${token.trim()}"
            )
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode

            val response = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            }

            val json = try {
                JSONObject(response)
            } catch (_: Exception) {
                throw GatewayException(
                    "The AI gateway returned an invalid response (HTTP $status).",
                    retryable = status == 429 || status >= 500,
                    httpStatus = status
                )
            }

            if (status !in 200..299) {
                val message = json.optString(
                    "error",
                    "AI request failed (HTTP $status)."
                )

                throw GatewayException(
                    message,
                    retryable = status == 429 || status >= 500,
                    httpStatus = status
                )
            }

            val text = json.optString("text", "").trim()

            if (text.isBlank()) {
                throw GatewayException("The AI returned an empty response.")
            }

            return GatewayResult(
                text = text,
                model = json.optString("model", "unknown")
            )
        } catch (error: GatewayException) {
            throw error
        } catch (error: Exception) {
            throw GatewayException(
                error.localizedMessage ?: "Unable to contact the AI gateway.",
                retryable = true
            )
        } finally {
            connection?.disconnect()
        }
    }
}
