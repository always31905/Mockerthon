package com.speechcoach.stt

import android.content.Context
import android.os.Handler
import android.os.Looper

class PostSpeechProcessor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var whisperEngine: WhisperEngine? = null

    fun processWavFile(
        wavPath: String,
        onProgress: (String) -> Unit,
        onResult: (String, List<SpeechWord>) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                // 1. 엔진 초기화
                val engine = whisperEngine ?: WhisperEngine(context).also { whisperEngine = it }

                // 2. 모델 로드
                val loaded = engine.load { msg ->
                    mainHandler.post { onProgress(msg) }
                }

                if (!loaded) {
                    mainHandler.post { onError("Whisper 모델 파일(.onnx)을 찾을 수 없거나 로드에 실패했습니다.") }
                    return@Thread
                }

                mainHandler.post { onProgress("오디오 분석 및 텍스트 변환 시작...") }

                // 3. 변환 수행 (핵심 로직)
                val (transcript, segments) = engine.transcribeWavFile(wavPath)

                if (transcript.isBlank()) {
                    mainHandler.post { onError("음성이 감지되지 않았습니다.") }
                    return@Thread
                }

                // 4. 결과 전달
                mainHandler.post { onResult(transcript, segments) }

            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post { onError("시스템 오류: ${e.localizedMessage}") }
            }
        }.start()
    }

    fun release() {
        whisperEngine?.release()
        whisperEngine = null
    }
}