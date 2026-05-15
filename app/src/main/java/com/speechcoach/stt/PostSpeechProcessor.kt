package com.speechcoach.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class PostSpeechProcessor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var whisperEngine: WhisperEngine? = null

    // onResult가 전체텍스트와 단어리스트(시간 포함)를 함께 반환하도록 수정
    fun processWavFile(
        wavPath: String,
        onProgress: (String) -> Unit,
        onResult: (String, List<SpeechWord>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val engine = whisperEngine ?: WhisperEngine(context).also { whisperEngine = it }
                mainHandler.post { onProgress("Whisper 모델 로드 중...") }

                val loaded = engine.load { progressMsg -> mainHandler.post { onProgress(progressMsg) } }
                if (!loaded) {
                    mainHandler.post { onError("Whisper 모델 로드 실패") }
                    return@Thread
                }

                mainHandler.post { onProgress("발표 스크립트 및 타임스탬프 추출 중...") }

                val (transcript, words) = engine.transcribeWavFile(wavPath)

                if (transcript.isBlank()) {
                    mainHandler.post { onError("음성을 인식하지 못했습니다.") }
                    return@Thread
                }

                mainHandler.post { onResult(transcript, words) }

            } catch (e: Exception) {
                mainHandler.post { onError("스크립트 추출 실패: ${e.message}") }
            }
        }.start()
    }

    fun release() {
        whisperEngine?.release()
        whisperEngine = null
    }
}