package com.speechcoach.analysis

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * CalibrationManager
 *
 * 발표 전 사용자에게 30초짜리 글을 읽게 한 뒤,
 * 그 목소리를 기준점으로 저장해서 이후 발표에서의
 * 떨림을 '개인화된 기준'으로 분석한다.
 *
 * 저장 방식: SharedPreferences (온디바이스 영구 저장)
 *
 * 기준점 데이터:
 * - baselineRmsDb:      평상시 평균 볼륨 (dB)
 * - baselinePitchHz:    평상시 평균 음높이 (Hz)
 * - baselinePitchStd:   평상시 음높이 표준편차 (안정도 기준)
 */
class CalibrationManager(context: Context) {

    companion object {
        private const val PREF_NAME           = "speech_coach_calibration"
        private const val KEY_BASELINE_RMS    = "baseline_rms_db"
        private const val KEY_BASELINE_PITCH  = "baseline_pitch_hz"
        private const val KEY_BASELINE_STD    = "baseline_pitch_std"
        private const val KEY_IS_CALIBRATED   = "is_calibrated"

        // 떨림 판정: 현재 pitch_std가 baseline의 몇 배를 넘으면 떨림으로 간주
        const val TREMOR_STD_MULTIPLIER = 2.0f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── 캘리브레이션 수집 버퍼 ──────────────────────────────────
    private val calibRmsBuffer   = mutableListOf<Float>()
    private val calibPitchBuffer = mutableListOf<Float>()
    private var isCollecting     = false

    val isCalibrated: Boolean
        get() = prefs.getBoolean(KEY_IS_CALIBRATED, false)

    val baselineRmsDb: Float
        get() = prefs.getFloat(KEY_BASELINE_RMS, -30f)

    val baselinePitchHz: Float
        get() = prefs.getFloat(KEY_BASELINE_PITCH, 180f)

    val baselinePitchStd: Float
        get() = prefs.getFloat(KEY_BASELINE_STD, 20f)

    // ── 수집 시작 ────────────────────────────────────────────────
    fun startCalibration() {
        calibRmsBuffer.clear()
        calibPitchBuffer.clear()
        isCollecting = true
    }

    // ── 프레임 데이터 수집 (TarsosAudioAnalyzer의 onFrameAnalyzed에서 호출) ──
    fun addFrame(rmsDb: Float, pitchHz: Float) {
        if (!isCollecting) return
        if (rmsDb > -60f) calibRmsBuffer.add(rmsDb)    // 묵음 제외
        if (pitchHz > 0)  calibPitchBuffer.add(pitchHz) // 비음성 제외
    }

    // ── 수집 종료 및 기준점 계산/저장 ────────────────────────────
    fun finishCalibration(): CalibrationResult {
        isCollecting = false

        if (calibRmsBuffer.size < 10 || calibPitchBuffer.size < 10) {
            return CalibrationResult.insufficient()
        }

        val avgRms   = calibRmsBuffer.average().toFloat()
        val avgPitch = calibPitchBuffer.average().toFloat()
        val pitchStd = standardDeviation(calibPitchBuffer)

        prefs.edit().apply {
            putFloat(KEY_BASELINE_RMS,  avgRms)
            putFloat(KEY_BASELINE_PITCH, avgPitch)
            putFloat(KEY_BASELINE_STD,   pitchStd)
            putBoolean(KEY_IS_CALIBRATED, true)
            apply()
        }

        return CalibrationResult(
            success       = true,
            avgRmsDb      = avgRms,
            avgPitchHz    = avgPitch,
            pitchStdHz    = pitchStd,
            sampleCount   = calibPitchBuffer.size
        )
    }

    /**
     * 현재 윈도우의 떨림 강도를 기준점 대비 계산
     * @return 0.0 (안정) ~ 1.0 이상 (심한 떨림)
     */
    fun calcTremorIntensity(currentPitchArray: FloatArray): Float {
        val validPitches = currentPitchArray.filter { it > 0 }
        if (validPitches.isEmpty()) return 0f
        val currentStd = standardDeviation(validPitches.map { it })
        return (currentStd / baselinePitchStd.coerceAtLeast(1f))
            .coerceIn(0f, 3f)
    }

    // ── 표준편차 계산 ────────────────────────────────────────────
    private fun standardDeviation(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size
        return Math.sqrt(variance).toFloat()
    }

    fun resetCalibration() {
        prefs.edit().clear().apply()
        calibRmsBuffer.clear()
        calibPitchBuffer.clear()
    }
}

data class CalibrationResult(
    val success:     Boolean,
    val avgRmsDb:    Float = 0f,
    val avgPitchHz:  Float = 0f,
    val pitchStdHz:  Float = 0f,
    val sampleCount: Int   = 0
) {
    companion object {
        fun insufficient() = CalibrationResult(false)
    }
}
