package com.speechcoach.ui.hud

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speechcoach.analysis.CalibrationManager
import com.speechcoach.analysis.TarsosAudioAnalyzer
import com.speechcoach.analysis.VoiceAnalysisModel
import com.speechcoach.analysis.VoiceAnalysisResult
import com.speechcoach.audio.AudioBroadcaster
import com.speechcoach.audio.WavConverter
import com.speechcoach.model.ReportBuilder
import com.speechcoach.stt.FillerWordAnalyzer
import com.speechcoach.stt.PostSpeechProcessor
import com.speechcoach.stt.SpeechSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresentationViewModel : ViewModel() {

    private val _hudState = MutableStateFlow<HudState>(HudState.Idle)
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _reportState = MutableStateFlow<ReportState>(ReportState.Idle)
    val reportState: StateFlow<ReportState> = _reportState.asStateFlow()

    private var tarsosAnalyzer: TarsosAudioAnalyzer? = null
    private var voiceModel: VoiceAnalysisModel? = null
    private var calibManager: CalibrationManager? = null
    private var postProcessor: PostSpeechProcessor? = null

    private val voiceResultBuffer = mutableListOf<VoiceAnalysisResult>()
    private var presentationStartMs = 0L
    private var wavFilePath = ""
    private var pcmFilePath = ""

    fun initialize(context: Context, filesDir: String) {
        calibManager = CalibrationManager(context)
        voiceModel = VoiceAnalysisModel(context).also { it.load() }
        postProcessor = PostSpeechProcessor(context)
        pcmFilePath = "$filesDir/${com.speechcoach.audio.AudioConfig.TEMP_PCM_FILENAME}"
        wavFilePath = "$filesDir/${com.speechcoach.audio.AudioConfig.RECORDING_FILENAME}"
    }

    fun startPresentation(context: Context, broadcaster: AudioBroadcaster) {
        presentationStartMs = System.currentTimeMillis()
        voiceResultBuffer.clear()
        _hudState.value = HudState.Recording()

        tarsosAnalyzer = TarsosAudioAnalyzer(viewModelScope).also { tarsos ->
            tarsos.start(broadcaster.audioChunkFlow)
            tarsos.onFrameAnalyzed = { rms, pitch -> calibManager?.addFrame(rms, pitch) }
            tarsos.onWindowReady = { rmsArr, pitchArr ->
                viewModelScope.launch(Dispatchers.Default) {
                    // 1. TFLite 모델의 기초 분석 수행
                    val rawResult = voiceModel?.analyze(rmsArr, pitchArr) ?: VoiceAnalysisResult.empty()

                    // 2. 정규화(Normalization) 적용: 기준 데이터 대비 편차 계산
                    val normalizedResult = applyNormalization(rawResult, rmsArr, pitchArr)

                    voiceResultBuffer.add(normalizedResult)

                    val current = _hudState.value
                    if (current is HudState.Recording) {
                        withContext(Dispatchers.Main) {
                            _hudState.value = current.copy(
                                confidencePercent = normalizedResult.confidencePercent,
                                tremorPercent = normalizedResult.tremorPercent
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 💡 핵심 로직: 평상시 목소리와 비교하여 점수 보정
     */
    private fun applyNormalization(
        raw: VoiceAnalysisResult,
        rmsArr: FloatArray,
        pitchArr: FloatArray
    ): VoiceAnalysisResult {
        val calib = calibManager ?: return raw

        // [자신감 보정] 평소 볼륨(baselineRmsDb) 대비 현재 볼륨 차이 측정
        val currentAvgRms = rmsArr.filter { it > -60f }.average().toFloat()
        val volDiff = currentAvgRms - calib.baselineRmsDb

        // 평소보다 목소리가 5dB 이상 작아지면 자신감 점수 하락, 크면 상승 (최대 100)
        val confidenceBoost = (volDiff * 5).toInt()
        val finalConfidence = (raw.confidencePercent + confidenceBoost).coerceIn(0, 100)

        // [떨림 보정] 평소 음높이 표준편차 대비 현재 흔들림 측정
        val tremorIntensity = calib.calcTremorIntensity(pitchArr)

        // 강도 1.0(평소 수준)을 기준으로 0~100%로 변환 (2.0배 넘어가면 심한 떨림)
        val finalTremor = (tremorIntensity * 40).toInt().coerceIn(0, 100)

        // 💡💡💡 바로 이곳입니다! (에러 원인 해결)
        // raw 객체가 가지고 있던 isReliable 값을 그대로 넘겨주도록 수정했습니다.
        return VoiceAnalysisResult(
            confidencePercent = finalConfidence,
            tremorPercent = finalTremor,
            isReliable = raw.isReliable
        )
    }

    private fun generateWpmHistory(segments: List<SpeechSegment>, durationSec: Int): List<Pair<Double, Int>> {
        val history = mutableListOf<Pair<Double, Int>>()
        val windowSec = 7.0 // 7초 윈도우 기준

        for (t in 1..durationSec) {
            val windowStart = maxOf(0.0, t - windowSec)
            val windowEnd = t.toDouble()

            var wordCount = 0
            for (seg in segments) {
                if (seg.start < windowEnd && seg.end > windowStart) {
                    val count = seg.text.trim().split("\\s+".toRegex()).size
                    wordCount += count
                }
            }

            val actualWindowSize = minOf(windowSec, t.toDouble())
            val wpm = if (actualWindowSize > 0) (wordCount / actualWindowSize * 60).toInt() else 0
            history.add(Pair(t.toDouble(), wpm))
        }
        return history
    }

    fun stopPresentation() {
        _hudState.value = HudState.Analyzing

        tarsosAnalyzer?.stop()
        val volHistory = tarsosAnalyzer?.getVolumeHistory() ?: emptyList()
        tarsosAnalyzer = null

        val durationSec = ((System.currentTimeMillis() - presentationStartMs) / 1000).toInt()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                WavConverter.convert(pcmFilePath, wavFilePath)

                withContext(Dispatchers.Main) {
                    _hudState.value = HudState.Transcribing("발표 내용을 분석하는 중...")

                    postProcessor?.processWavFile(
                        wavPath = wavFilePath,
                        onProgress = { partial ->
                            _hudState.value = HudState.Transcribing(partial)
                        },
                        onResult = { fullText, segments ->
                            val wpmHistory = generateWpmHistory(segments, durationSec)
                            buildReport(fullText, segments, wpmHistory, volHistory, durationSec)
                        },
                        onError = { err ->
                            buildReport("", emptyList(), emptyList(), volHistory, durationSec)
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _reportState.value = ReportState.Error("처리 실패: ${e.message}")
                    _hudState.value = HudState.Idle
                }
            }
        }
    }

    private fun buildReport(
        fullText: String,
        segments: List<SpeechSegment>,
        wpmHistory: List<Pair<Double, Int>>,
        volHistory: List<Pair<Long, Float>>,
        durationSec: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fillerResult = FillerWordAnalyzer().analyze(fullText, segments)

                // 💡💡💡 이전 답변에서 제가 잘못 추가했던 isReliable 파라미터는 제거했습니다.
                val report = ReportBuilder.build(
                    durationSec = durationSec,
                    fullTranscript = fullText,
                    wpmHistory = wpmHistory,
                    fillerResult = fillerResult,
                    voiceResults = voiceResultBuffer.toList(),
                    volumeHistory = volHistory,
                    presentationStartMs = presentationStartMs
                )

                withContext(Dispatchers.Main) {
                    _reportState.value = ReportState.Ready(report)
                    _hudState.value = HudState.Idle
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _reportState.value = ReportState.Error("리포트 생성 실패: ${e.message}")
                    _hudState.value = HudState.Idle
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tarsosAnalyzer?.stop()
        voiceModel?.close()
        postProcessor?.release()
    }
}

sealed class HudState {
    object Idle : HudState()
    object Analyzing : HudState()
    data class Transcribing(val message: String) : HudState()
    data class Recording(
        val confidencePercent: Int = 0,
        val tremorPercent: Int = 0
    ) : HudState()
    data class Error(val message: String) : HudState()
}

sealed class ReportState {
    object Idle : ReportState()
    data class Ready(val report: com.speechcoach.model.PresentationReport) : ReportState()
    data class Error(val message: String) : ReportState()
}