package com.speechcoach.analysis

import android.content.Context
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * VoiceAnalysisModel (규칙 기반으로 완전 교체)
 *
 * ── 교체 이유 ────────────────────────────────────────────────────
 * 기존 TFLite 1D-CNN 모델은 실제 사람 목소리가 아닌
 * 더미 데이터(난수)로 학습되어 실질적인 떨림 감지가 불가능했음.
 *
 * ── 새 방식: CalibrationManager 기준점 기반 규칙 분석 ────────────
 *
 * [떨림 점수]
 *   핵심 지표: Pitch 표준편차 (음높이 흔들림 정도)
 *   - 캘리브레이션에서 측정한 사용자 고유 baselinePitchStd 대비
 *     현재 윈도우의 Pitch 표준편차가 몇 배인지로 판정
 *   - 추가 지표: Jitter (연속 프레임 간 Pitch 변화량 평균)
 *   - 두 지표를 가중 합산 → 0~100%
 *
 * [자신감 점수]
 *   - 볼륨(RMS dB)이 기준치 대비 얼마나 안정적인지
 *   - Pitch가 정상 발화 범위(80~400Hz) 안에서 얼마나 유지되는지
 *   - 묵음 비율(Pitch = -1)이 얼마나 적은지
 *
 * ── 캘리브레이션 없을 때 폴백 ────────────────────────────────────
 *   절대값 기준으로 동작 (baselinePitchStd = 20Hz 가정)
 */
class VoiceAnalysisModel(private val context: Context) {

    companion object {
        // 캘리브레이션 없을 때 사용하는 기본 기준값
        private const val DEFAULT_BASELINE_PITCH_STD = 20f  // Hz
        private const val DEFAULT_BASELINE_RMS_DB    = -30f // dB

        // 떨림 판정 배수 (기준 Std의 몇 배 이상이면 떨림으로 볼 것인가)
        private const val TREMOR_MILD_MULTIPLIER   = 1.5f  // 1.5배: 경미한 떨림
        private const val TREMOR_SEVERE_MULTIPLIER = 3.0f  // 3.0배: 심한 떨림

        // 정상 Pitch 범위 (Hz)
        private const val PITCH_MIN_NORMAL = 80f
        private const val PITCH_MAX_NORMAL = 400f
    }

    private lateinit var calibManager: CalibrationManager

    // TFLite interpreter 대신 CalibrationManager만 필요
    fun load(): Boolean {
        calibManager = CalibrationManager(context)
        return true  // 항상 성공 (모델 파일 불필요)
    }

    /**
     * 규칙 기반 분석 실행
     *
     * @param rmsArray   RMS dB 배열 (3초 윈도우)
     * @param pitchArray Pitch Hz 배열 (-1 = 묵음)
     * @return VoiceAnalysisResult
     */
    fun analyze(rmsArray: FloatArray, pitchArray: FloatArray): VoiceAnalysisResult {
        val windowSize = minOf(rmsArray.size, pitchArray.size)
        if (windowSize < 3) return VoiceAnalysisResult.empty()

        // 묵음(-1) 제외한 유효 Pitch만 추출
        val validPitches = pitchArray.filter { it > 0 }
        if (validPitches.isEmpty()) return VoiceAnalysisResult.empty()

        val baseline = calibManager    // 캘리브레이션 완료 여부와 무관하게 사용

        // ── 1. Pitch 표준편차 계산 ────────────────────────────────
        val pitchStd    = standardDeviation(validPitches)
        val baselineStd = if (calibManager.isCalibrated && calibManager.baselinePitchStd > 0)
            calibManager.baselinePitchStd else DEFAULT_BASELINE_PITCH_STD

        // ── 2. Jitter 계산 (연속 프레임 간 Pitch 변화량 평균) ──────
        // 음높이가 갑자기 튀는 정도 → 떨림의 직접적 지표
        val jitter = if (validPitches.size >= 2) {
            validPitches.zipWithNext()
                .map { (a, b) -> abs(b - a) }
                .average().toFloat()
        } else 0f

        // ── 3. 떨림 점수 계산 (0~100) ────────────────────────────
        // pitchStd/baselineStd: 기준 대비 배수 (1.0 = 기준과 동일)
        val stdRatio    = (pitchStd / baselineStd).coerceIn(0f, TREMOR_SEVERE_MULTIPLIER)
        // jitter 정규화: 50Hz 이상이면 최대 떨림으로 간주
        val jitterScore = (jitter / 50f).coerceIn(0f, 1f)

        // 표준편차 비율 70% + Jitter 30% 가중 합산
        val rawTremor   = (stdRatio / TREMOR_SEVERE_MULTIPLIER) * 0.7f + jitterScore * 0.3f
        val tremorPercent = (rawTremor * 100).toInt().coerceIn(0, 100)

        // ── 4. 자신감 점수 계산 (0~100) ──────────────────────────

        // (a) 볼륨 안정성: RMS의 표준편차가 작을수록 안정적 → 자신감 높음
        val validRms    = rmsArray.filter { it > -80f }
        val rmsStd      = if (validRms.size >= 2) standardDeviation(validRms) else 20f
        // rmsStd 0dB → 100%, 20dB → 0%
        val rmsStability = (1f - (rmsStd / 20f)).coerceIn(0f, 1f)

        // (b) Pitch 정상 범위 유지율: 발화 중 80~400Hz 범위 비율
        val pitchInRange = validPitches.count { it in PITCH_MIN_NORMAL..PITCH_MAX_NORMAL }
        val pitchRangeScore = (pitchInRange.toFloat() / validPitches.size).coerceIn(0f, 1f)

        // (c) 발화 밀도: 묵음이 적을수록 자신감 있게 말하고 있음
        val silenceRatio  = (pitchArray.count { it < 0 }.toFloat() / windowSize).coerceIn(0f, 1f)
        val densityScore  = 1f - silenceRatio

        // (d) 떨림이 심하면 자신감도 낮게 반영
        val tremorPenalty = rawTremor * 0.4f

        // 자신감 = 볼륨안정성*30% + Pitch범위*30% + 발화밀도*40% - 떨림페널티
        val rawConfidence = (rmsStability * 0.3f +
                             pitchRangeScore * 0.3f +
                             densityScore * 0.4f) - tremorPenalty
        val confidencePercent = (rawConfidence * 100).toInt().coerceIn(0, 100)

        return VoiceAnalysisResult(
            confidencePercent = confidencePercent,
            tremorPercent     = tremorPercent,
            isReliable        = windowSize >= 5,
            // 디버그용 세부 지표
            pitchStd          = pitchStd,
            jitter            = jitter,
            baselineStd       = baselineStd
        )
    }

    // ── 표준편차 계산 ─────────────────────────────────────────────
    private fun standardDeviation(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean     = values.average().toFloat()
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size
        return sqrt(variance).toFloat()
    }

    // TFLite 시절 호환용 (아무것도 안 함)
    fun close() {}
}

// ── 결과 데이터 클래스 ────────────────────────────────────────────
data class VoiceAnalysisResult(
    val confidencePercent: Int,
    val tremorPercent:     Int,
    val isReliable:        Boolean,
    // 세부 지표 (리포트 디버그용)
    val pitchStd:          Float = 0f,
    val jitter:            Float = 0f,
    val baselineStd:       Float = 0f
) {
    companion object {
        fun empty() = VoiceAnalysisResult(0, 0, false)
    }

    val isTremorHigh:    Boolean get() = tremorPercent     >= 60
    val isConfidenceLow: Boolean get() = confidencePercent <= 40

    /** 떨림 심각도 레이블 */
    val tremorLabel: String get() = when {
        tremorPercent >= 70 -> "심함"
        tremorPercent >= 40 -> "경미"
        else                -> "안정"
    }
}
