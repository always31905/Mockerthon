package com.speechcoach.analysis

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * VoiceAnalysisModel — TFLite 입력 타입 수정
 *
 * [문제] INT8 양자화 모델에 Float32(4byte) 버퍼를 넣어서 크기 불일치 발생.
 *       "with 30 bytes from a Java Buffer with 120 bytes"
 *       → 모델 기대: 15프레임 × 2피처 × 1byte(INT8) = 30 bytes
 *       → 실제 전달: 15프레임 × 2피처 × 4byte(Float32) = 120 bytes
 *
 * [해결 1] Float32 모델 사용 (양자화 없이 변환한 경우) → Float32 버퍼 유지
 * [해결 2] INT8 모델 사용 (양자화한 경우) → INT8 ByteBuffer로 변환
 *
 * train_voice_model.py에서 양자화 없이 변환한 경우가 많으므로
 * 기본값을 Float32 모드로 설정하고, 오류 발생 시 INT8 모드로 폴백.
 */
class VoiceAnalysisModel(private val context: Context) {

    companion object {
        private const val TAG             = "VoiceAnalysisModel"
        private const val MODEL_FILENAME  = "voice_analysis.tflite"
        private const val INPUT_FEATURES  = 2  // rms + pitch
    }

    private var interpreter: Interpreter? = null
    private var useInt8Input = false   // 모델 로드 후 입력 타입 자동 감지

    fun load(): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = 2
                useNNAPI   = false
            }
            interpreter = Interpreter(loadModelFile(), options)

            // 입력 텐서 타입 확인 → INT8이면 useInt8Input = true
            val inputTensor = interpreter!!.getInputTensor(0)
            useInt8Input = (inputTensor.dataType() == org.tensorflow.lite.DataType.INT8)
            Log.d(TAG, "TFLite 모델 로드 완료 | 입력타입=${inputTensor.dataType()} | INT8모드=$useInt8Input")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TFLite 모델 로드 실패: ${e.message}")
            false
        }
    }

    fun analyze(rmsArray: FloatArray, pitchArray: FloatArray): VoiceAnalysisResult {
        val interp = interpreter ?: return VoiceAnalysisResult.empty()
        val windowSize = minOf(rmsArray.size, pitchArray.size)
        if (windowSize == 0) return VoiceAnalysisResult.empty()

        return try {
            if (useInt8Input) {
                runInt8(interp, rmsArray, pitchArray, windowSize)
            } else {
                runFloat32(interp, rmsArray, pitchArray, windowSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TFLite 추론 오류: ${e.message}")
            VoiceAnalysisResult.empty()
        }
    }

    // ── Float32 추론 (양자화 없는 모델) ──────────────────────────
    private fun runFloat32(
        interp: Interpreter,
        rmsArray: FloatArray,
        pitchArray: FloatArray,
        windowSize: Int
    ): VoiceAnalysisResult {
        // [1, windowSize, 2] × Float32(4byte)
        val inputBuffer = ByteBuffer
            .allocateDirect(windowSize * INPUT_FEATURES * 4)
            .order(ByteOrder.nativeOrder())

        for (i in 0 until windowSize) {
            inputBuffer.putFloat(normalizeRms(rmsArray[i]))
            inputBuffer.putFloat(normalizePitch(pitchArray[i]))
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(2) }
        interp.run(inputBuffer, output)

        return toResult(output[0][0], output[0][1], windowSize)
    }

    // ── INT8 추론 (양자화 모델) ───────────────────────────────────
    private fun runInt8(
        interp: Interpreter,
        rmsArray: FloatArray,
        pitchArray: FloatArray,
        windowSize: Int
    ): VoiceAnalysisResult {
        // [1, windowSize, 2] × INT8(1byte)
        // Float 0~1 → INT8 범위 -128~127 로 스케일
        val inputBuffer = ByteBuffer
            .allocateDirect(windowSize * INPUT_FEATURES)
            .order(ByteOrder.nativeOrder())

        for (i in 0 until windowSize) {
            val rmsNorm   = normalizeRms(rmsArray[i])
            val pitchNorm = normalizePitch(pitchArray[i])
            inputBuffer.put(floatToInt8(rmsNorm))
            inputBuffer.put(floatToInt8(pitchNorm))
        }
        inputBuffer.rewind()

        // INT8 출력 텐서
        val outputBuffer = ByteBuffer
            .allocateDirect(2)
            .order(ByteOrder.nativeOrder())
        interp.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val conf   = int8ToFloat(outputBuffer.get())
        val tremor = int8ToFloat(outputBuffer.get())
        return toResult(conf, tremor, windowSize)
    }

    private fun floatToInt8(f: Float): Byte =
        (f * 127f).toInt().coerceIn(-128, 127).toByte()

    private fun int8ToFloat(b: Byte): Float =
        (b.toInt() / 127f).coerceIn(0f, 1f)

    private fun toResult(conf: Float, tremor: Float, windowSize: Int) = VoiceAnalysisResult(
        confidencePercent = (conf   * 100).toInt().coerceIn(0, 100),
        tremorPercent     = (tremor * 100).toInt().coerceIn(0, 100),
        isReliable        = windowSize >= 10
    )

    // ── 정규화 ────────────────────────────────────────────────────
    private fun normalizeRms(db: Float): Float   = ((db + 60f) / 50f).coerceIn(0f, 1f)
    private fun normalizePitch(hz: Float): Float =
        if (hz < 0) 0f else ((hz - 80f) / 320f).coerceIn(0f, 1f)

    private fun loadModelFile(): MappedByteBuffer {
        val assetFd     = context.assets.openFd(MODEL_FILENAME)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

data class VoiceAnalysisResult(
    val confidencePercent: Int,
    val tremorPercent:     Int,
    val isReliable:        Boolean
) {
    companion object { fun empty() = VoiceAnalysisResult(0, 0, false) }
    val isTremorHigh:    Boolean get() = tremorPercent    >= 60
    val isConfidenceLow: Boolean get() = confidencePercent <= 40
}
