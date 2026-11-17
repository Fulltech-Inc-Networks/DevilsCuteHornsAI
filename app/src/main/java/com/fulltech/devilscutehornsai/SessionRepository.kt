package com.fulltech.devilscutehornsai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PageProcessing(
    val index: Int,
    val status: String = "pending",
    val reconstructedText: String = "",
    val errorCategory: String = "",
    val errorMessage: String = "",
    val attempts: Int = 0,
    val updatedAt: Long = 0L,
    val nextRetryAt: Long = 0L
)

data class ScanSession(
    val id: String,
    val createdAt: Long,
    val pagePaths: List<String>,
    val extractedText: String,
    val reconstructedText: String = "",
    val approvedText: String = "",
    val answerText: String = "",
    val model: String = "",
    val pageResults: List<PageProcessing> = emptyList(),
    val status: String = "pending",
    val updatedAt: Long = 0L,
    val answerStatus: String = "",
    val answerAttemptAt: Long = 0L,
    val questionTextForSpeech: String = ""
) {
    val pageCount: Int
        get() = pagePaths.size

    val successfulPageCount: Int
        get() = pageResults.count { it.status == "succeeded" }

    val failedPageCount: Int
        get() = pageResults.count { it.status == "failed" }
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

            val now = System.currentTimeMillis()
            val session = ScanSession(
                id = id,
                createdAt = now,
                pagePaths = savedPaths,
                extractedText = extractedText,
                reconstructedText = reconstructedText,
                approvedText = approvedText,
                answerText = answerText,
                model = model,
                pageResults = savedPaths.indices.map { index ->
                    PageProcessing(
                        index = index + 1,
                        status = "pending",
                        updatedAt = now
                    )
                },
                status = if (answerText.isNotBlank()) "answered"
                    else if (approvedText.isNotBlank()) "approved"
                    else "pending",
                updatedAt = now
            )

            writeSession(session)
            return session
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun createDraftSession(): ScanSession {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val folder = File(root, id)

        check(folder.mkdirs()) { "Unable to create a scan session." }

        val session = ScanSession(
            id = id,
            createdAt = now,
            pagePaths = emptyList(),
            extractedText = "",
            status = "capturing",
            updatedAt = now
        )

        try {
            writeSession(session)
            return session
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun addPage(id: String, source: File): ScanSession {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) {
            "Invalid session identifier."
        }
        require(source.exists() && source.isFile && source.length() > 0L) {
            "The captured page is missing or empty."
        }

        val session = getSession(id)
            ?: throw IllegalStateException("The scan session could not be found.")

        val index = session.pagePaths.size + 1
        val folder = File(root, id)
        val destination = File(
            folder,
            "page_${index.toString().padStart(3, '0')}.jpg"
        )

        check(!destination.exists()) { "This page has already been saved." }

        source.copyTo(destination, overwrite = false)

        try {
            val now = System.currentTimeMillis()
            val updated = session.copy(
                pagePaths = session.pagePaths + destination.absolutePath,
                pageResults = session.pageResults + PageProcessing(
                    index = index,
                    status = "pending",
                    updatedAt = now
                ),
                status = "pending",
                updatedAt = now
            )

            writeSession(updated)
            return updated
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    @Synchronized
    fun removeLastPage(id: String): Boolean {
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return false

        val session = getSession(id) ?: return false
        if (session.pagePaths.isEmpty()) return false
        if (session.status !in setOf("capturing", "pending")) return false

        val lastPath = session.pagePaths.last()
        val lastFile = File(lastPath)
        val folder = File(root, id)

        if (!lastFile.canonicalPath.startsWith(folder.canonicalPath + File.separator)) {
            return false
        }

        val newPaths = session.pagePaths.dropLast(1)
        val newResults = session.pageResults.filterNot {
            it.index == session.pagePaths.size
        }

        val now = System.currentTimeMillis()
        val updated = session.copy(
            pagePaths = newPaths,
            pageResults = newResults,
            status = if (newPaths.isEmpty()) "capturing" else "pending",
            updatedAt = now
        )

        return try {
            writeSession(updated)
            lastFile.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun updatePageProcessing(
        id: String,
        pageIndex: Int,
        status: String,
        reconstructedText: String = "",
        errorCategory: String = "",
        errorMessage: String = "",
        attempts: Int? = null,
        nextRetryAt: Long = 0L
    ): Boolean {
        if (pageIndex < 1) return false

        val allowedStatuses = setOf(
            "pending", "processing", "succeeded", "failed"
        )
        if (status !in allowedStatuses) return false

        val session = getSession(id) ?: return false
        if (pageIndex > session.pageCount) return false

        val now = System.currentTimeMillis()
        val previous = session.pageResults.firstOrNull {
            it.index == pageIndex
        }

        val result = PageProcessing(
            index = pageIndex,
            status = status,
            reconstructedText = if (status == "succeeded") {
                reconstructedText
            } else {
                previous?.reconstructedText.orEmpty()
            },
            errorCategory = errorCategory,
            errorMessage = errorMessage,
            attempts = attempts ?: (previous?.attempts ?: 0),
            updatedAt = now,
            nextRetryAt = nextRetryAt
        )

        val results = (session.pageResults.filterNot {
            it.index == pageIndex
        } + result).sortedBy { it.index }

        val overallStatus = when {
            results.any { it.status == "processing" } -> "processing"
            results.all { it.status == "succeeded" } -> "reconstructed"
            results.any { it.status == "succeeded" } -> "partial"
            results.any { it.status == "failed" } -> "failed"
            else -> "pending"
        }

        return try {
            writeSession(
                session.copy(
                    pageResults = results,
                    reconstructedText = results
                        .filter { it.status == "succeeded" }
                        .joinToString("\n\n") {
                            "PAGE ${it.index}\n\n${it.reconstructedText}"
                        },
                    status = overallStatus,
                    updatedAt = now
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun updateSessionStatus(id: String, status: String): Boolean {
        val allowed = setOf(
            "capturing", "pending", "processing", "partial",
            "failed", "reconstructed", "approved", "answered"
        )
        if (status !in allowed) return false

        val session = getSession(id) ?: return false

        return try {
            writeSession(
                session.copy(
                    status = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        } catch (_: Exception) {
            false
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
    fun updateReconstructedText(id: String, text: String): Boolean {
        val session = getSession(id) ?: return false

        return try {
            writeSession(
                session.copy(
                    reconstructedText = text,
                    updatedAt = System.currentTimeMillis()
                )
            )
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
        model: String,
        questionTextForSpeech: String? = null
    ): Boolean {
        val session = getSession(id) ?: return false

        return try {
            writeSession(
                session.copy(
                    reconstructedText = reconstructedText,
                    approvedText = approvedText,
                    answerText = answerText,
                    questionTextForSpeech = questionTextForSpeech
                        ?: session.questionTextForSpeech,
                    model = model,
                    status = if (answerText.isNotBlank()) "answered"
                        else if (approvedText.isNotBlank()) "approved"
                        else session.status,
                    answerStatus = if (answerText.isNotBlank()) {
                        "completed"
                    } else {
                        "pending"
                    },
                    answerAttemptAt = if (answerText.isNotBlank()) {
                        System.currentTimeMillis()
                    } else {
                        session.answerAttemptAt
                    },
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun updateAnswerStatus(id: String, answerStatus: String): Boolean {
        val allowed = setOf(
            "pending", "in_flight", "uncertain", "failed", "completed"
        )
        if (answerStatus !in allowed) return false

        val session = getSession(id) ?: return false

        return try {
            writeSession(
                session.copy(
                    answerStatus = answerStatus,
                    answerAttemptAt = if (answerStatus == "in_flight") {
                        System.currentTimeMillis()
                    } else {
                        session.answerAttemptAt
                    },
                    updatedAt = System.currentTimeMillis()
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
            put("status", session.status)
            put("updatedAt", session.updatedAt)
            put("answerStatus", session.answerStatus)
            put("answerAttemptAt", session.answerAttemptAt)
            put("questionTextForSpeech", session.questionTextForSpeech)
            put("pagePaths", JSONArray(session.pagePaths))
            put("pageResults", JSONArray().apply {
                session.pageResults.forEach { page ->
                    put(JSONObject().apply {
                        put("index", page.index)
                        put("status", page.status)
                        put("reconstructedText", page.reconstructedText)
                        put("errorCategory", page.errorCategory)
                        put("errorMessage", page.errorMessage)
                        put("attempts", page.attempts)
                        put("updatedAt", page.updatedAt)
                        put("nextRetryAt", page.nextRetryAt)
                    })
                }
            })
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
            model = json.optString("model", ""),
            pageResults = json.optJSONArray("pageResults")?.let { array ->
                List(array.length()) { index ->
                    val page = array.getJSONObject(index)
                    PageProcessing(
                        index = page.optInt("index", index + 1),
                        status = page.optString("status", "pending"),
                        reconstructedText = page.optString("reconstructedText", ""),
                        errorCategory = page.optString("errorCategory", ""),
                        errorMessage = page.optString("errorMessage", ""),
                        attempts = page.optInt("attempts", 0),
                        updatedAt = page.optLong("updatedAt", 0L),
                        nextRetryAt = page.optLong("nextRetryAt", 0L)
                    )
                }
            } ?: List(paths.length()) { index ->
                PageProcessing(index = index + 1)
            },
            status = json.optString(
                "status",
                if (json.optString("answerText").isNotBlank()) "answered"
                else if (json.optString("extractedText").isNotBlank()) "legacy"
                else "pending"
            ),
            updatedAt = json.optLong(
                "updatedAt",
                json.optLong("createdAt", 0L)
            ),
            answerStatus = json.optString("answerStatus", ""),
            answerAttemptAt = json.optLong("answerAttemptAt", 0L),
            questionTextForSpeech = json.optString("questionTextForSpeech", "")
        )
    }
}
