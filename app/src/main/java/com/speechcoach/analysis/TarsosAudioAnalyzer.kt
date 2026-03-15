package com.speechcoach.analysis

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import com.speechcoach.audio.AudioConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * TarsosAudioAnalyzer — TarsosDSP 2.5 실제 API 기준
 *
 * 수정 내역:
 * 1. RMSMeasure import 제거 → RMS 수식으로 직접 계산
 *    (be.tarsos.dsp.rms.RMSMeasure 는 2.5 JVM 아티팩트에 존재하지 않음)
 * 2. PitchDetector 내부 클래스(비공개 API) 사용 제거
 *    → PitchProcessor + PitchDetectionHandler 콜백 방식으로 교체
 *    (TarsosDSP 2.5의 공개 표준 패턴)
 * 3. 미사용 import(UniversalAudioInputStream, AudioProcessor, ByteBuffer 등) 제거
 */
class TarsosAudioAnalyzer(
    private val scope: CoroutineScope
) {

    // ── TarsosDSP 오디오 포맷 ────────────────────────────────────
    private val audioFormat = TarsosDSPAudioFormat(
        AudioConfig.SAMPLE_RATE.toFloat(),
        16,     // bits per sample
        1,      // mono
        true,   // signed
        false   // little-endian
    )

    // ── 슬라이딩 윈도우 버퍼 ─────────────────────────────────────
    private val rmsBuffer   = mutableListOf<Float>()
    private val pitchBuffer = mutableListOf<Float>()

    // ── 콜백 ─────────────────────────────────────────────────────
    var onFrameAnalyzed: ((rmsDb: Float, pitchHz: Float) -> Unit)? = null
    var onWindowReady:   ((rmsArray: FloatArray, pitchArray: FloatArray) -> Unit)? = null

    // ── 전체 볼륨 히스토리 (리포트 그래프용) ──────────────────────
    private val volumeHistory = mutableListOf<Pair<Long, Float>>()

    private var analyzeJob: Job? = null

    // ── 분석 시작 ─────────────────────────────────────────────────
    fun start(audioChunkFlow: SharedFlow<ShortArray>) {
        analyzeJob = scope.launch(Dispatchers.IO) {
            audioChunkFlow.collect { chunk ->
                processChunk(chunk)
            }
        }
    }

    // ── 청크 처리 ────────────────────────────────────────────────
    private fun processChunk(chunk: ShortArray) {
        val floatSamples = FloatArray(chunk.size) { chunk[it] / 32768f }

        // 1. RMS → dB (직접 계산, TarsosDSP RMSMeasure 불필요)
        val rms = calculateRMS(floatSamples)
        val db  = if (rms > 0.0001f) (20f * log10(rms.toDouble()).toFloat()) else -90f

        // 2. YIN Pitch Detection (TarsosDSP 2.5 공개 API)
        val pitchHz = detectPitch(floatSamples, chunk.size)

        // 버퍼 누적
        rmsBuffer.add(db)
        pitchBuffer.add(pitchHz)
        volumeHistory.add(Pair(System.currentTimeMillis(), db))

        // 메인 스레드 콜백
        scope.launch(Dispatchers.Main) {
            onFrameAnalyzed?.invoke(db, pitchHz)
        }

        // AI 윈도우 체크
        val windowChunks = (AudioConfig.AI_WINDOW_SEC * 1000 / AudioConfig.CHUNK_DURATION_MS)
        if (rmsBuffer.size >= windowChunks) {
            val rmsWindow   = rmsBuffer.takeLast(windowChunks.toInt()).toFloatArray()
            val pitchWindow = pitchBuffer.takeLast(windowChunks.toInt()).toFloatArray()

            scope.launch(Dispatchers.Main) {
                onWindowReady?.invoke(rmsWindow, pitchWindow)
            }

            // 슬라이딩: 앞 절반 제거
            repeat(windowChunks.toInt() / 2) {
                if (rmsBuffer.isNotEmpty())   rmsBuffer.removeAt(0)
                if (pitchBuffer.isNotEmpty()) pitchBuffer.removeAt(0)
            }
        }
    }

    // ── RMS 직접 계산 ────────────────────────────────────────────
    private fun calculateRMS(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        val sumSq = samples.fold(0.0) { acc, v -> acc + v * v }
        return sqrt(sumSq / samples.size).toFloat()
    }

    // ── TarsosDSP 2.5 올바른 Pitch 추출 ─────────────────────────
    // PitchProcessor(알고리즘, 샘플레이트, 버퍼크기, 핸들러) 생성자 사용.
    // process(AudioEvent) 를 직접 호출하여 동기적으로 결과를 받는다.
    private fun detectPitch(floatSamples: FloatArray, bufferSize: Int): Float {
        var detectedPitch = -1f

        try {
            val handler = PitchDetectionHandler { result: PitchDetectionResult, _: AudioEvent ->
                if (result.isPitched) {
                    detectedPitch = result.pitch
                }
            }

            // 올바른 PitchProcessor 4-인자 생성자
            val pitchProcessor = PitchProcessor(
                PitchProcessor.PitchEstimationAlgorithm.YIN,
                AudioConfig.SAMPLE_RATE.toFloat(),
                bufferSize,
                handler
            )

            val audioEvent = AudioEvent(audioFormat)
            audioEvent.setFloatBuffer(floatSamples)
            pitchProcessor.process(audioEvent)

        } catch (e: Exception) {
            // 묵음 구간 또는 처리 불가 → -1 유지
        }

        return detectedPitch
    }

    fun stop() {
        analyzeJob?.cancel()
    }

    fun getVolumeHistory(): List<Pair<Long, Float>> = volumeHistory.toList()

    fun reset() {
        rmsBuffer.clear()
        pitchBuffer.clear()
        volumeHistory.clear()
    }
}
