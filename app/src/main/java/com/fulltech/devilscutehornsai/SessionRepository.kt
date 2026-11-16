package com.fulltech.devilscutehornsai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScanSession(
    val id: String,
    val createdAt: Long,
    val pagePaths: List<String>,
    val extractedText: String,
    val reconstructedText: String = "",
    val approvedText: String = "",
    val answerText: String = "",
    val model: String = ""
) {
    val pageCount: Int
        get() = pagePaths.size
}

class SessionRepository(context: Context) {

    private val root = File(context.filesDir, "sessions").apply {
        mkdirs()
    }

    @Synchronized
    fun saveSession(
        pages: List<File>,
        extractedText: String,
        reconstructedText: String = "",
        approvedText: String = "",
        answerText: String = "",
        model: String = ""
    ): ScanSession {
        require(pages.isNotEmpty()) { "A session must contain at least one page." }

        val id = UUID.randomUUID().toString()
        val folder = File(root, id).apply { mkdirs() }
        val savedPaths = mutableListOf<String>()

        try {
            pages.forEachIndexed { index, source ->
                require(source.exists() && source.length() > 0L) {
                    "Page ${index + 1} is missing or empty."
                }

                val destination = File(
                    folder,
                    "page_${(index + 1).toString().padStart(3, '0')}.jpg"
                )

                source.copyTo(destination, overwrite = false)
                savedPaths.add(destination.absolutePath)
            }

            val session = ScanSession(
                id = id,
                createdAt = System.currentTimeMillis(),
                pagePaths = savedPaths,
                extractedText = extractedText,
                reconstructedText = reconstructedText,
                approvedText = approvedText,
                answerText = answerText,
                model = model
            )

            writeSession(session)
            return session
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun getSessions(): List<ScanSession> {
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { folder ->
                try {
                    readSession(File(folder, "session.json"))
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    @Synchronized
    fun getSession(id: String): ScanSession? {
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return null

        return try {
            readSession(File(File(root, id), "session.json"))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun updateText(id: String, text: String): Boolean {
        val session = getSession(id) ?: return false

        return try {
            writeSession(session.copy(extractedText = text))
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun updateAiResults(
        id: String,
        reconstructedText: String,
        approvedText: String,
        answerText: String,
        model: String
    ): Boolean {
        val session = getSession(id) ?: return false

        return try {
            writeSession(
                session.copy(
                    reconstructedText = reconstructedText,
                    approvedText = approvedText,
                    answerText = answerText,
                    model = model
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun deleteSession(id: String): Boolean {
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return false

        val folder = File(root, id)
        if (!folder.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            return false
        }

        return folder.deleteRecursively()
    }

    private fun writeSession(session: ScanSession) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("createdAt", session.createdAt)
            put("extractedText", session.extractedText)
            put("reconstructedText", session.reconstructedText)
            put("approvedText", session.approvedText)
            put("answerText", session.answerText)
            put("model", session.model)
            put("pagePaths", JSONArray(session.pagePaths))
        }

        val folder = File(root, session.id)
        val temporary = File(folder, "session.tmp")
        val destination = File(folder, "session.json")

        temporary.writeText(json.toString())
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun readSession(file: File): ScanSession {
        val json = JSONObject(file.readText())
        val paths = json.getJSONArray("pagePaths")

        return ScanSession(
            id = json.getString("id"),
            createdAt = json.getLong("createdAt"),
            extractedText = json.optString("extractedText", ""),
            pagePaths = List(paths.length()) { index ->
                paths.getString(index)
            },
            reconstructedText = json.optString("reconstructedText", ""),
            approvedText = json.optString("approvedText", ""),
            answerText = json.optString("answerText", ""),
            model = json.optString("model", "")

        )
    }
}
