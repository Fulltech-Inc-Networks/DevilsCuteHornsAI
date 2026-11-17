package com.fulltech.devilscutehornsai

object DchAnswerFormatter {

    data class StructuredAnswer(
        val questionText: String,
        val answerText: String
    )

    fun parseStructuredResponse(text: String): StructuredAnswer {
        val questionStart = "[[DCH_SELECTED_QUESTIONS]]"
        val questionEnd = "[[/DCH_SELECTED_QUESTIONS]]"
        val answerStart = "[[DCH_ANSWERS]]"
        val answerEnd = "[[/DCH_ANSWERS]]"

        val qStart = text.indexOf(questionStart)
        val qEnd = text.indexOf(questionEnd)
        val aStart = text.indexOf(answerStart)
        val aEnd = text.indexOf(answerEnd)

        if (qStart < 0 || qEnd <= qStart ||
            aStart < 0 || aEnd <= aStart ||
            aStart < qEnd
        ) {
            return StructuredAnswer("", text.trim())
        }

        val questions = text.substring(
            qStart + questionStart.length,
            qEnd
        ).trim()

        val answers = text.substring(
            aStart + answerStart.length,
            aEnd
        ).trim()

        if (answers.isBlank()) {
            return StructuredAnswer("", text.trim())
        }

        return StructuredAnswer(questions, answers)
    }



    fun formatForDisplay(
        text: String,
        cleanFormatting: Boolean = true
    ): String {
        if (!cleanFormatting) return text.trim()

        return text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("__(.*?)__"), "$1")
            .replace(Regex("(?<!\\*)\\*(?!\\s)(.*?)(?<!\\s)\\*(?!\\*)"), "$1")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun formatForSpeech(
        text: String,
        includeQuestionHeadings: Boolean = false,
        cleanFormatting: Boolean = true
    ): String {
        var result = formatForDisplay(text, cleanFormatting)

        if (!includeQuestionHeadings) {
            result = result.lines().filterNot { line ->
                val value = line.trim()
                value.matches(
                    Regex(
                        "(?i)^(question\\s*)?\\d+[.) :]\\s*.{20,}[?]$"
                    )
                ) ||
                    value.matches(
                        Regex("(?i)^question\\s+\\d+\\s*:?\\s*$")
                    ) ||
                    value.matches(
                        Regex("(?i)^(instructions|answer all questions|section [a-z])\\s*:?$")
                    )
            }.joinToString("\n")
        }

        return result
            .replace(Regex("(?m)^\\s*[•]\\s*"), "")
            .replace(Regex("(?m)^\\s*[-]{2,}\\s*$"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
