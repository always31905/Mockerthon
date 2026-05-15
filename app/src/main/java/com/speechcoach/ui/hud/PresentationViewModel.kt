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
                    val result = voiceModel?.analyze(rmsArr, pitchArr) ?: VoiceAnalysisResult.empty()
                    voiceResultBuffer.add(result)
                    val current = _hudState.value
                    if (current is HudState.Recording) {
                        withContext(Dispatchers.Main) {
                            _hudState.value = current.copy(
                                confidencePercent = result.confidencePercent,
                                tremorPercent = result.tremorPercent
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
                            // words가 이제 문장이 아닌 '단어' 단위입니다.
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
     */
    private fun calculateWpmFromWords(words: List<SpeechWord>, totalDuration: Int): List<Pair<Double, Int>> {
        if (words.isEmpty()) return emptyList()
        val history = mutableListOf<Pair<Double, Int>>()
        val windowSize = 3 // 감도를 높이기 위해 윈도우를 3초로 단축

        for (sec in 0..totalDuration) {
            val windowStart = (sec - windowSize).toDouble().coerceAtLeast(0.0)
            val windowEnd = sec.toDouble()
            
            // 윈도우 내에 시작된 단어 개수를 직접 카운트
            val wordsInWindow = words.count { it.start in windowStart..windowEnd }
            
            // WPM 계산: (단어 수 / 측정 시간) * 60
            val actualWindowSec = if (sec < windowSize) (sec + 0.1) else windowSize.toDouble()
            val wpm = (wordsInWindow / actualWindowSec * 60).toInt()
            
            history.add(Pair(sec.toDouble(), wpm))
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
