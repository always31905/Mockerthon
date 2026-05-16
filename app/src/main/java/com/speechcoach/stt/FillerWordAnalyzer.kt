package com.speechcoach.stt

class FillerWordAnalyzer {

    val fillerEntries: List<FillerEntry> = listOf(
        FillerEntry("어",         "(?<!\\S)어+(?!\\S)"),
        FillerEntry("음",         "(?<!\\S)음+(?!\\S)"),
        FillerEntry("그",         "(?<!\\S)그(?!\\S)"),
        FillerEntry("아",         "(?<!\\S)아+(?!\\S)"),
        FillerEntry("에",         "(?<!\\S)에+(?!\\S)"),
        FillerEntry("이제",       "(?<!\\S)이제(?!\\S)"),
        FillerEntry("근데",       "(?<!\\S)근데(?!\\S)"),
        FillerEntry("그래서",     "(?<!\\S)그래서(?!\\S)"),
        FillerEntry("그러니까",   "(?<!\\S)그러니까(?!\\S)"),
        FillerEntry("그리고",     "(?<!\\S)그리고(?!\\S)"),
        FillerEntry("뭐",         "(?<!\\S)뭐(?!\\S)"),
        FillerEntry("사실",       "(?<!\\S)사실(?!\\S)"),
        FillerEntry("기본적으로", "(?<!\\S)기본적으로(?!\\S)"),
        FillerEntry("일단",       "(?<!\\S)일단(?!\\S)"),
        FillerEntry("아무튼",     "(?<!\\S)아무튼(?!\\S)"),
        FillerEntry("뭐랄까",     "(?<!\\S)뭐랄까(?!\\S)"),
        FillerEntry("솔직히",     "(?<!\\S)솔직히(?!\\S)"),
        FillerEntry("진짜",       "(?<!\\S)진짜(?!\\S)"),
        FillerEntry("약간",       "(?<!\\S)약간(?!\\S)"),
        FillerEntry("되게",       "(?<!\\S)되게(?!\\S)"),
        FillerEntry("좀",         "(?<!\\S)좀(?!\\S)")
    )

    fun analyze(fullText: String, segments: List<SpeechWord>): FillerAnalysisResult {
        val breakdown = mutableMapOf<String, FillerDetail>()

        fillerEntries.forEach { entry ->
            val regex = Regex(entry.pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(fullText).toList()
            if (matches.isEmpty()) return@forEach

            // 💡 문장 내에 습관어가 포함되어 있으면 해당 문장의 시작 시간을 타임스탬프로 사용
            val timestamps = segments
                .filter { regex.containsMatchIn(it.word) }
                .map { it.start }.map{it.toDouble()}

            breakdown[entry.displayName] = FillerDetail(
                count = matches.size,
                timestamps = timestamps
            )
        }

        val totalFillers = breakdown.values.sumOf { it.count }
        val totalWords = fullText.trim().split("\\s+".toRegex()).size
        val rate = if (totalWords > 0) totalFillers.toDouble() / totalWords * 100 else 0.0

        return FillerAnalysisResult(
            totalFillers = totalFillers,
            fillerBreakdown = breakdown,
            fillerRatePercent = rate
        )
    }

    fun highlightPatterns(): List<Pair<String, Regex>> =
        fillerEntries.map { Pair(it.displayName, Regex(it.pattern, RegexOption.IGNORE_CASE)) }
}

data class FillerEntry(val displayName: String, val pattern: String)
data class FillerDetail(val count: Int, val timestamps: List<Double>)
data class FillerAnalysisResult(
    val totalFillers: Int,
    val fillerBreakdown: Map<String, FillerDetail>,
    val fillerRatePercent: Double
)
