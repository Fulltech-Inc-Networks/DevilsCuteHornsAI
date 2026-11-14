package com.fulltech.devilscutehornsai

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class OcrEngine {

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    fun recognize(
        context: Context,
        file: File,
        onResult: (Result<String>) -> Unit
    ) {
        try {
            val image = InputImage.fromFilePath(
                context,
                Uri.fromFile(file)
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    onResult(Result.success(visionText.text))
                }
                .addOnFailureListener { error ->
                    onResult(Result.failure(error))
                }
        } catch (error: Exception) {
            onResult(Result.failure(error))
        }
    }

    fun close() {
        recognizer.close()
    }
}
