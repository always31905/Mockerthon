package com.speechcoach.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
/*
class AndroidSTTEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AndroidSTTEngine"
        private const val SILENCE_TIMEOUT = 5000L
        private const val RESTART_DELAY_MS = 300L
    }

    var onSpeedUpdate: ((String, Long) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldRestart = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fullTranscript = StringBuilder()
    private var lastPartialBackup = ""
    private var startTimeMs = 0L
    private val allWords = mutableListOf<SpeechWord>()

    fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("이 기기에서 음성 인식을 사용할 수 없습니다.")
                return@post
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(buildListener())
            }
            Log.d(TAG, "SpeechRecognizer 초기화 완료")
            onReady()
        }
    }

    fun startListening() {
        startTimeMs = System.currentTimeMillis()
        isListening = true
        shouldRestart = true
        mainHandler.post { startRecognition() }
    }

    private fun startRecognition() {
        if (!isListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening 오류: ${e.message}")
        }
    }

    private fun buildListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            if (partial.isBlank()) return

            val nowMs = System.currentTimeMillis()
            lastPartialBackup = partial
            scope.launch(Dispatchers.Main) {
                onSpeedUpdate?.invoke(partial, nowMs)
                onPartialResult?.invoke(partial)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                val nowMs = System.currentTimeMillis()
                appendResult(text, nowMs)
                lastPartialBackup = ""
                scope.launch(Dispatchers.Main) {
                    onSpeedUpdate?.invoke(text, nowMs)
                    onPartialResult?.invoke(text)
                }
            }
            if (shouldRestart && isListening) {
                mainHandler.postDelayed({ startRecognition() }, RESTART_DELAY_MS)
            }
        }

        override fun onError(error: Int) {
            Log.w(TAG, "STT 오류 code=$error")
            if (shouldRestart && isListening) {
                mainHandler.postDelayed({ startRecognition() }, RESTART_DELAY_MS)
            }
        }
    }

    private fun appendResult(text: String, nowMs: Long) {
        fullTranscript.append(text).append(" ")
        val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        val elapsedSec = (nowMs - startTimeMs) / 1000.0
        val estimatedDuration = (words.size * 0.4).coerceAtMost(elapsedSec)
        val startSec = elapsedSec - estimatedDuration
        val perWord = if (words.size > 1) estimatedDuration / words.size else estimatedDuration

        allWords.addAll(words.mapIndexed { i, word ->
            SpeechWord(
                word = word,
                start = startSec + i * perWord,
                end = startSec + (i + 1) * perWord
            )
        })
        Log.d(TAG, "인식: \"$text\"")
    }

    fun stopAndGetTranscript(): String {
        shouldRestart = false
        isListening = false
        mainHandler.post {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "중지 오류: ${e.message}")
            }
        }
        if (lastPartialBackup.isNotBlank()) {
            appendResult(lastPartialBackup, System.currentTimeMillis())
        }
        return fullTranscript.toString().trim().replace("\\s+".toRegex(), " ")
    }

    fun getAllWords(): List<SpeechWord> = allWords.toList()

    fun stop() {
        shouldRestart = false
        isListening = false
        mainHandler.post {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null
            } catch (e: Exception) { }
        }
    }
}

data class SpeechWord(
    val word: String,
    val start: Double,
    val end: Double,
    val conf: Double = 1.0
)
*/