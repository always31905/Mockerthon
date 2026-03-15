package com.speechcoach.stt

import com.speechcoach.audio.AudioConfig
import kotlinx.coroutines.*

/**
 * SpeedAnalyzer (Track 1 - 실시간 HUD용)
 *
 * VOSK에서 나오는 단어 스트림을 받아
 * 슬라이딩 윈도우(기본 7초) 기준으로 WPM(분당 단어 수)을 계산한다.
 *
 * 결과에 따라 3단계 속도 상태를 반환:
 * NORMAL  → 테두리 초록색 (유지)
 * FAST    → 테두리 주황색 깜빡임 (빠름 경고)
 * SLOW    → 테두리 파란색 (느림 안내)
 */
class SpeedAnalyzer {

    enum class SpeedState { NORMAL, FAST, SLOW }

    data class SpeedResult(
        val wpm:        Int,        // 현재 WPM
        val state:      SpeedState, // 속도 상태
        val windowSec:  Int         // 분석 윈도우 (초)
    )

    // ── 슬라이딩 윈도우 버퍼 ─────────────────────────────────────
    // Pair<단어, 타임스탬프(초)> 를 저장
    private val wordBuffer = ArrayDeque<Pair<String, Double>>()

    // ── 전체 발표 WPM 기록 (리포트 그래프용) ────────────────────
    // Pair<시각(초), WPM>
    private val wpmHistory = mutableListOf<Pair<Double, Int>>()

    private var lastCalcTime = 0.0

    /**
     * 새 단어 배열을 버퍼에 추가하고 WPM을 계산한다.
     *
     * @param words VOSK VoskWord 리스트
     * @return SpeedResult (현재 속도 상태)
     */
    fun onNewWords(words: List<VoskWord>): SpeedResult {
        val now = words.lastOrNull()?.end ?: return SpeedResult(0, SpeedState.NORMAL, AudioConfig.SPEED_WINDOW_SEC)

        // 버퍼에 추가
        words.forEach { wordBuffer.addLast(Pair(it.word, it.end)) }

        // 윈도우 바깥 단어 제거 (슬라이딩)
        val windowStart = now - AudioConfig.SPEED_WINDOW_SEC
        while (wordBuffer.isNotEmpty() && wordBuffer.first().second < windowStart) {
            wordBuffer.removeFirst()
        }

        // WPM 계산: (윈도우 내 단어 수 / 윈도우 초) × 60
        val windowWordCount = wordBuffer.size
        val wpm = if (AudioConfig.SPEED_WINDOW_SEC > 0) {
            (windowWordCount.toDouble() / AudioConfig.SPEED_WINDOW_SEC * 60).toInt()
        } else 0

        // 히스토리 저장 (1초 간격으로)
        if (now - lastCalcTime >= 1.0) {
            wpmHistory.add(Pair(now, wpm))
            lastCalcTime = now
        }

        val state = when {
            wpm >= AudioConfig.SPEED_FAST_WPM -> SpeedState.FAST
            wpm <= AudioConfig.SPEED_SLOW_WPM && wpm > 0 -> SpeedState.SLOW
            else -> SpeedState.NORMAL
        }

        return SpeedResult(wpm, state, AudioConfig.SPEED_WINDOW_SEC)
    }

    /**
     * 전체 발표 WPM 히스토리 반환 (발표 종료 후 그래프용)
     * @return List<Pair<시각(초), WPM>>
     */
    fun getWpmHistory(): List<Pair<Double, Int>> = wpmHistory.toList()

    fun reset() {
        wordBuffer.clear()
        wpmHistory.clear()
        lastCalcTime = 0.0
    }
}
