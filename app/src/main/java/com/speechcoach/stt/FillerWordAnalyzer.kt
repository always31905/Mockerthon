package com.speechcoach.stt

/**
 * FillerWordAnalyzer (Track 2 - 발표 종료 후)
 *
 * 전체 VOSK STT 텍스트에서 습관어(필러 워드)를
 * Regex로 찾아 횟수와 타임스탬프를 매핑한다.
 *
 * 결과 JSON 구조:
 * {
 *   "total_fillers": 12,
 *   "filler_breakdown": {
 *     "어": {"count": 5, "timestamps": [1.2, 3.5, 7.8, ...]},
 *     "이제": {"count": 3, "timestamps": [12.1, 20.3, 35.0]},
 *     ...
 *   }
 * }
 */
class FillerWordAnalyzer {

    // ── 분석 대상 습관어 목록 ────────────────────────────────────
    // 실제 서비스 시 사용자 커스터마이즈 기능 추가 권장
    private val fillerPatterns = listOf(
        "어+",         // 어, 어어, 어어어
        "음+",         // 음, 음음
        "그+",         // 그, 그그
        "이제",
        "뭐지",
        "사실",
        "근데",
        "아\\s*그",    // "아 그"
        "그러니까",
        "뭐랄까",
        "아무튼",
        "기본적으로",
        "일단"
    )

    /**
     * 전체 텍스트와 단어 타임스탬프 리스트를 받아 습관어 분석
     *
     * @param fullText   전체 STT 텍스트
     * @param allWords   VOSK 단어 타임스탬프 리스트 (VoskWord)
     * @return FillerAnalysisResult
     */
    fun analyze(fullText: String, allWords: List<VoskWord>): FillerAnalysisResult {
        val breakdown = mutableMapOf<String, FillerDetail>()

        fillerPatterns.forEach { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matchedWord = pattern.replace("+", "").replace("\\s*", " ").trim()

            // 텍스트에서 Regex 매칭
            val matches = regex.findAll(fullText).toList()
            if (matches.isEmpty()) return@forEach

            // 타임스탬프 매핑: VoskWord 리스트에서 해당 단어를 찾아 시각 추출
            val timestamps = allWords
                .filter { regex.containsMatchIn(it.word) }
                .map { it.start }

            breakdown[matchedWord] = FillerDetail(
                count      = matches.size,
                timestamps = timestamps
            )
        }

        val totalFillers = breakdown.values.sumOf { it.count }

        return FillerAnalysisResult(
            totalFillers   = totalFillers,
            fillerBreakdown = breakdown,
            fillerRatePercent = calcFillerRate(fullText, totalFillers)
        )
    }

    // ── 습관어 비율: 전체 단어 중 몇 % ─────────────────────────
    private fun calcFillerRate(fullText: String, fillerCount: Int): Double {
        val totalWords = fullText.trim().split("\\s+".toRegex()).size
        return if (totalWords > 0) fillerCount.toDouble() / totalWords * 100 else 0.0
    }
}

// ── 결과 데이터 클래스 ────────────────────────────────────────────

data class FillerDetail(
    val count:      Int,
    val timestamps: List<Double>    // 등장 시각 (초)
)

data class FillerAnalysisResult(
    val totalFillers:      Int,
    val fillerBreakdown:   Map<String, FillerDetail>,
    val fillerRatePercent: Double   // 전체 발화 중 습관어 비율(%)
)
