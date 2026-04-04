package com.speechcoach.stt

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File

/**
 * PostSpeechProcessor
 *
 * 발표 종료 후 저장된 WAV 파일을 SpeechRecognizer로 처리해
 * 정확한 전체 스크립트를 추출한다.
 *
 * 동작 방식:
 *   WAV 파일을 일정 구간(SEGMENT_MS)씩 잘라서
 *   각 구간을 SpeechRecognizer에 순차적으로 넘긴다.
 *
 * 단, Android SpeechRecognizer는 파일 직접 입력을 지원하지 않으므로
 * MediaPlayer로 파일을 재생하면서 SpeechRecognizer가 마이크로
 * 해당 소리를 인식하는 방식은 현실적으로 불가능.
 *
 * 따라서 실제 구현은:
 *   발표 종료 후 마이크가 해제된 시점에
 *   SpeechRecognizer를 짧게 여러 번 실행해서
 *   사용자에게 "발표 내용을 다시 읽어달라"는 방식이 아니라,
 *
 *   WAV를 EXTRA_AUDIO_SOURCE(파일 URI)로 넘기는 방식 사용.
 *   → RecognizerIntent.EXTRA_AUDIO_SOURCE (API 33+)
 *   → minSdk 26이므로 API 33 미만 기기에서는 폴백 처리
 *
 * API 33 미만 폴백:
 *   WAV 파일을 직접 읽어서 텍스트 추출 불가
 *   → VOSK로 WAV 파일 전체를 오프라인 처리 (배치)
 */
class PostSpeechProcessor(private val context: Context) {

    companion object {
        private const val TAG = "PostSpeechProcessor"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * WAV 파일 전체를 STT로 처리
     *
     * API 33+: SpeechRecognizer EXTRA_AUDIO_SOURCE 사용
     * API 26~32: VOSK 배치 처리
     *
     * @param wavPath WAV 파일 경로
     * @param onResult 전체 스크립트 텍스트
     * @param onError 오류 메시지
     */
    fun processWavFile(
        wavPath: String,
        onProgress: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val file = File(wavPath)
        if (!file.exists() || file.length() == 0L) {
            onError("녹음 파일이 없습니다")
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            processWithSpeechRecognizer(wavPath, onProgress, onResult, onError)
        } else {
            processWithVosk(wavPath, onProgress, onResult, onError)
        }
    }

    // ── API 33+: SpeechRecognizer EXTRA_AUDIO_SOURCE ─────────────
    private fun processWithSpeechRecognizer(
        wavPath: String,
        onProgress: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("음성 인식 불가")
                return@post
            }

            val transcript = StringBuilder()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            recognizer.setRecognitionListener(object : RecognitionListener {
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
                    onProgress(partial)
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (text.isNotBlank()) transcript.append(text).append(" ")
                    recognizer.destroy()
                    onResult(transcript.toString().trim())
                    Log.d(TAG, "SpeechRecognizer 배치 완료: ${transcript.length}자")
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    // SpeechRecognizer 실패 시 VOSK 폴백
                    Log.w(TAG, "SpeechRecognizer 실패(code=$error), VOSK 폴백")
                    processWithVosk(wavPath, onProgress, onResult, onError)
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // API 33+: 파일 URI로 오디오 소스 지정
                putExtra("android.speech.extra.AUDIO_SOURCE",
                    android.net.Uri.fromFile(File(wavPath)))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
            Log.d(TAG, "SpeechRecognizer 배치 시작: $wavPath")
        }
    }

    // ── API 26~32 폴백: VOSK 배치 처리 ──────────────────────────
    private fun processWithVosk(
        wavPath: String,
        onProgress: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val modelDir = File(context.filesDir, "vosk-model-small-ko")
                if (!modelDir.exists()) {
                    onError("VOSK 모델 없음. 캘리브레이션을 먼저 실행하세요.")
                    return@Thread
                }

                val model = org.vosk.Model(modelDir.absolutePath)
                val recognizer = org.vosk.Recognizer(model, 16000f).also {
                    it.setWords(true)
                }

                val transcript = StringBuilder()
                val wavFile = File(wavPath)
                val wavBytes = wavFile.readBytes()

                // WAV 헤더(44바이트) 제거 후 PCM 데이터만 처리
                val pcmData = wavBytes.drop(44).toByteArray()
                val chunkSize = 16000 * 2 * 2  // 2초 청크

                var offset = 0
                while (offset < pcmData.size) {
                    val end = minOf(offset + chunkSize, pcmData.size)
                    val chunk = pcmData.copyOfRange(offset, end)

                    if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        val json = recognizer.result
                        val text = com.google.gson.Gson()
                            .fromJson(json, com.google.gson.JsonObject::class.java)
                            .get("text")?.asString ?: ""
                        if (text.isNotBlank()) {
                            transcript.append(text).append(" ")
                            mainHandler.post { onProgress(transcript.toString().trim()) }
                        }
                    }
                    offset = end
                }

                // 마지막 결과
                val finalJson = recognizer.finalResult
                val finalText = com.google.gson.Gson()
                    .fromJson(finalJson, com.google.gson.JsonObject::class.java)
                    .get("text")?.asString ?: ""
                if (finalText.isNotBlank()) transcript.append(finalText)

                recognizer.close()
                model.close()

                val result = transcript.toString().trim()
                Log.d(TAG, "VOSK 배치 완료: ${result.length}자")
                mainHandler.post { onResult(result) }

            } catch (e: Exception) {
                Log.e(TAG, "VOSK 배치 오류: ${e.message}")
                mainHandler.post { onError("STT 처리 실패: ${e.message}") }
            }
        }.start()
    }
}
