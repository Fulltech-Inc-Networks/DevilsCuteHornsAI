package com.fulltech.devilscutehornsai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object DchWorkScheduler {
    private const val PREFIX = "dch-session-"

    fun enqueue(context: Context, sessionId: String) {
        val request = OneTimeWorkRequestBuilder<DchProcessingWorker>()
            .setInputData(
                workDataOf(
                    DchProcessingWorker.KEY_SESSION_ID to sessionId,
                    DchProcessingWorker.KEY_ENQUEUED_AT to System.currentTimeMillis()
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .addTag(PREFIX + sessionId)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            PREFIX + sessionId,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun uniqueName(sessionId: String) = PREFIX + sessionId
}

class DchProcessingWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ENQUEUED_AT = "enqueued_at"
        private const val MAX_PAGE_ATTEMPTS = 3

        private const val ANSWER_IN_FLIGHT = "in_flight"
        private const val ANSWER_UNCERTAIN = "uncertain"
        private const val ANSWER_PENDING = "pending"
        private const val ANSWER_FAILED = "failed"
    }

    private val repository by lazy {
        SessionRepository(applicationContext)
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?: return failure("Missing scan session identifier.")

        try {

            val token = withContext(Dispatchers.IO) {
                GatewayTokenStore(applicationContext).read()
            } ?: return failure(
                "Gateway token is missing. Open AI Gateway Settings first."
            )

            var session = withContext(Dispatchers.IO) {
                repository.getSession(sessionId)
            } ?: return failure("The scan session could not be found.")

            if (session.pageCount == 0) {
                return failure("No captured pages were found.")
            }

            if (session.answerText.isNotBlank()) {
                return Result.success()
            }

            // An in-flight answer request left behind by a killed process
            // may already have reached the provider. Never resend it blindly.
            if (session.answerStatus == ANSWER_IN_FLIGHT) {
                withContext(Dispatchers.IO) {
                    repository.updateAnswerStatus(
                        sessionId,
                        ANSWER_UNCERTAIN
                    )
                }
                return failure(
                    "The previous answer request was interrupted. " +
                        "Its result may already have been processed. " +
                        "Check your session before requesting answers again."
                )
            }

            if (session.answerStatus == ANSWER_UNCERTAIN) {
                return failure(
                    "The answer request has an uncertain outcome. " +
                        "Check your saved results before trying again."
                )
            }

            var detectedModel = session.model
            val totalPages = session.pageCount

            for (index in session.pagePaths.indices) {
                if (isStopped) return Result.retry()

                session = withContext(Dispatchers.IO) {
                    repository.getSession(sessionId)
                } ?: return failure("The scan session is unavailable.")

                val pageNumber = index + 1
                val previous = session.pageResults.firstOrNull {
                    it.index == pageNumber
                }

                if (previous?.status == "succeeded") {
                    setProgress(
                        workDataOf(
                            "progress" to pageNumber,
                            "page_count" to totalPages,
                            "phase" to "Reconstructing pages"
                        )
                    )
                    continue
                }

                if ((previous?.attempts ?: 0) >= MAX_PAGE_ATTEMPTS) {
                    val lastError = previous?.errorMessage
                        ?.takeIf { it.isNotBlank() }
                        ?: "The maximum number of attempts was reached."

                    withContext(Dispatchers.IO) {
                        repository.updatePageProcessing(
                            id = sessionId,
                            pageIndex = pageNumber,
                            status = "failed",
                            errorCategory = previous?.errorCategory ?: "reconstruction",
                            errorMessage = lastError,
                            attempts = previous?.attempts ?: MAX_PAGE_ATTEMPTS
                        )
                    }

                    return failure(
                        "Page $pageNumber failed after $MAX_PAGE_ATTEMPTS attempts: $lastError"
                    )
                }

                val attempts = (previous?.attempts ?: 0) + 1

                val started = withContext(Dispatchers.IO) {
                    repository.updatePageProcessing(
                        id = sessionId,
                        pageIndex = pageNumber,
                        status = "processing",
                        attempts = attempts
                    )
                }

                if (!started) {
                    return failure(
                        "Unable to save the processing state for page $pageNumber."
                    )
                }

                setProgress(
                    workDataOf(
                        "progress" to (pageNumber - 1),
                        "page_count" to totalPages,
                        "phase" to "Reconstructing page $pageNumber of $totalPages"
                    )
                )

                try {
                    val imageFile = File(session.pagePaths[index])

                    if (!imageFile.isFile || imageFile.length() <= 0L) {
                        throw GatewayException(
                            "The saved image for page $pageNumber is missing."
                        )
                    }

                    val result = withContext(Dispatchers.IO) {
                        GatewayClient.reconstruct(
                            imageFile = imageFile,
                            pageNumber = pageNumber,
                            totalPages = totalPages,
                            token = token
                        )
                    }

                    if (result.text.isBlank()) {
                        throw GatewayException(
                            "AI returned empty text for page $pageNumber."
                        )
                    }

                    val saved = withContext(Dispatchers.IO) {
                        repository.updatePageProcessing(
                            id = sessionId,
                            pageIndex = pageNumber,
                            status = "succeeded",
                            reconstructedText = result.text.trim(),
                            attempts = attempts
                        )
                    }

                    if (!saved) {
                        return failure(
                            "Could not save the result for page $pageNumber."
                        )
                    }

                    if (result.model.isNotBlank()) {
                        detectedModel = result.model
                    }
                } catch (error: GatewayException) {
                    withContext(Dispatchers.IO) {
                        repository.updatePageProcessing(
                            id = sessionId,
                            pageIndex = pageNumber,
                            status = "failed",
                            errorCategory = if (error.retryable) {
                                "network"
                            } else {
                                "reconstruction"
                            },
                            errorMessage = error.message.orEmpty(),
                            attempts = attempts
                        )
                    }

                    val message = error.message ?: "Reconstruction failed."

                    if (error.retryable && attempts < MAX_PAGE_ATTEMPTS) {
                        setProgress(
                            workDataOf(
                                "progress" to (pageNumber - 1),
                                "page_count" to totalPages,
                                "phase" to "Temporary problem. Retrying page $pageNumber (attempt $attempts of $MAX_PAGE_ATTEMPTS)..."
                            )
                        )
                        return Result.retry()
                    }

                    return failure(
                        "Page $pageNumber failed after $attempts attempt(s): $message"
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.localizedMessage
                        ?: "An unexpected error occurred during reconstruction."

                    withContext(Dispatchers.IO) {
                        repository.updatePageProcessing(
                            id = sessionId,
                            pageIndex = pageNumber,
                            status = "failed",
                            errorCategory = "network",
                            errorMessage = message,
                            attempts = attempts
                        )
                    }

                    if (attempts < MAX_PAGE_ATTEMPTS) {
                        setProgress(
                            workDataOf(
                                "progress" to (pageNumber - 1),
                                "page_count" to totalPages,
                                "phase" to "Temporary problem. Retrying page $pageNumber (attempt $attempts of $MAX_PAGE_ATTEMPTS)..."
                            )
                        )
                        return Result.retry()
                    }

                    return failure(
                        "Page $pageNumber failed after $attempts attempts: $message"
                    )
                }
            }

            session = withContext(Dispatchers.IO) {
                repository.getSession(sessionId)
            } ?: return failure("Unable to reload the scan session.")

            if (session.successfulPageCount != session.pageCount) {
                val incompletePage = session.pageResults.firstOrNull {
                    it.status != "succeeded"
                }
                val pageNumber = incompletePage?.index
                val detail = incompletePage?.errorMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: "The page result was not saved successfully."

                return failure(
                    if (pageNumber != null) {
                        "Page $pageNumber is incomplete: $detail"
                    } else {
                        "Document reconstruction is incomplete. $detail"
                    }
                )
            }

            val completeText = session.reconstructedText.trim()

            if (completeText.isBlank()) {
                return failure("No readable document content was reconstructed.")
            }

            // Preserve the original minimum preparation period.
            val enqueuedAt = inputData.getLong(
                KEY_ENQUEUED_AT,
                System.currentTimeMillis()
            )
            val remaining = (enqueuedAt + 20_000L) - System.currentTimeMillis()

            if (remaining > 0L) {
                setProgress(
                    workDataOf(
                        "progress" to totalPages,
                        "page_count" to totalPages,
                        "phase" to "Understanding the paper"
                    )
                )
                delay(remaining)
            }

            val prepared = withContext(Dispatchers.IO) {
                repository.updateAiResults(
                    id = sessionId,
                    reconstructedText = completeText,
                    approvedText = completeText,
                    answerText = "",
                    model = detectedModel
                )
            }

            if (!prepared) {
                return failure("Unable to save the reconstructed document.")
            }

            // Persist this before the network request. If the process dies
            // during the request, the next run will not duplicate the charge.
            val answerStarted = withContext(Dispatchers.IO) {
                repository.updateAnswerStatus(
                    sessionId,
                    ANSWER_IN_FLIGHT
                )
            }

            if (!answerStarted) {
                return failure("Unable to save the answer-request state.")
            }

            setProgress(
                workDataOf(
                    "progress" to totalPages,
                    "page_count" to totalPages,
                    "phase" to "Generating answers"
                )
            )

            val answer = try {
                withContext(Dispatchers.IO) {
                    GatewayClient.answer(
                        questionText = completeText,
                        instruction = "Carefully interpret and obey every instruction printed on the paper, including compulsory sections, answer-count limits, alternatives, subquestions, marks and word limits. If choices are allowed, select exactly the required number of permitted questions, prioritizing compulsory questions and then the easiest and quickest valid choices. Do not answer unselected optional questions. Return exactly two sections using these exact markers: [[DCH_SELECTED_QUESTIONS]] followed by the complete text of only the selected question statements, preserving their original question numbers and necessary subquestion labels, then [[/DCH_SELECTED_QUESTIONS]], followed by [[DCH_ANSWERS]], then the complete answers to those selected questions in matching order, followed by [[/DCH_ANSWERS]]. Do not include the full paper, unselected questions, examination instructions, an introduction or a conclusion. Give complete, accurate explanations and show all necessary calculation steps. Use clear plain text, meaningful numbering and blank lines between answers. Avoid Markdown decoration such as bold markers, hash-heading symbols, and horizontal divider lines. Preserve mathematical notation, units, equations, diagrams described in words, and important technical symbols. Never sacrifice working or essential details merely to shorten an answer.",
                        token = token
                    )
                }
            } catch (error: GatewayException) {
                when {
                    // A 429 response means the request was rate-limited.
                    // It is safe to return to pending and let WorkManager retry.
                    error.httpStatus == 429 -> {
                        withContext(Dispatchers.IO) {
                            repository.updateAnswerStatus(
                                sessionId,
                                ANSWER_PENDING
                            )
                        }
                        return Result.retry()
                    }

                    // A definite client rejection did not generate an answer.
                    error.httpStatus != null &&
                        error.httpStatus in 400..499 -> {
                        withContext(Dispatchers.IO) {
                            repository.updateAnswerStatus(
                                sessionId,
                                ANSWER_FAILED
                            )
                        }
                        return failure(
                            error.message ?: "The answer request was rejected."
                        )
                    }

                    // Network loss and server errors have uncertain outcomes.
                    else -> {
                        withContext(Dispatchers.IO) {
                            repository.updateAnswerStatus(
                                sessionId,
                                ANSWER_UNCERTAIN
                            )
                        }
                        return failure(
                            "The answer request may have reached the AI provider, " +
                                "but its result was not confirmed. Check your results " +
                                "before requesting another answer."
                        )
                    }
                }
            }

            if (answer.text.isBlank()) {
                withContext(Dispatchers.IO) {
                    repository.updateAnswerStatus(
                        sessionId,
                        ANSWER_UNCERTAIN
                    )
                }
                return failure("The AI returned an empty answer.")
            }

            val structuredAnswer =
                DchAnswerFormatter.parseStructuredResponse(answer.text)

            val saved = withContext(Dispatchers.IO) {
                repository.updateAiResults(
                    id = sessionId,
                    reconstructedText = completeText,
                    approvedText = completeText,
                    answerText = structuredAnswer.answerText,
                    model = answer.model,
                    questionTextForSpeech = structuredAnswer.questionText
                )
            }

            if (!saved) {
                withContext(Dispatchers.IO) {
                    repository.updateAnswerStatus(
                        sessionId,
                        ANSWER_UNCERTAIN
                    )
                }
                return failure(
                    "Answers were generated but could not be saved. " +
                        "Check the session before requesting them again."
                )
            }

            setProgress(
                workDataOf(
                    "progress" to totalPages,
                    "page_count" to totalPages,
                    "phase" to "Answers saved"
                )
            )

            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failure(
                error.localizedMessage ?: "Background processing failed."
            )
        }
    }

    private suspend fun failure(message: String): Result {
        return Result.failure(workDataOf("error" to message))
    }

}
