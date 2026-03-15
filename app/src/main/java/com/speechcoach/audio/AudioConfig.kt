package com.speechcoach.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 전역 오디오 설정 상수
 *
 * 16kHz / MONO / 16-bit PCM 규격:
 * - VOSK STT가 요구하는 표준 입력 포맷
 * - TFLite 1D-CNN 모델 학습 기준과 동일
 * - TarsosDSP 실시간 처리에 최적화된 샘플링 레이트
 */
object AudioConfig {

    // ── 핵심 오디오 스펙 ──────────────────────────────────────────
    const val SAMPLE_RATE = 16000          // 16kHz (VOSK & TFLite 표준)
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    // ── 청크 단위 설정 ───────────────────────────────────────────
    // 0.2초 단위로 쪼개서 STT 스레드와 오디오 분석 스레드로 브로드캐스팅
    const val CHUNK_DURATION_MS = 200      // ms 단위
    val CHUNK_SIZE: Int
        get() = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000) * 2  // * 2: 16-bit = 2 bytes

    // ── 최소 버퍼 크기 ───────────────────────────────────────────
    val MIN_BUFFER_SIZE: Int
        get() = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(CHUNK_SIZE * 4)  // 최소 4청크 이상 버퍼 확보

    // ── 속도 분석 윈도우 (Track 1 실시간) ─────────────────────────
    const val SPEED_WINDOW_SEC = 7         // 7초 단위로 WPM 계산
    const val SPEED_FAST_WPM = 200         // 이 이상이면 "빠름" 경고 (한국어 기준 조정 권장)
    const val SPEED_SLOW_WPM = 80          // 이 이하이면 "느림" 경고

    // ── AI 분석 윈도우 (Track 2 TFLite) ──────────────────────────
    const val AI_WINDOW_SEC = 3            // 3초 슬라이딩 윈도우로 떨림/자신감 분석
    val AI_WINDOW_SAMPLES: Int
        get() = SAMPLE_RATE * AI_WINDOW_SEC

    // ── 녹음 파일 저장 ────────────────────────────────────────────
    const val RECORDING_FILENAME = "presentation_recording.wav"
    const val TEMP_PCM_FILENAME  = "temp_recording.pcm"
}
