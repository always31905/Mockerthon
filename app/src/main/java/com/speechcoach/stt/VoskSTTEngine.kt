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
 * VoskSTTEngine
 *
 * [크래시 수정]
 * getFinalTranscript()를 STT 코루틴이 살아있는 상태에서 호출하면
 * Recognizer를 두 스레드가 동시에 접근해 메모리 손상(SIGABRT) 발생.
 *
 * 해결: stopAndGetTranscript() 함수를 추가.
 *   1. sttJob.cancelAndJoin() → 코루틴이 완전히 종료될 때까지 대기
 *   2. 단일 스레드에서 recognizer.finalResult 안전하게 접근
 *   3. Recognizer/Model 해제
 *
 * 반드시 IO 스레드(Dispatchers.IO)에서 호출해야 한다.
 * (suspend 함수이므로 코루틴 안에서 호출)
 */
class VoskSTTEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG             = "VoskSTTEngine"
        private const val MODEL_ASSET_DIR = "vosk-model-small-ko"
    }

    private var model:      Model?      = null
    private var recognizer: Recognizer? = null
    private val gson = Gson()

    var onPartialResult: ((String) -> Unit)?        = null
    var onWordResult:    ((List<VoskWord>) -> Unit)? = null

    private var sttJob: Job? = null
    private val fullTranscript = StringBuilder()

    // ── 모델 초기화 ───────────────────────────────────────────────
    fun initialize(onReady: () -> Unit, onError: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val modelDir   = copyModelFromAssets()
                val loadedModel = Model(modelDir.absolutePath)
                model      = loadedModel
                recognizer = Recognizer(loadedModel, 16000f).also { it.setWords(true) }
                Log.d(TAG, "VOSK 모델 로드 완료: ${modelDir.absolutePath}")
                withContext(Dispatchers.Main) { onReady() }
            } catch (e: Exception) {
                Log.e(TAG, "VOSK 모델 로드 실패: ${e.message}")
                withContext(Dispatchers.Main) { onError(e.message ?: "모델 로드 실패") }
            }
        }
    }

    // ── STT 스트리밍 시작 ─────────────────────────────────────────
    fun startListening(audioChunkFlow: SharedFlow<ShortArray>) {
        sttJob = scope.launch(Dispatchers.IO) {
            audioChunkFlow.collect { chunk -> processChunk(chunk) }
        }
    }

    // ── 청크 처리 ────────────────────────────────────────────────
    private suspend fun processChunk(chunk: ShortArray) {
        val rec   = recognizer ?: return
        val bytes = shortToByteArray(chunk)
        if (rec.acceptWaveForm(bytes, bytes.size)) {
            parseAndEmitResult(rec.result)
        } else {
            val partial = gson.fromJson(rec.partialResult, JsonObject::class.java)
                .get("partial")?.asString ?: ""
            if (partial.isNotEmpty()) {
                withContext(Dispatchers.Main) { onPartialResult?.invoke(partial) }
            }
        }
    }

    private suspend fun parseAndEmitResult(json: String) {
        try {
            val obj  = gson.fromJson(json, JsonObject::class.java)
            val text = obj.get("text")?.asString ?: return
            if (text.isEmpty()) return
            fullTranscript.append(text).append(" ")
            val words = mutableListOf<VoskWord>()
            obj.getAsJsonArray("result")?.forEach { elem ->
                val w = elem.asJsonObject
                words.add(VoskWord(
                    word  = w.get("word").asString,
                    start = w.get("start").asDouble,
                    end   = w.get("end").asDouble,
                    conf  = w.get("conf").asDouble
                ))
            }
            withContext(Dispatchers.Main) {
                onWordResult?.invoke(words)
                onPartialResult?.invoke(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON 파싱 오류: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════
    // [핵심 수정] STT 코루틴을 완전히 종료한 뒤 finalResult 접근
    //
    // 반드시 IO 스레드에서 호출 (suspend)
    // PresentationViewModel.stopPresentation()의 IO 코루틴 안에서 호출
    // ════════════════════════════════════════════════════════════
    suspend fun stopAndGetTranscript(): String {
        // 1. STT 코루틴 취소 + 완전 종료 대기
        //    cancelAndJoin(): cancel() 후 Job이 Completed 상태가 될 때까지 suspend
        sttJob?.cancelAndJoin()
        sttJob = null
        Log.d(TAG, "STT 코루틴 완전 종료 확인")

        // 2. 이 시점에는 processChunk()가 실행 중이지 않으므로
        //    Recognizer 단독 접근 보장 → 안전하게 finalResult 호출
        val finalJson = try {
            recognizer?.finalResult ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "finalResult 접근 오류: ${e.message}")
            ""
        }

        // 3. 마지막 미처리 텍스트 추가
        try {
            val obj      = gson.fromJson(finalJson, JsonObject::class.java)
            val lastText = obj.get("text")?.asString ?: ""
            if (lastText.isNotEmpty()) fullTranscript.append(lastText)
        } catch (e: Exception) { /* JSON 파싱 실패 무시 */ }

        val transcript = fullTranscript.toString().trim()
        Log.d(TAG, "최종 전사 완료: ${transcript.length}자")

        // 4. Recognizer/Model 해제 (finalResult 호출 이후에 해제해야 안전)
        recognizer?.close()
        model?.close()
        recognizer = null
        model       = null

        return transcript
    }

    // 기존 stop() - onCleared()에서 비상 정리용으로만 사용
    fun stop() {
        sttJob?.cancel()
        sttJob = null
        recognizer?.close()
        model?.close()
        recognizer = null
        model       = null
    }

    // ── assets → filesDir 복사 ────────────────────────────────────
    private fun copyModelFromAssets(): File {
        val destDir = File(context.filesDir, MODEL_ASSET_DIR)
        if (destDir.exists() && destDir.list()?.isNotEmpty() == true) {
            Log.d(TAG, "모델 이미 존재, 복사 스킵")
            return destDir
        }
        destDir.mkdirs()
        copyAssetFolder(MODEL_ASSET_DIR, destDir)
        Log.d(TAG, "모델 복사 완료")
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
            bytes[i * 2]     = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8).toByte()
        }
        return bytes
    }
}

data class VoskWord(
    val word:  String,
    val start: Double,
    val end:   Double,
    val conf:  Double
)
