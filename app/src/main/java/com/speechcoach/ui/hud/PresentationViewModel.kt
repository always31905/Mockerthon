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
import com.speechcoach.stt.SpeechWord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
            // 기준 데이터 수집 유지
            tarsos.onFrameAnalyzed = { rms, pitch -> calibManager?.addFrame(rms, pitch) }

            tarsos.onWindowReady = { rmsArr, pitchArr ->
                viewModelScope.launch(Dispatchers.Default) {
                    // 1. 기본 AI 모델 분석 결과 (자신감 등 추출용)
                    val rawResult = voiceModel?.analyze(rmsArr, pitchArr) ?: VoiceAnalysisResult.empty()

                    // 2. [추가] CalibrationManager를 통한 떨림 정규화 (개인화 적용)
                    // calibManager의 calcTremorIntensity는 (현재 표준편차 / 기준 표준편차)를 반환함
                    val tremorIntensity = calibManager?.calcTremorIntensity(pitchArr) ?: 1.0f

                    // 정규화된 강도를 %로 변환 (기준 대비 2배 떨리면 100%에 가깝게 계산)
                    // 0.0 ~ 2.0 범위를 0 ~ 100%로 매핑
                    val normalizedTremorPercent = (tremorIntensity * 50).coerceIn(0f, 100f).toInt()

                    // 보정된 결과를 버퍼에 저장 (나중에 리포트 생성 시 사용)
                    val calibratedResult = rawResult.copy(tremorPercent = normalizedTremorPercent)
                    voiceResultBuffer.add(calibratedResult)

                    val current = _hudState.value
                    if (current is HudState.Recording) {
                        withContext(Dispatchers.Main) {
                            _hudState.value = current.copy(
                                confidencePercent = calibratedResult.confidencePercent,
                                tremorPercent = calibratedResult.tremorPercent // 보정된 값 반영
                            )
                        }
                    }
                }
            }
        }
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
                        onProgress = { msg -> _hudState.value = HudState.Transcribing(msg) },
                        onResult = { fullText, words ->
                            val calculatedWpmHistory = calculateWpmFromWords(words, durationSec)
                            buildReport(fullText, words, calculatedWpmHistory, volHistory, durationSec)
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

    /**
     * 개별 단어 리스트를 바탕으로 1초 단위 WPM 히스토리 생성
     * 초반 600이 나오는 문제를 방지하기 위해 로직 수정됨
     */
    private fun calculateWpmFromWords(words: List<SpeechWord>, totalDuration: Int): List<Pair<Double, Int>> {
        if (words.isEmpty()) return emptyList()
        val history = mutableListOf<Pair<Double, Int>>()
        val windowSize = 3

        for (sec in 0..totalDuration) {
            val windowStart = (sec - windowSize).toDouble().coerceAtLeast(0.0)
            val windowEnd = sec.toDouble()

            val wordsInWindow = words.count { it.start in windowStart..windowEnd }

            val wpm = when {
                sec < 2 -> {
                    // 시작 후 2초까지는 0으로 처리하여 튀는 현상(600 WPM 등) 방지
                    150
                }
                sec < windowSize -> {
                    // 2~3초 구간은 현재 초만큼만 나눔
                    val actualTime = sec.toDouble()
                    (wordsInWindow / actualTime * 60).toInt()
                }
                else -> {
                    // 정상 구간 (윈도우 크기 3초 사용)
                    (wordsInWindow / windowSize.toDouble() * 60).toInt()
                }
            }

            // 물리적 한계치 400으로 캡핑
            val sanitizedWpm = if (wpm > 400) 400 else wpm
            history.add(Pair(sec.toDouble(), sanitizedWpm))
        }
        return history
    }

    private fun buildReport(
        fullText: String,
        allWords: List<SpeechWord>,
        wpmHistory: List<Pair<Double, Int>>,
        volHistory: List<Pair<Long, Float>>,
        durationSec: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fillerResult = FillerWordAnalyzer().analyze(fullText, allWords)

                val report = ReportBuilder.build(
                    durationSec = durationSec,
                    fullTranscript = fullText,
                    allWords = allWords,
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
                    _reportState.value = ReportState.Error("리포트 생성 실패")
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