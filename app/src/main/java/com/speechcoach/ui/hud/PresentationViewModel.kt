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
import com.speechcoach.stt.SpeedAnalyzer
import com.speechcoach.stt.VoskSTTEngine
import com.speechcoach.stt.VoskWord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresentationViewModel : ViewModel() {

    private val _hudState    = MutableStateFlow<HudState>(HudState.Idle)
    val hudState: StateFlow<HudState> = _hudState.asStateFlow()

    private val _reportState = MutableStateFlow<ReportState>(ReportState.Idle)
    val reportState: StateFlow<ReportState> = _reportState.asStateFlow()

    private var broadcaster:     AudioBroadcaster?    = null
    private var voskEngine:      VoskSTTEngine?       = null
    private var tarsosAnalyzer:  TarsosAudioAnalyzer? = null
    private var voiceModel:      VoiceAnalysisModel?  = null
    private var speedAnalyzer:   SpeedAnalyzer        = SpeedAnalyzer()
    private var calibManager:    CalibrationManager?  = null

    private val allWords          = mutableListOf<VoskWord>()
    private val voiceResultBuffer = mutableListOf<VoiceAnalysisResult>()
    private var presentationStartMs = 0L
    private var pcmFilePath       = ""
    private var wavFilePath       = ""

    fun initialize(context: Context, filesDir: String) {
        calibManager = CalibrationManager(context)
        voiceModel   = VoiceAnalysisModel(context).also { it.load() }
        pcmFilePath  = "$filesDir/${com.speechcoach.audio.AudioConfig.TEMP_PCM_FILENAME}"
        wavFilePath  = "$filesDir/${com.speechcoach.audio.AudioConfig.RECORDING_FILENAME}"
    }

    // ═══════════════════════════════════════════════════════════
    // TRACK 1: 발표 시작
    // ═══════════════════════════════════════════════════════════
    fun startPresentation(context: Context, broadcaster: AudioBroadcaster) {
        this.broadcaster = broadcaster
        presentationStartMs = System.currentTimeMillis()
        allWords.clear()
        voiceResultBuffer.clear()
        speedAnalyzer.reset()

        _hudState.value = HudState.Recording(wpm = 0, speedStateLabel = "정상")

        voskEngine = VoskSTTEngine(context, viewModelScope).also { vosk ->
            vosk.initialize(
                onReady = { vosk.startListening(broadcaster.audioChunkFlow) },
                onError = { err -> _hudState.value = HudState.Error("STT 초기화 실패: $err") }
            )
            vosk.onWordResult = { words ->
                allWords.addAll(words)
                val speedResult = speedAnalyzer.onNewWords(words)
                val label = when (speedResult.state) {
                    SpeedAnalyzer.SpeedState.FAST   -> "빠름 ⚡"
                    SpeedAnalyzer.SpeedState.SLOW   -> "느림 🐢"
                    SpeedAnalyzer.SpeedState.NORMAL -> "정상 ✅"
                }
                _hudState.value = HudState.Recording(
                    wpm             = speedResult.wpm,
                    speedStateLabel = label,
                    speedState      = speedResult.state
                )
            }
        }

        tarsosAnalyzer = TarsosAudioAnalyzer(viewModelScope).also { tarsos ->
            tarsos.start(broadcaster.audioChunkFlow)
            tarsos.onFrameAnalyzed = { rms, pitch -> calibManager?.addFrame(rms, pitch) }
            tarsos.onWindowReady = { rmsArr, pitchArr ->
                viewModelScope.launch(Dispatchers.Default) {
                    val result = voiceModel?.analyze(rmsArr, pitchArr) ?: VoiceAnalysisResult.empty()
                    voiceResultBuffer.add(result)
                    val currentHud = _hudState.value
                    if (currentHud is HudState.Recording) {
                        withContext(Dispatchers.Main) {
                            _hudState.value = currentHud.copy(
                                confidencePercent = result.confidencePercent,
                                tremorPercent     = result.tremorPercent
                            )
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TRACK 2: 발표 종료
    //
    // [크래시 수정] 기존 코드의 잘못된 순서:
    //   getFinalTranscript() 호출 → (STT 코루틴 아직 살아있음) → stop()
    //   → Recognizer를 두 스레드에서 동시 접근 → 메모리 손상 → SIGABRT
    //
    // [올바른 순서]:
    //   1. stop()으로 STT 코루틴 완전 종료 (cancel + join)
    //   2. 코루틴이 완전히 멈춘 후 finalResult 안전하게 접근
    // ═══════════════════════════════════════════════════════════
    fun stopPresentation() {
        _hudState.value = HudState.Analyzing

        val wpmHistory  = speedAnalyzer.getWpmHistory()
        val volHistory  = tarsosAnalyzer?.getVolumeHistory() ?: emptyList()
        val durationSec = ((System.currentTimeMillis() - presentationStartMs) / 1000).toInt()

        // TarsosDSP 먼저 중지 (Recognizer와 무관하므로 즉시 중지 가능)
        tarsosAnalyzer?.stop()
        tarsosAnalyzer = null

        val capturedVosk = voskEngine
        val capturedWords = allWords.toList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── Step 1: STT 코루틴을 완전히 종료한 뒤 finalResult 접근 ──
                // stopAndGetTranscript()는 sttJob.cancelAndJoin() 후
                // 단일 스레드(IO)에서 안전하게 finalResult를 읽는다.
                val fullText = capturedVosk?.stopAndGetTranscript() ?: ""

                // ── Step 2: PCM → WAV 변환 ────────────────────────────────
                WavConverter.convert(pcmFilePath, wavFilePath)

                // ── Step 3: 습관어 분석 ────────────────────────────────────
                val fillerResult = FillerWordAnalyzer().analyze(fullText, capturedWords)

                // ── Step 4: 리포트 생성 ────────────────────────────────────
                val report = ReportBuilder.build(
                    durationSec         = durationSec,
                    wpmHistory          = wpmHistory,
                    fillerResult        = fillerResult,
                    voiceResults        = voiceResultBuffer.toList(),
                    volumeHistory       = volHistory,
                    presentationStartMs = presentationStartMs
                )

                withContext(Dispatchers.Main) {
                    _reportState.value = ReportState.Ready(report)
                    _hudState.value    = HudState.Idle
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _reportState.value = ReportState.Error("리포트 생성 실패: ${e.message}")
                    _hudState.value    = HudState.Idle
                }
            } finally {
                voskEngine = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voskEngine?.stop()
        tarsosAnalyzer?.stop()
        voiceModel?.close()
    }
}

// ── HUD 상태 ─────────────────────────────────────────────────────
sealed class HudState {
    object Idle      : HudState()
    object Analyzing : HudState()
    data class Recording(
        val wpm:               Int    = 0,
        val speedStateLabel:   String = "정상",
        val speedState:        SpeedAnalyzer.SpeedState = SpeedAnalyzer.SpeedState.NORMAL,
        val confidencePercent: Int    = 0,
        val tremorPercent:     Int    = 0
    ) : HudState()
    data class Error(val message: String) : HudState()
}

sealed class ReportState {
    object Idle : ReportState()
    data class Ready(val report: PresentationReport) : ReportState()
    data class Error(val message: String) : ReportState()
}
