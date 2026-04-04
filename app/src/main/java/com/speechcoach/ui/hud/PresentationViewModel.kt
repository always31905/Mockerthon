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
import com.speechcoach.model.PresentationReport
import com.speechcoach.model.ReportBuilder
import com.speechcoach.stt.FillerWordAnalyzer
import com.speechcoach.stt.PostSpeechProcessor
import com.speechcoach.stt.SpeedAnalyzer
import com.speechcoach.stt.VoskSTTEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresentationViewModel : ViewModel() {

    private val _hudState = MutableStateFlow<HudState>(HudState.Idle)
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _reportState = MutableStateFlow<ReportState>(ReportState.Idle)
    val reportState: StateFlow<ReportState> = _reportState.asStateFlow()

    // ── 컴포넌트 ─────────────────────────────────────────────────
    // 실시간: VOSK(WPM) + TarsosDSP(떨림)
    private var voskEngine: VoskSTTEngine? = null
    private var tarsosAnalyzer: TarsosAudioAnalyzer? = null
    private var voiceModel: VoiceAnalysisModel? = null
    private val speedAnalyzer = SpeedAnalyzer()
    private var calibManager: CalibrationManager? = null

    // 발표 종료 후: PostSpeechProcessor(스크립트+습관어)
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

    // ═══════════════════════════════════════════════════════════
    // TRACK 1: 발표 중 (실시간)
    // AudioRecord 단독 마이크 사용 → VOSK + TarsosDSP 동시 처리
    // ═══════════════════════════════════════════════════════════
    fun startPresentation(context: Context, broadcaster: AudioBroadcaster) {
        presentationStartMs = System.currentTimeMillis()
        voiceResultBuffer.clear()
        speedAnalyzer.reset()
        _hudState.value = HudState.Recording()

        // VOSK: PCM 청크 직접 수신 → WPM 계산 (마이크 충돌 없음)
        voskEngine = VoskSTTEngine(context, viewModelScope).also { vosk ->
            vosk.initialize(
                onReady = { vosk.startListening(broadcaster.audioChunkFlow) },
                onError = { err ->
                    // VOSK 실패해도 발표는 계속 (WPM만 0으로 표시)
                    android.util.Log.w("ViewModel", "VOSK 초기화 실패: $err")
                }
            )
            vosk.onSpeedUpdate = { text: String, nowMs: Long ->
                speedAnalyzer.onPartialText(text, nowMs)
            }
        }

        // TarsosDSP: PCM 청크 → 볼륨/Pitch → 떨림/자신감
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

    // ═══════════════════════════════════════════════════════════
    // TRACK 2: 발표 종료 후
    // 1. VOSK/TarsosDSP 중지
    // 2. PCM → WAV 변환
    // 3. PostSpeechProcessor: WAV → 정확한 스크립트 추출
    // 4. 습관어 분석 → 리포트 생성
    // ═══════════════════════════════════════════════════════════
    fun stopPresentation() {
        _hudState.value = HudState.Analyzing

        // TarsosDSP 즉시 중지
        tarsosAnalyzer?.stop()
        val volHistory = tarsosAnalyzer?.getVolumeHistory() ?: emptyList()
        tarsosAnalyzer = null

        val wpmHistory = speedAnalyzer.getWpmHistory()
        val durationSec = ((System.currentTimeMillis() - presentationStartMs) / 1000).toInt()
        val capturedVosk = voskEngine
        voskEngine = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. VOSK 중지
                capturedVosk?.stop()

                // 2. PCM → WAV 변환
                WavConverter.convert(pcmFilePath, wavFilePath)
                android.util.Log.d("ViewModel", "WAV 변환 완료: $wavFilePath")

                // 3. PostSpeechProcessor로 WAV 파일 STT 처리
                // 스크립트 추출이 완료되면 리포트 생성
                withContext(Dispatchers.Main) {
                    _hudState.value = HudState.Transcribing("발표 내용을 분석하는 중...")

                    postProcessor?.processWavFile(
                        wavPath = wavFilePath,
                        onProgress = { partial ->
                            _hudState.value = HudState.Transcribing("스크립트 추출 중: $partial")
                        },
                        onResult = { fullText ->
                            android.util.Log.d("ViewModel", "STT 완료: ${fullText.length}자")
                            buildReport(fullText, wpmHistory, volHistory, durationSec)
                        },
                        onError = { err ->
                            android.util.Log.e("ViewModel", "STT 오류: $err")
                            // STT 실패해도 빈 스크립트로 리포트는 생성
                            buildReport("", wpmHistory, volHistory, durationSec)
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
        wpmHistory: List<Pair<Double, Int>>,
        volHistory: List<Pair<Long, Float>>,
        durationSec: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 습관어 분석: 스크립트 텍스트 Regex 매칭
                val fillerResult = FillerWordAnalyzer().analyze(fullText, emptyList())

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
        viewModelScope.launch { voskEngine?.stop() }
        tarsosAnalyzer?.stop()
        voiceModel?.close()
    }
}

// ── HUD 상태 ─────────────────────────────────────────────────────
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
    data class Ready(val report: PresentationReport) : ReportState()
    data class Error(val message: String) : ReportState()
}
