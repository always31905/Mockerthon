package com.speechcoach.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.speechcoach.stt.FillerAnalysisResult
import com.speechcoach.analysis.VoiceAnalysisResult

/**
 * PresentationReport
 *
 * 발표 종료 후 모든 분석 결과를 담는 최종 리포트 모델.
 * Gson으로 JSON 직렬화해서 로컬 저장 또는 UI에 전달한다.
 *
 * 최종 JSON 구조:
 * {
 *   "presentation_id": "pres_1710000000000",
 *   "duration_sec": 612,
 *   "speed_analysis": {
 *     "avg_wpm": 145,
 *     "max_wpm": 220,
 *     "min_wpm": 80,
 *     "fast_sections": [...],
 *     "wpm_history": [[시각, wpm], ...]
 *   },
 *   "filler_analysis": {
 *     "total_fillers": 12,
 *     "filler_rate_percent": 3.2,
 *     "filler_breakdown": {
 *       "어": {"count": 5, "timestamps": [1.2, 3.5, ...]},
 *       ...
 *     }
 *   },
 *   "voice_analysis": {
 *     "avg_confidence_percent": 72,
 *     "avg_tremor_percent": 28,
 *     "tremor_sections": [{"start_sec": 150, "end_sec": 165, "intensity": 85}],
 *     "volume_history": [[ms, db], ...]
 *   },
 *   "overall_score": 76,
 *   "ai_feedback": "발표 중간에 습관어 사용이 많습니다..."
 * }
 */
data class PresentationReport(
    val presentationId:  String,
    val durationSec:     Int,
    val createdAt:       Long,              // epoch ms
    val speedAnalysis:   SpeedAnalysisReport,
    val fillerAnalysis:  FillerAnalysisReport,
    val voiceAnalysis:   VoiceAnalysisReport,
    val overallScore:    Int,               // 종합 점수 0~100
    val aiFeedback:      String             // 규칙 기반 종합 피드백
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        fun fromJson(json: String): PresentationReport = gson.fromJson(json, PresentationReport::class.java)
    }
}

// ── 속도 분석 리포트 ──────────────────────────────────────────────
data class SpeedAnalysisReport(
    val avgWpm:      Int,
    val maxWpm:      Int,
    val minWpm:      Int,
    // 빠름 구간 리스트 (리포트 타임라인 마커용)
    val fastSections: List<TimedSection>,
    val slowSections: List<TimedSection>,
    // 그래프 데이터: [[타임스탬프(초), WPM], ...]
    val wpmHistory:  List<List<Double>>
)

// ── 습관어 분석 리포트 ────────────────────────────────────────────
data class FillerAnalysisReport(
    val totalFillers:      Int,
    val fillerRatePercent: Double,
    // {"어": {"count": 5, "timestamps": [...]}, ...}
    val fillerBreakdown:   Map<String, FillerBreakdownItem>
)

data class FillerBreakdownItem(
    val count:      Int,
    val timestamps: List<Double>
)

// ── 목소리 분석 리포트 ────────────────────────────────────────────
data class VoiceAnalysisReport(
    val avgConfidencePercent: Int,
    val avgTremorPercent:     Int,
    // 떨림 위험 구간
    val tremorSections:       List<TimedSection>,
    // 볼륨 그래프: [[타임스탬프ms, dB], ...]
    val volumeHistory:        List<List<Double>>
)

// ── 공통: 시간 구간 마커 ──────────────────────────────────────────
data class TimedSection(
    val startSec:  Double,
    val endSec:    Double,
    val intensity: Int      // 해당 구간의 강도 (WPM 또는 떨림%)
)

// ─────────────────────────────────────────────────────────────────
// ReportBuilder: 분석 결과들을 모아서 PresentationReport를 생성
// ─────────────────────────────────────────────────────────────────
object ReportBuilder {

    /**
     * 모든 분석 결과를 종합해 최종 리포트를 생성한다.
     */
    fun build(
        durationSec:         Int,
        wpmHistory:          List<Pair<Double, Int>>,        // (초, WPM)
        fillerResult:        com.speechcoach.stt.FillerAnalysisResult,
        voiceResults:        List<VoiceAnalysisResult>,      // 시계열
        volumeHistory:       List<Pair<Long, Float>>,        // (ms, dB)
        presentationStartMs: Long
    ): PresentationReport {

        val id = "pres_${System.currentTimeMillis()}"

        // ── 속도 분석 ────────────────────────────────────────────
        val wpms = wpmHistory.map { it.second }
        val fastSections = findSections(wpmHistory) { it >= 200 }
        val slowSections = findSections(wpmHistory) { it <= 80 && it > 0 }

        val speedReport = SpeedAnalysisReport(
            avgWpm      = if (wpms.isNotEmpty()) wpms.average().toInt() else 0,
            maxWpm      = wpms.maxOrNull() ?: 0,
            minWpm      = wpms.minOrNull() ?: 0,
            fastSections = fastSections,
            slowSections = slowSections,
            wpmHistory  = wpmHistory.map { listOf(it.first, it.second.toDouble()) }
        )

        // ── 습관어 분석 ──────────────────────────────────────────
        val fillerReport = FillerAnalysisReport(
            totalFillers      = fillerResult.totalFillers,
            fillerRatePercent = fillerResult.fillerRatePercent,
            fillerBreakdown   = fillerResult.fillerBreakdown.mapValues { (_, v) ->
                FillerBreakdownItem(v.count, v.timestamps)
            }
        )

        // ── 목소리 분석 ──────────────────────────────────────────
        val avgConf   = if (voiceResults.isNotEmpty()) voiceResults.map { it.confidencePercent }.average().toInt() else 0
        val avgTremor = if (voiceResults.isNotEmpty()) voiceResults.map { it.tremorPercent }.average().toInt() else 0
        val tremorSections = findTremorSections(voiceResults)
        val volHistory = volumeHistory.map { listOf(it.first.toDouble(), it.second.toDouble()) }

        val voiceReport = VoiceAnalysisReport(
            avgConfidencePercent = avgConf,
            avgTremorPercent     = avgTremor,
            tremorSections       = tremorSections,
            volumeHistory        = volHistory
        )

        // ── 종합 점수 ────────────────────────────────────────────
        val score = calcOverallScore(speedReport, fillerReport, voiceReport)

        // ── AI 피드백 (규칙 기반) ────────────────────────────────
        val feedback = generateFeedback(speedReport, fillerReport, voiceReport)

        return PresentationReport(
            presentationId = id,
            durationSec    = durationSec,
            createdAt      = System.currentTimeMillis(),
            speedAnalysis  = speedReport,
            fillerAnalysis = fillerReport,
            voiceAnalysis  = voiceReport,
            overallScore   = score,
            aiFeedback     = feedback
        )
    }

    // ── 구간 탐지 (WPM 기준) ─────────────────────────────────────
    private fun findSections(
        history: List<Pair<Double, Int>>,
        predicate: (Int) -> Boolean
    ): List<TimedSection> {
        val sections = mutableListOf<TimedSection>()
        var sectionStart = -1.0
        var sectionIntensity = 0

        history.forEachIndexed { i, (time, wpm) ->
            if (predicate(wpm)) {
                if (sectionStart < 0) { sectionStart = time; sectionIntensity = wpm }
            } else {
                if (sectionStart >= 0) {
                    sections.add(TimedSection(sectionStart, time, sectionIntensity))
                    sectionStart = -1.0
                }
            }
        }
        return sections
    }

    // ── 떨림 위험 구간 탐지 ──────────────────────────────────────
    private fun findTremorSections(results: List<VoiceAnalysisResult>): List<TimedSection> {
        // 3초 윈도우 단위 인덱스를 시간으로 환산
        return results.mapIndexedNotNull { i, r ->
            if (r.tremorPercent >= 60) {
                val startSec = i * 3.0
                TimedSection(startSec, startSec + 3.0, r.tremorPercent)
            } else null
        }
    }

    // ── 종합 점수 계산 ────────────────────────────────────────────
    private fun calcOverallScore(
        speed: SpeedAnalysisReport,
        filler: FillerAnalysisReport,
        voice: VoiceAnalysisReport
    ): Int {
        var score = 100

        // 습관어 비율 페널티
        score -= (filler.fillerRatePercent * 2).toInt().coerceAtMost(30)

        // 빠름 구간 페널티
        score -= (speed.fastSections.size * 3).coerceAtMost(20)

        // 떨림 페널티
        score -= (voice.avgTremorPercent / 5).coerceAtMost(20)

        return score.coerceIn(0, 100)
    }

    // ── 규칙 기반 피드백 생성 ─────────────────────────────────────
    private fun generateFeedback(
        speed: SpeedAnalysisReport,
        filler: FillerAnalysisReport,
        voice: VoiceAnalysisReport
    ): String {
        val sb = StringBuilder()

        if (filler.fillerRatePercent >= 5.0) {
            sb.appendLine("💬 발표 중 불필요한 단어(습관어)의 사용이 전체의 ${String.format("%.1f", filler.fillerRatePercent)}%로 다소 많습니다.")
        }
        if (speed.fastSections.isNotEmpty()) {
            sb.appendLine("⚡ 발표 중 말이 빨라지는 구간이 ${speed.fastSections.size}번 감지되었습니다. 긴장 시 의식적으로 속도를 늦춰보세요.")
        }
        if (voice.avgTremorPercent >= 50) {
            val tremorTime = voice.tremorSections.firstOrNull()
                ?.let { "${(it.startSec / 60).toInt()}분 ${(it.startSec % 60).toInt()}초" } ?: ""
            sb.appendLine("🎙️ 목소리 떨림이 감지되었습니다${if (tremorTime.isNotEmpty()) " (주요 구간: $tremorTime)" else ""}. 발표 전 심호흡을 권장합니다.")
        }
        if (voice.avgConfidencePercent >= 70) {
            sb.appendLine("✅ 전반적으로 자신감 있는 목소리를 유지했습니다. 잘하셨습니다!")
        }
        if (sb.isEmpty()) {
            sb.appendLine("✅ 훌륭한 발표였습니다! 페이스, 습관어, 목소리 모두 양호합니다.")
        }

        return sb.toString().trim()
    }
}
