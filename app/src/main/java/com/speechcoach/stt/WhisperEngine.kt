package com.speechcoach.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper에서 추출한 개별 단어 정보
 */
data class SpeechWord(
    val word: String,
    val start: Double,
    val end: Double
)

class WhisperEngine(private val context: Context) {

    companion object {
        private const val TAG = "WhisperEngine"
        // [수정] 상업적 속도 확보를 위해 base 모델로 변경
        private const val MODEL_FILENAME = "ggml-base.bin" 
        private const val SAMPLE_RATE = 16000

        init {
            try {
                System.loadLibrary("whisper_jni")
            } catch (e: Exception) {
                Log.e(TAG, "Library load error: ${e.message}")
            }
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
            if (isLoaded) Log.d(TAG, "Whisper 모델 로드 완료: $MODEL_FILENAME")
            else Log.e(TAG, "Whisper 모델 로드 실패: $MODEL_FILENAME")
            isLoaded
        } catch (e: Exception) {
            Log.e(TAG, "load 오류: ${e.message}")
            false
        }
    }

    fun transcribeWavFile(wavPath: String): Pair<String, List<SpeechWord>> {
        if (!isLoaded) {
            Log.e(TAG, "모델이 로드되지 않음")
            return Pair("", emptyList())
        }

        return try {
            val pcmFloats = readWavAsPcmFloats(wavPath)
            if (pcmFloats.isEmpty()) return Pair("", emptyList())

            Log.d(TAG, "Whisper 추론 시작: ${pcmFloats.size} 샘플")

            val rawResult = transcribe(pcmFloats, pcmFloats.size)

            val wordList = mutableListOf<SpeechWord>()
            val fullTranscript = StringBuilder()

            rawResult.split("\n").forEach { line ->
                val parts = line.split("|")
                if (parts.size == 3) {
                    val segmentText = parts[0]
                    val start = parts[1].toDoubleOrNull() ?: 0.0
                    val end = parts[2].toDoubleOrNull() ?: 0.0

                    fullTranscript.append(segmentText)

                    // 세그먼트를 단어 단위로 쪼개서 타임스탬프 배분
                    val wordsInSegment = segmentText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (wordsInSegment.isNotEmpty()) {
                        val duration = end - start
                        val perWordDuration = duration / wordsInSegment.size
                        wordsInSegment.forEachIndexed { index, word ->
                            val wordStart = start + (index * perWordDuration)
                            val wordEnd = wordStart + perWordDuration
                            wordList.add(SpeechWord(word, wordStart, wordEnd))
                        }
                    }
                }
            }

            val cleanText = fullTranscript.toString().trim().replace("\\s+".toRegex(), " ")
            Log.d(TAG, "Whisper 추론 완료: ${wordList.size}단어 추출")

            Pair(cleanText, wordList)
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
        for (i in 0 until numSamples) {
            if (buf.remaining() >= 2) floats[i] = buf.short / 32768.0f
        }
        return floats
    }

    private fun ensureModelInFilesDir(onProgress: (String) -> Unit): File {
        val destFile = File(context.filesDir, MODEL_FILENAME)
        // [참고] 모델이 바뀌었으므로 기존 small 모델이 있더라도 다시 복사하도록 조건 확인
        if (destFile.exists() && destFile.length() > 5 * 1024 * 1024) return destFile

        onProgress("Whisper($MODEL_FILENAME) 모델 준비 중...")
        context.assets.open(MODEL_FILENAME).use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
        return destFile
    }
}
