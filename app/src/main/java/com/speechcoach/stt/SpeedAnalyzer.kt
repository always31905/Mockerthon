package com.speechcoach.stt

import com.speechcoach.audio.AudioConfig

/**
 * SpeedAnalyzer
 *
 * onPartialText()는 SpeechRecognizer의 누적 텍스트를 받으므로
 * 이전 partial과 비교해 새로 추가된 단어만 WPM에 카운트한다.
 *
 * onFinalText()는 확정된 문장을 받으며 partial 버퍼를 초기화한다.
 */
class SpeedAnalyzer {

    enum class SpeedState { NORMAL, FAST, SLOW }

    data class SpeedResult(
        val wpm: Int,
        val state: SpeedState,
        val windowSec: Int
    )

    private val WINDOW_SEC = 3

    // 실제 WPM 계산용: (단어, 수신시각ms)
    private val wordBuffer = ArrayDeque<Pair<String, Long>>()
    private val wpmHistory = mutableListOf<Pair<Double, Int>>()

    private var startTimeMs = 0L
    private var lastCalcMs = 0L

    // partial 중복 방지용: 이전 partial 단어 수
    private var lastPartialWordCount = 0

    fun reset() {
        wordBuffer.clear()
        wpmHistory.clear()
        startTimeMs = System.currentTimeMillis()
        lastCalcMs = startTimeMs
        lastPartialWordCount = 0
    }

    /**
     * VOSK partial/final 텍스트 수신 시 호출
     * isFinal=true이면 중복 방지 카운터 초기화
     */
    fun onPartialText(text: String, nowMs: Long, isFinal: Boolean = false): SpeedResult {
        val allWords = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }

        // 새로 추가된 단어만 추출 (중복 카운팅 방지)
        val newWords = if (isFinal) {
            // 확정 결과: partial 카운터 리셋 후 전체 단어 사용
            lastPartialWordCount = 0
            allWords
        } else {
            // partial: 이전 partial보다 늘어난 단어만
            val newCount = allWords.size - lastPartialWordCount
            lastPartialWordCount = allWords.size
            if (newCount <= 0) return getLastResult(nowMs)
            allWords.takeLast(newCount)
        }

        if (newWords.isEmpty()) return getLastResult(nowMs)

        // 슬라이딩 윈도우에 추가
        newWords.forEach { wordBuffer.addLast(Pair(it, nowMs)) }

        // 윈도우 밖 제거
        val cutMs = nowMs - WINDOW_SEC * 1000L
        while (wordBuffer.isNotEmpty() && wordBuffer.first().second < cutMs) {
            wordBuffer.removeFirst()
        }

        val wpm = (wordBuffer.size.toDouble() / WINDOW_SEC * 60).toInt()

        // 1초 간격 히스토리
        if (nowMs - lastCalcMs >= 1000L) {
            val elapsed = (nowMs - startTimeMs) / 1000.0
            wpmHistory.add(Pair(elapsed, wpm))
            lastCalcMs = nowMs
        }

        val state = when {
            wpm >= AudioConfig.SPEED_FAST_WPM -> SpeedState.FAST
            wpm in 1 until AudioConfig.SPEED_SLOW_WPM -> SpeedState.SLOW
            else -> SpeedState.NORMAL
        }

        return SpeedResult(wpm, state, WINDOW_SEC)
    }

    private fun getLastResult(nowMs: Long): SpeedResult {
        val wpm = if (wpmHistory.isNotEmpty()) wpmHistory.last().second else 0
        val state = when {
            wpm >= AudioConfig.SPEED_FAST_WPM -> SpeedState.FAST
            wpm in 1 until AudioConfig.SPEED_SLOW_WPM -> SpeedState.SLOW
            else -> SpeedState.NORMAL
        }
        return SpeedResult(wpm, state, WINDOW_SEC)
    }

    fun getWpmHistory(): List<Pair<Double, Int>> = wpmHistory.toList()
}
