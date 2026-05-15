package com.speechcoach.stt

class FillerWordAnalyzer {

    // 정규표현식 설명:
    // (?<!\S) : 앞에 공백이 있거나 문장 시작점이어야 함
    // [.?!~]* : 단어 뒤에 붙는 문장 부호를 포함하여 매칭
    // (?![가-힣]) : 뒤에 한글이 바로 오지 않아야 함 (다른 단어의 일부인 경우 제외)
    val fillerEntries: List<FillerEntry> = listOf(
        FillerEntry("어",         "(?<!\\S)어+[.?!~]*(?![가-힣])"),
        FillerEntry("음",         "(?<!\\S)음+[.?!~]*(?![가-힣])"),
        FillerEntry("그",         "(?<!\\S)그[.?!~]*(?![가-힣])"),
        FillerEntry("아",         "(?<!\\S)아+[.?!~]*(?![가-힣])"),
        FillerEntry("에",         "(?<!\\S)에+[.?!~]*(?![가-힣])"),
        FillerEntry("이제",       "(?<!\\S)이제[.?!~]*(?![가-힣])"),
        FillerEntry("근데",       "(?<!\\S)근데[.?!~]*(?![가-힣])"),
        FillerEntry("그래서",     "(?<!\\S)그래서[.?!~]*(?![가-힣])"),
        FillerEntry("그러니까",   "(?<!\\S)그러니까[.?!~]*(?![가-힣])"),
        FillerEntry("그리고",     "(?<!\\S)그리고[.?!~]*(?![가-힣])"),
        FillerEntry("뭐",         "(?<!\\S)뭐[.?!~]*(?![가-힣])"),
        FillerEntry("사실",       "(?<!\\S)사실[.?!~]*(?![가-힣])"),
        FillerEntry("기본적으로", "(?<!\\S)기본적으로[.?!~]*(?![가-힣])"),
        FillerEntry("일단",       "(?<!\\S)일단[.?!~]*(?![가-힣])"),
        FillerEntry("아무튼",     "(?<!\\S)아무튼[.?!~]*(?![가-힣])"),
        FillerEntry("뭐랄까",     "(?<!\\S)뭐랄까[.?!~]*(?![가-힣])"),
        FillerEntry("솔직히",     "(?<!\\S)솔직히[.?!~]*(?![가-힣])"),
        FillerEntry("진짜",       "(?<!\\S)진짜[.?!~]*(?![가-힣])"),
        FillerEntry("약간",       "(?<!\\S)약간[.?!~]*(?![가-힣])"),
        FillerEntry("되게",       "(?<!\\S)되게[.?!~]*(?![가-힣])"),
        FillerEntry("좀",         "(?<!\\S)좀[.?!~]*(?![가-힣])")
    )

    fun analyze(fullText: String, allWords: List<SpeechWord>): FillerAnalysisResult {
        val breakdown = mutableMapOf<String, FillerDetail>()

        fillerEntries.forEach { entry ->
            val regex = Regex(entry.pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(fullText).toList()
            if (matches.isEmpty()) return@forEach

            // 타임스탬프 추출 시에도 문장 부호 포함 여부 체크
            val timestamps = allWords
                .filter { regex.containsMatchIn(it.word) }
                .map { it.start }

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
