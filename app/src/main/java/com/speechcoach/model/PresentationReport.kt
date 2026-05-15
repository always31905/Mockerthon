package com.speechcoach.model

import com.google.gson.Gson
import com.speechcoach.stt.SpeechWord

data class PresentationReport(
    val presentationId: String,
    val durationSec:    Int,
    val createdAt:      Long,
    val fullTranscript: String,
    val allWords:       List<SpeechWord> = emptyList(), // ← 추가: 단어별 타임스탬프 리스트
    val speedAnalysis:  SpeedAnalysisReport,
    val fillerAnalysis: FillerAnalysisReport,
    val voiceAnalysis:  VoiceAnalysisReport,
    val overallScore:   Int,
    val aiFeedback:     String
) {
    fun toJson(): String = Gson().toJson(this)
    companion object {
        fun fromJson(json: String): PresentationReport = Gson().fromJson(json, PresentationReport::class.java)
    }
}

data class SpeedAnalysisReport(
    val avgWpm:       Int,
    val maxWpm:       Int,
    val minWpm:       Int,
    val fastSections: List<TimedSection>,
    val slowSections: List<TimedSection>,
    val wpmHistory:   List<List<Double>>
)

data class FillerAnalysisReport(
    val totalFillers:      Int,
    val fillerRatePercent: Double,
    val fillerBreakdown:   Map<String, FillerBreakdownItem>
)

data class FillerBreakdownItem(val count: Int, val timestamps: List<Double>)

data class VoiceAnalysisReport(
    val avgConfidencePercent: Int,
    val avgTremorPercent:     Int,
    val tremorSections:       List<TimedSection>,
    val volumeHistory:        List<List<Double>>
)

data class TimedSection(val startSec: Double, val endSec: Double, val intensity: Int)

// ── ReportBuilder ─────────────────────────────────────────────────
object ReportBuilder {
    fun build(
        durationSec:         Int,
        fullTranscript:      String,
        allWords:            List<SpeechWord>, // ← 추가
        wpmHistory:          List<Pair<Double, Int>>,
        fillerResult:        com.speechcoach.stt.FillerAnalysisResult,
        voiceResults:        List<com.speechcoach.analysis.VoiceAnalysisResult>,
        volumeHistory:       List<Pair<Long, Float>>,
        presentationStartMs: Long
    ): PresentationReport {
        val wpms = wpmHistory.map { it.second }
        val speedReport = SpeedAnalysisReport(
            avgWpm       = if (wpms.isNotEmpty()) wpms.average().toInt() else 0,
            maxWpm       = wpms.maxOrNull() ?: 0,
            minWpm       = wpms.minOrNull() ?: 0,
            fastSections = findSections(wpmHistory) { it >= 180 }, // 180 이상 빠름
            slowSections = findSections(wpmHistory) { it in 1..90 }, // 90 이하 느림
            wpmHistory   = wpmHistory.map { listOf(it.first, it.second.toDouble()) }
        )

        val fillerReport = FillerAnalysisReport(
            totalFillers      = fillerResult.totalFillers,
            fillerRatePercent = fillerResult.fillerRatePercent,
            fillerBreakdown   = fillerResult.fillerBreakdown.mapValues { (_, v) ->
                FillerBreakdownItem(v.count, v.timestamps)
            }
        )

        val voiceReport = VoiceAnalysisReport(
            avgConfidencePercent = if (voiceResults.isNotEmpty()) voiceResults.map { it.confidencePercent }.average().toInt() else 0,
            avgTremorPercent     = if (voiceResults.isNotEmpty()) voiceResults.map { it.tremorPercent }.average().toInt() else 0,
            tremorSections       = resultsToSections(voiceResults),
            volumeHistory        = volumeHistory.map { listOf(it.first.toDouble(), it.second.toDouble()) }
        )

        return PresentationReport(
            presentationId = "pres_${System.currentTimeMillis()}",
            durationSec    = durationSec,
            createdAt      = System.currentTimeMillis(),
            fullTranscript = fullTranscript,
            allWords       = allWords, // ← 저장
            speedAnalysis  = speedReport,
            fillerAnalysis = fillerReport,
            voiceAnalysis  = voiceReport,
            overallScore   = 85, // 임시
            aiFeedback     = "분석 완료"
        )
    }

    private fun findSections(history: List<Pair<Double, Int>>, predicate: (Int) -> Boolean): List<TimedSection> {
        val sections = mutableListOf<TimedSection>()
        var start = -1.0; var intensity = 0
        history.forEach { (time, wpm) ->
            if (predicate(wpm)) { if (start < 0) { start = time; intensity = wpm } }
            else { if (start >= 0) { sections.add(TimedSection(start, time, intensity)); start = -1.0 } }
        }
        return sections
    }

    private fun resultsToSections(results: List<com.speechcoach.analysis.VoiceAnalysisResult>) =
        results.mapIndexedNotNull { i, r ->
            if (r.tremorPercent >= 60) TimedSection(i * 3.0, i * 3.0 + 3.0, r.tremorPercent) else null
        }
}
