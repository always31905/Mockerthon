package com.speechcoach.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class PostSpeechProcessor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var whisperEngine: WhisperEngine? = null

    fun processWavFile(
        wavPath: String,
        onProgress: (String) -> Unit,
        // 💡 콜백에 List<SpeechSegment> 추가
        onResult: (String, List<SpeechSegment>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val engine = whisperEngine ?: WhisperEngine(context).also { whisperEngine = it }
                mainHandler.post { onProgress("Whisper 모델 로드 중...") }

                val loaded = engine.load { msg -> mainHandler.post { onProgress(msg) } }
                if (!loaded) {
                    mainHandler.post { onError("Whisper 모델 로드 실패") }
                    return@Thread
                }

                mainHandler.post { onProgress("발표 스크립트 및 말하기 속도 분석 중...") }

                // 💡 분리된 세그먼트 데이터도 함께 받음
                val (transcript, segments) = engine.transcribeWavFile(wavPath)

                if (transcript.isBlank()) {
                    mainHandler.post { onError("음성을 인식하지 못했습니다.") }
                    return@Thread
                }

                mainHandler.post { onResult(transcript, segments) }

            } catch (e: Exception) {
                mainHandler.post { onError("처리 오류: ${e.message}") }
            }
        }.start()
    }

    fun release() {
        whisperEngine?.release()
        whisperEngine = null
    }
}