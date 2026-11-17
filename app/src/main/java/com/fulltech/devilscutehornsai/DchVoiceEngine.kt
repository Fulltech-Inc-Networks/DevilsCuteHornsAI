package com.fulltech.devilscutehornsai

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

data class DchVoiceOption(
    val name: String,
    val label: String,
    val locale: String
)

class DchVoiceEngine(context: Context) : TextToSpeech.OnInitListener {

    var status by mutableStateOf("Preparing speech engine...")
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var currentPart by mutableStateOf(0)
        private set

    var totalParts by mutableStateOf(0)
        private set

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingPlay = false
    private var paused = false
    private data class SpeechPart(
        val text: String,
        val repeatCount: Int
    )

    private var parts = emptyList<SpeechPart>()
    private var currentRepeat = 1
    private var readingSettings = DchReadingSettings()
    var availableVoices by mutableStateOf<List<DchVoiceOption>>(emptyList())
        private set
    private var activeUtteranceId = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(
        Context.AUDIO_SERVICE
    ) as AudioManager

    private var bluetoothOutputAvailable = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(
            addedDevices: Array<out AudioDeviceInfo>
        ) {
            mainHandler.post {
                refreshBluetoothOutputAvailability()
            }
        }

        override fun onAudioDevicesRemoved(
            removedDevices: Array<out AudioDeviceInfo>
        ) {
            mainHandler.post {
                refreshBluetoothOutputAvailability()
            }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(
            audioDeviceCallback,
            mainHandler
        )
        refreshBluetoothOutputAvailability()
        engine = TextToSpeech(appContext, this)
    }

    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            status = "Speech engine initialization failed."
            return
        }

        val languageResult = engine?.setLanguage(Locale.ENGLISH)

        if (
            languageResult == null ||
            languageResult == TextToSpeech.ERROR ||
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            status = "Speech language unavailable. Check Android text-to-speech settings."
            return
        }

        ready = true
        refreshAvailableVoices()
        applyVoiceSettings()
        status = "Ready"
        refreshBluetoothOutputAvailability()

        engine?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val id = utteranceId ?: return
                    mainHandler.post {
                        if (id == activeUtteranceId) {
                            isPlaying = true
                            status = "Playing"
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    val id = utteranceId ?: return
                    mainHandler.post {
                        if (id == activeUtteranceId) {
                            activeUtteranceId = ""

                            if (currentPart < parts.size) {
                                val part = parts[currentPart]

                                if (currentRepeat < part.repeatCount) {
                                    currentRepeat++
                                    speakCurrentPart()
                                } else {
                                    currentRepeat = 1
                                    currentPart++

                                    if (currentPart < parts.size) {
                                        speakCurrentPart()
                                    } else {
                                        isPlaying = false
                                        paused = false
                                        status = "Finished"
                                    }
                                }
                            }
                        }
                    }
                }

                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    val id = utteranceId ?: return
                    mainHandler.post {
                        if (id == activeUtteranceId) {
                            activeUtteranceId = ""
                            isPlaying = false
                            status = "Speech playback failed."
                        }
                    }
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int
                ) {
                    val id = utteranceId ?: return
                    mainHandler.post {
                        if (id == activeUtteranceId) {
                            activeUtteranceId = ""
                            isPlaying = false
                            status = "Speech playback failed ($errorCode)."
                        }
                    }
                }
            }
        )

        if (pendingPlay) {
            pendingPlay = false
            speakCurrentPart()
        }
    }

    fun applySettings(settings: DchReadingSettings) {
        readingSettings = settings
        refreshBluetoothOutputAvailability()
        if (ready) applyVoiceSettings()
    }

    private fun hasBluetoothAudioOutput(): Boolean {
        val bluetoothTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )

        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.isSink && it.type in bluetoothTypes }
    }

    private fun refreshBluetoothOutputAvailability() {
        bluetoothOutputAvailable = hasBluetoothAudioOutput()

        if (
            readingSettings.bluetoothEarbudsOnly &&
            !bluetoothOutputAvailable
        ) {
            if (activeUtteranceId.isNotEmpty() || isPlaying) {
                activeUtteranceId = ""
                engine?.stop()
                pendingPlay = false
                paused = true
                isPlaying = false
            }

            status = "Bluetooth earbuds disconnected. Playback paused."
        } else if (
            status == "Bluetooth earbuds disconnected. Playback paused."
        ) {
            status = if (parts.isEmpty()) {
                "No answer available to read."
            } else {
                "Ready"
            }
        }
    }

    private fun refreshAvailableVoices() {
        val voices = engine?.voices.orEmpty()
            .filter { it.locale.language == "en" }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenBy { it.locale.displayName }
                    .thenBy { it.name }
            )

        availableVoices = voices.map { voice ->
            DchVoiceOption(
                name = voice.name,
                label = "${voice.locale.displayName} • ${voice.name}",
                locale = voice.locale.toLanguageTag()
            )
        }
    }

    private fun applyVoiceSettings() {
        val tts = engine ?: return

        tts.setSpeechRate(readingSettings.speechRate)
        tts.setPitch(readingSettings.speechPitch)

        val selectedVoice = tts.voices.orEmpty().firstOrNull {
            it.name == readingSettings.voiceName &&
                it.locale.language == "en"
        }

        if (selectedVoice != null) {
            tts.voice = selectedVoice
        } else {
            val englishVoice = tts.voices.orEmpty()
                .firstOrNull {
                    it.locale.language == "en" &&
                        !it.isNetworkConnectionRequired
                }
                ?: tts.voices.orEmpty().firstOrNull {
                    it.locale.language == "en"
                }

            if (englishVoice != null) {
                tts.voice = englishVoice
            }
        }
    }

    fun setText(text: String) {
        activeUtteranceId = ""
        engine?.stop()
        pendingPlay = false
        paused = false
        isPlaying = false

        parts = splitText(text).map {
            SpeechPart(it, 1)
        }
        currentPart = 0
        currentRepeat = 1
        totalParts = parts.size

        status = if (parts.isEmpty()) {
            "No answer available to read."
        } else {
            "Ready"
        }
    }

    fun setContent(
        questionText: String,
        answerText: String,
        includeQuestions: Boolean,
        repeatQuestions: Boolean,
        repeatAnswers: Boolean
    ) {
        activeUtteranceId = ""
        engine?.stop()
        pendingPlay = false
        paused = false
        isPlaying = false

        val questionParts = if (includeQuestions) {
            splitText(questionText).map {
                SpeechPart(it, if (repeatQuestions) 3 else 1)
            }
        } else {
            emptyList()
        }

        val answerParts = splitText(answerText).map {
            SpeechPart(it, if (repeatAnswers) 3 else 1)
        }

        parts = questionParts + answerParts
        currentPart = 0
        currentRepeat = 1
        totalParts = parts.size

        status = if (parts.isEmpty()) {
            "No answer available to read."
        } else {
            "Ready"
        }
    }

    fun play() {
        if (activeUtteranceId.isNotEmpty()) return
        if (parts.isEmpty()) {
            status = "No answer available to read."
            return
        }

        if (currentPart >= parts.size) {
            currentPart = 0
            currentRepeat = 1
        }

        if (!ready) {
            pendingPlay = true
            status = "Preparing speech..."
            return
        }

        paused = false
        speakCurrentPart()
    }

    fun pause() {
        pendingPlay = false
        activeUtteranceId = ""
        engine?.stop()
        paused = true
        isPlaying = false
        status = "Paused"
    }

    fun resume() {
        if (activeUtteranceId.isNotEmpty()) return
        if (parts.isEmpty()) return

        if (!ready) {
            pendingPlay = true
            return
        }

        if (currentPart >= parts.size) {
            currentPart = 0
            currentRepeat = 1
        }

        paused = false
        speakCurrentPart()
    }

    fun replay() {
        if (parts.isEmpty()) return

        currentPart = 0
        currentRepeat = 1
        paused = false

        if (!ready) {
            pendingPlay = true
            return
        }

        speakCurrentPart()
    }

    fun next() {
        if (parts.isEmpty()) return

        activeUtteranceId = ""
        engine?.stop()
        pendingPlay = false
        paused = false
        isPlaying = false
        currentRepeat = 1

        if (currentPart < parts.lastIndex) {
            currentPart++

            if (!ready) {
                pendingPlay = true
                status = "Preparing speech..."
            } else {
                pendingPlay = false
                speakCurrentPart()
            }
        } else {
            pendingPlay = false
            currentPart = parts.size
            status = "Finished"
        }
    }

    private fun speakCurrentPart() {
        if (!ready || parts.isEmpty()) return

        if (
            readingSettings.bluetoothEarbudsOnly &&
            !hasBluetoothAudioOutput()
        ) {
            pendingPlay = false
            paused = true
            isPlaying = false
            status = "Connect Bluetooth earbuds before playing."
            return
        }

        if (currentPart >= parts.size) {
            isPlaying = false
            status = "Finished"
            return
        }

        val utteranceId = "dch-${System.nanoTime()}"
        activeUtteranceId = utteranceId

        status = "Starting playback..."

        val result = engine?.speak(
            parts[currentPart].text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )

        if (result == null || result == TextToSpeech.ERROR) {
            activeUtteranceId = ""
            isPlaying = false
            status = "Unable to start speech playback."
        }
    }

    private fun splitText(text: String): List<String> {
        val paragraphs = text
            .trim()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()

        for (paragraph in paragraphs) {
            var current = StringBuilder()

            val sentences = paragraph.split(
                Regex("(?<=[.!?])\\s+")
            )

            for (sentence in sentences) {
                var remaining = sentence.trim()

                while (remaining.length > MAX_UTTERANCE_LENGTH) {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuilder()
                    }

                    result.add(
                        remaining.take(MAX_UTTERANCE_LENGTH)
                    )

                    remaining = remaining.drop(MAX_UTTERANCE_LENGTH)
                }

                if (remaining.isBlank()) continue

                if (
                    current.isNotEmpty() &&
                    current.length + remaining.length + 1 >
                    MAX_UTTERANCE_LENGTH
                ) {
                    result.add(current.toString())
                    current = StringBuilder()
                }

                if (current.isNotEmpty()) current.append(' ')
                current.append(remaining)
            }

            if (current.isNotEmpty()) {
                result.add(current.toString())
            }
        }

        return result
    }

    fun shutdown() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        pendingPlay = false
        activeUtteranceId = ""
        isPlaying = false
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    companion object {
        private const val MAX_UTTERANCE_LENGTH = 3500
    }
}

object DchHardwareKeyRouter {

    private enum class Mode {
        INACTIVE,
        CAMERA,
        PLAYBACK
    }

    private data class Press(
        val mode: Mode,
        val startedAt: Long,
        val cameraCapture: (() -> Unit)? = null,
        val downShort: (() -> Unit)? = null,
        val downLong: (() -> Unit)? = null,
        val upShort: (() -> Unit)? = null,
        val upLong: (() -> Unit)? = null
    )

    private var mode = Mode.INACTIVE
    private var cameraCapture: (() -> Unit)? = null

    private var downShort: (() -> Unit)? = null
    private var downLong: (() -> Unit)? = null
    private var upShort: (() -> Unit)? = null
    private var upLong: (() -> Unit)? = null

    private val pressed = mutableMapOf<Int, Press>()

    @Synchronized
    fun setCameraCapture(callback: (() -> Unit)?) {
        clearLocked()

        if (callback != null) {
            cameraCapture = callback
            mode = Mode.CAMERA
        }
    }

    @Synchronized
    fun setPlaybackControls(
        onDownShort: () -> Unit,
        onDownLong: () -> Unit,
        onUpShort: () -> Unit,
        onUpLong: () -> Unit
    ) {
        clearLocked()

        downShort = onDownShort
        downLong = onDownLong
        upShort = onUpShort
        upLong = onUpLong
        mode = Mode.PLAYBACK
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun clearLocked() {
        mode = Mode.INACTIVE
        cameraCapture = null
        downShort = null
        downLong = null
        upShort = null
        upLong = null
        pressed.clear()
    }

    @Synchronized
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            keyCode != KeyEvent.KEYCODE_VOLUME_UP
        ) {
            return false
        }

        if (mode == Mode.INACTIVE) return false

        if (mode == Mode.CAMERA &&
            keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return false
        }

        if (event.repeatCount == 0 && !pressed.containsKey(keyCode)) {
            pressed[keyCode] = Press(
                mode = mode,
                startedAt = event.eventTime,
                cameraCapture = cameraCapture,
                downShort = downShort,
                downLong = downLong,
                upShort = upShort,
                upLong = upLong
            )
        }

        return true
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            keyCode != KeyEvent.KEYCODE_VOLUME_UP
        ) {
            return false
        }

        val press = synchronized(this) {
            pressed.remove(keyCode)
        } ?: return synchronized(this) {
            mode != Mode.INACTIVE &&
                (mode == Mode.PLAYBACK ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        }

        val duration = event.eventTime - press.startedAt
        val longPress = duration >= LONG_PRESS_MILLIS

        when (press.mode) {
            Mode.CAMERA -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    press.cameraCapture?.invoke()
                }
            }

            Mode.PLAYBACK -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (longPress) {
                        press.downLong?.invoke()
                    } else {
                        press.downShort?.invoke()
                    }
                } else {
                    if (longPress) {
                        press.upLong?.invoke()
                    } else {
                        press.upShort?.invoke()
                    }
                }
            }

            Mode.INACTIVE -> Unit
        }

        return true
    }

    private const val LONG_PRESS_MILLIS = 500L
}
