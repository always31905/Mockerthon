package com.speechcoach.stt

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * VoskSTTEngine - 실시간 WPM 계산 전용
 *
 * 역할: AudioBroadcaster PCM 청크 → 단어 인식 → SpeedAnalyzer에 전달
 * 스크립트/습관어는 담당하지 않음 (발표 종료 후 PostSpeechProcessor가 담당)
 *
 * VOSK는 PCM을 직접 받으므로 AudioRecord와 마이크 충돌 없음.
 */
class VoskSTTEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VoskSTTEngine"
        private const val MODEL_ASSET_DIR = "vosk-model-small-ko"
        private const val SAMPLE_RATE = 16000f
        // 400ms 청크 (인식률 개선)
        private const val CHUNK_SAMPLES = 6400
    }

    // WPM용 콜백: 인식된 텍스트 + 수신 시각
    var onSpeedUpdate: ((String, Long) -> Unit)? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val gson = Gson()
    private var sttJob: Job? = null
    private val accumulator = mutableListOf<Short>()

    fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val modelDir = copyModelFromAssets()
                val loadedModel = Model(modelDir.absolutePath)
                model = loadedModel
                recognizer = Recognizer(loadedModel, SAMPLE_RATE).also {
                    it.setWords(true)
                }
                Log.d(TAG, "VOSK 초기화 완료")
                withContext(Dispatchers.Main) { onReady() }
            } catch (e: Exception) {
                Log.e(TAG, "VOSK 초기화 실패: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "모델 로드 실패") }
            }
        }
    }

    fun startListening(audioChunkFlow: SharedFlow<ShortArray>) {
        sttJob = scope.launch(Dispatchers.IO) {
            audioChunkFlow.collect { chunk ->
                accumulator.addAll(chunk.toList())
                while (accumulator.size >= CHUNK_SAMPLES) {
                    val batch = accumulator.subList(0, CHUNK_SAMPLES).toShortArray()
                    accumulator.subList(0, CHUNK_SAMPLES).clear()
                    processChunk(batch)
                }
            }
        }
    }

    private suspend fun processChunk(chunk: ShortArray) {
        val rec = recognizer ?: return
        val bytes = shortToByteArray(chunk)
        val nowMs = System.currentTimeMillis()

        if (rec.acceptWaveForm(bytes, bytes.size)) {
            val json = rec.result
            parseForSpeed(json, nowMs)
        } else {
            // partial도 WPM에 활용
            val partialJson = rec.partialResult
            try {
                val partial = gson.fromJson(partialJson, JsonObject::class.java)
                    .get("partial")?.asString ?: ""
                if (partial.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onSpeedUpdate?.invoke(partial, nowMs)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private suspend fun parseForSpeed(json: String, nowMs: Long) {
        try {
            val text = gson.fromJson(json, JsonObject::class.java)
                .get("text")?.asString ?: return
            if (text.isBlank()) return
            withContext(Dispatchers.Main) {
                onSpeedUpdate?.invoke(text, nowMs)
            }
        } catch (e: Exception) { }
    }

    suspend fun stop() {
        sttJob?.cancelAndJoin()
        sttJob = null
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        accumulator.clear()
        Log.d(TAG, "VOSK 중지 완료")
    }

    private fun copyModelFromAssets(): File {
        val destDir = File(context.filesDir, MODEL_ASSET_DIR)
        if (destDir.exists() && destDir.list()?.isNotEmpty() == true) return destDir
        destDir.mkdirs()
        copyAssetFolder(MODEL_ASSET_DIR, destDir)
        return destDir
    }

    private fun copyAssetFolder(assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destDir).use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            assets.forEach { child ->
                copyAssetFolder("$assetPath/$child", File(destDir, child))
            }
        }
    }

    private fun shortToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8).toByte()
        }
        return bytes
    }
}
