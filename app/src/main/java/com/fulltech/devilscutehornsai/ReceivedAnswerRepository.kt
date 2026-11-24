package com.fulltech.devilscutehornsai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ReceivedAnswer(
    val transferId: String,
    val senderDeviceId: String,
    val senderDeviceName: String,
    val questionText: String,
    val answerText: String,
    val model: String,
    val completedAt: Long,
    val receivedAt: Long
)

class ReceivedAnswerRepository(context: Context) {

    private val root = File(
        context.applicationContext.filesDir,
        "received_answers"
    ).apply {
        check(isDirectory || mkdirs()) {
            "Unable to create received answers storage."
        }
    }

    @Synchronized
    fun save(answer: ReceivedAnswer): ReceivedAnswer {
        require(isValidId(answer.transferId)) {
            "Invalid transfer ID."
        }
        require(answer.questionText.isNotBlank()) {
            "Question text is empty."
        }
        require(answer.answerText.isNotBlank()) {
            "Answer text is empty."
        }
        require(answer.senderDeviceId.isNotBlank()) {
            "Sender device ID is missing."
        }
        require(answer.completedAt > 0L) {
            "Invalid completion timestamp."
        }

        val destination = fileFor(answer.transferId)

        if (destination.exists()) {
            val existing = read(destination)
            require(
                existing.transferId == answer.transferId &&
                existing.answerText == answer.answerText &&
                existing.questionText == answer.questionText
            ) {
                "Transfer ID already exists with different content."
            }
            return existing
        }

        val saved = answer.copy(
            receivedAt = answer.receivedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )

        val json = JSONObject().apply {
            put("version", 1)
            put("transferId", saved.transferId)
            put("senderDeviceId", saved.senderDeviceId)
            put("senderDeviceName", saved.senderDeviceName)
            put("questionText", saved.questionText)
            put("answerText", saved.answerText)
            put("model", saved.model)
            put("completedAt", saved.completedAt)
            put("receivedAt", saved.receivedAt)
        }

        val temporary = File(root, "${saved.transferId}.tmp")

        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            return saved
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun getAll(): List<ReceivedAnswer> {
        return root.listFiles()
            ?.filter {
                it.isFile &&
                it.extension == "json" &&
                isValidId(it.nameWithoutExtension)
            }
            ?.mapNotNull {
                try {
                    read(it)
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.receivedAt }
            ?: emptyList()
    }

    @Synchronized
    fun get(transferId: String): ReceivedAnswer? {
        if (!isValidId(transferId)) return null

        return try {
            val file = fileFor(transferId)
            if (file.isFile) read(file) else null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun delete(transferId: String): Boolean {
        if (!isValidId(transferId)) return false

        val file = fileFor(transferId)

        return try {
            file.canonicalFile.parentFile == root.canonicalFile &&
                (!file.exists() || file.delete())
        } catch (_: Exception) {
            false
        }
    }

    private fun fileFor(id: String): File {
        require(isValidId(id))
        return File(root, "$id.json")
    }

    private fun read(file: File): ReceivedAnswer {
        val json = JSONObject(file.readText(Charsets.UTF_8))

        require(json.optInt("version") == 1) {
            "Unsupported received-answer format."
        }

        val answer = ReceivedAnswer(
            transferId = json.getString("transferId"),
            senderDeviceId = json.getString("senderDeviceId"),
            senderDeviceName = json.optString("senderDeviceName", ""),
            questionText = json.getString("questionText"),
            answerText = json.getString("answerText"),
            model = json.optString("model", ""),
            completedAt = json.getLong("completedAt"),
            receivedAt = json.getLong("receivedAt")
        )

        require(isValidId(answer.transferId))
        require(answer.questionText.isNotBlank())
        require(answer.answerText.isNotBlank())
        require(answer.transferId == file.nameWithoutExtension)

        return answer
    }

    private fun isValidId(id: String): Boolean {
        return try {
            UUID.fromString(id).toString() == id.lowercase()
        } catch (_: Exception) {
            false
        }
    }
}
