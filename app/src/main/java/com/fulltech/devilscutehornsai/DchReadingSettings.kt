package com.fulltech.devilscutehornsai

import android.content.Context

data class DchReadingSettings(
    val speechRate: Float = 0.82f,
    val speechPitch: Float = 1.0f,
    val voiceName: String = "",
    val fontSize: Float = 16f,
    val paragraphSpacing: Float = 18f,
    val includeQuestionHeadings: Boolean = false,
    val cleanFormatting: Boolean = true,
    val repeatQuestionThreeTimes: Boolean = true,
    val repeatAnswerThreeTimes: Boolean = true,
    val bluetoothEarbudsOnly: Boolean = false
)

class DchReadingSettingsStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("dch_reading_settings", Context.MODE_PRIVATE)

    fun read(): DchReadingSettings {
        return DchReadingSettings(
            speechRate = preferences.getFloat("speech_rate", 0.82f),
            speechPitch = preferences.getFloat("speech_pitch", 1.0f),
            voiceName = preferences.getString("voice_name", "") ?: "",
            fontSize = preferences.getFloat("font_size", 16f),
            paragraphSpacing = preferences.getFloat("paragraph_spacing", 18f),
            includeQuestionHeadings = preferences.getBoolean(
                "include_question_headings",
                false
            ),
            cleanFormatting = preferences.getBoolean("clean_formatting", true),
            repeatQuestionThreeTimes = preferences.getBoolean("repeat_question_three_times", true),
            repeatAnswerThreeTimes = preferences.getBoolean("repeat_answer_three_times", true),
            bluetoothEarbudsOnly = preferences.getBoolean("bluetooth_earbuds_only", false)
        )
    }

    fun save(settings: DchReadingSettings) {
        preferences.edit()
            .putFloat("speech_rate", settings.speechRate.coerceIn(0.25f, 1.2f))
            .putFloat("speech_pitch", settings.speechPitch.coerceIn(0.8f, 1.2f))
            .putString("voice_name", settings.voiceName)
            .putFloat("font_size", settings.fontSize.coerceIn(13f, 24f))
            .putFloat(
                "paragraph_spacing",
                settings.paragraphSpacing.coerceIn(8f, 32f)
            )
            .putBoolean(
                "include_question_headings",
                settings.includeQuestionHeadings
            )
            .putBoolean("clean_formatting", settings.cleanFormatting)
            .putBoolean("repeat_question_three_times", settings.repeatQuestionThreeTimes)
            .putBoolean("repeat_answer_three_times", settings.repeatAnswerThreeTimes)
            .putBoolean("bluetooth_earbuds_only", settings.bluetoothEarbudsOnly)
            .apply()
    }
}
