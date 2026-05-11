package com.speechcoach.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 💡 시간 정보가 포함된 문장 조각 데이터 클래스 추가
data class SpeechSegment(
    val text: String,
    val start: Double,
    val end: Double
)

class WhisperEngine(private val context: Context) {

    companion object {
        private const val TAG = "WhisperEngine"
        private const val MODEL_FILENAME = "ggml-small.bin"
        private const val SAMPLE_RATE = 16000

        init {
            System.loadLibrary("whisper_jni")
        }
    }

    private var isLoaded = false

    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(pcmData: FloatArray, numSamples: Int): String
    private external fun releaseModel()

    fun load(onProgress: (String) -> Unit = {}): Boolean {
        if (isLoaded) return true

        return try {
            val modelFile = ensureModelInFilesDir(onProgress)
            isLoaded = loadModel(modelFile.absolutePath)
            if (isLoaded) Log.d(TAG, "Whisper 모델 로드 완료")
            else Log.e(TAG, "Whisper 모델 로드 실패")
            isLoaded
        } catch (e: Exception) {
            Log.e(TAG, "load 오류: ${e.message}")
            false
        }
    }

    // 💡 반환값을 단순 String에서 Pair(전체텍스트, 문장조각리스트)로 변경
    fun transcribeWavFile(wavPath: String): Pair<String, List<SpeechSegment>> {
        if (!isLoaded) {
            Log.e(TAG, "모델이 로드되지 않음")
            return Pair("", emptyList())
        }

        return try {
            val pcmFloats = readWavAsPcmFloats(wavPath)
            if (pcmFloats.isEmpty()) return Pair("", emptyList())

            val rawResult = transcribe(pcmFloats, pcmFloats.size)

            val segments = mutableListOf<SpeechSegment>()
            val fullTranscript = StringBuilder()

            // JNI에서 넘어온 "텍스트|시작초|끝초\n" 형식 파싱
            rawResult.split("\n").forEach { line ->
                val parts = line.split("|")
                if (parts.size == 3) {
                    val text = parts[0].trim()
                    val start = parts[1].toDoubleOrNull() ?: 0.0
                    val end = parts[2].toDoubleOrNull() ?: 0.0

                    if (text.isNotEmpty()) {
                        fullTranscript.append(text).append(" ")
                        segments.add(SpeechSegment(text, start, end))
                    }
                }
            }

            Pair(fullTranscript.toString().trim(), segments)
        } catch (e: Exception) {
            Log.e(TAG, "transcribeWavFile 오류: ${e.message}")
            Pair("", emptyList())
        }
    }

    fun release() {
        if (isLoaded) {
            releaseModel()
            isLoaded = false
        }
    }

    private fun readWavAsPcmFloats(wavPath: String): FloatArray {
        val file = File(wavPath)
        if (!file.exists() || file.length() < 44) return floatArrayOf()
        val bytes = file.readBytes()
        val pcmBytes = bytes.drop(44).toByteArray()
        if (pcmBytes.isEmpty()) return floatArrayOf()
        val numSamples = pcmBytes.size / 2
        val floats = FloatArray(numSamples)
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) floats[i] = buf.short / 32768.0f
        return floats
    }

    private fun ensureModelInFilesDir(onProgress: (String) -> Unit): File {
        val destFile = File(context.filesDir, MODEL_FILENAME)
        if (destFile.exists() && destFile.length() > 1024 * 1024) return destFile
        onProgress("Whisper 모델 준비 중... (최초 1회)")
        context.assets.open(MODEL_FILENAME).use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) output.write(buffer, 0, bytesRead)
            }
        }
        return destFile
    }
}