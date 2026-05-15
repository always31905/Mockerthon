package com.speechcoach.ui.hud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.speechcoach.analysis.CalibrationManager
import com.speechcoach.analysis.TarsosAudioAnalyzer
import com.speechcoach.audio.AudioBroadcaster
import com.speechcoach.audio.AudioConfig
import com.speechcoach.databinding.ActivityCalibrationBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * CalibrationActivity
 *
 * 사용자에게 30초짜리 글을 읽게 하여 기준 목소리를 저장.
 * - 30초 카운트다운 자동 종료
 * - 다 읽으면 [완료] 버튼으로 조기 종료 가능 (요청사항 반영)
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private val calibManager by lazy { CalibrationManager(this) }

    private var broadcaster: AudioBroadcaster? = null
    private var tarsosAnalyzer: TarsosAudioAnalyzer? = null
    private val calibScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var countDownTimer: CountDownTimer? = null
    private var isRecording = false

    private val calibText = """
        안녕하세요. 저는 오늘 발표를 통해 우리 팀의 프로젝트 진행 상황을 
        공유하고자 합니다. 지난 한 달 동안 팀원 모두가 열심히 노력해서 
        중요한 성과를 이루어냈습니다. 먼저 개발 파트에서는 핵심 기능 구현을 
        완료했으며, 다음 단계로 테스트와 품질 개선을 진행할 예정입니다. 
        감사합니다.
    """.trimIndent()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCalibration() else binding.tvGuide.text = "마이크 권한이 필요합니다"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvCalibText.text = calibText

        // 녹음 시작 버튼
        binding.btnStartCalib.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startCalibration()
            else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 조기 완료 버튼 (처음엔 숨김)
        binding.btnFinishEarly.setOnClickListener {
            finishCalibration()
        }
    }

    private fun startCalibration() {
        if (isRecording) return
        isRecording = true

        binding.btnStartCalib.isEnabled    = false
        binding.btnFinishEarly.visibility  = View.VISIBLE   // 완료 버튼 표시
        binding.tvGuide.text               = "위 글을 자연스럽게 읽어주세요..."
        binding.progressBar.visibility     = View.VISIBLE
        binding.progressBar.max            = 100

        val pcmPath = "${filesDir.absolutePath}/calib_temp.pcm"
        broadcaster = AudioBroadcaster(pcmPath, calibScope).also { it.start() }
        tarsosAnalyzer = TarsosAudioAnalyzer(calibScope).also { tarsos ->
            tarsos.start(broadcaster!!.audioChunkFlow)
            tarsos.onFrameAnalyzed = { rms, pitch -> calibManager.addFrame(rms, pitch) }
        }
        calibManager.startCalibration()

        // 30초 카운트다운
        countDownTimer = object : CountDownTimer(30_000, 1000) {
            override fun onTick(ms: Long) {
                val elapsed = 30 - (ms / 1000).toInt()
                binding.tvTimer.text       = "남은 시간: ${ms / 1000}초"
                binding.progressBar.progress = (elapsed * 100 / 30)
            }
            override fun onFinish() {
                finishCalibration()
            }
        }.start()
    }

    private fun finishCalibration() {
        if (!isRecording) return
        isRecording = false

        countDownTimer?.cancel()
        broadcaster?.stop()
        tarsosAnalyzer?.stop()

        binding.btnFinishEarly.visibility = View.GONE
        binding.progressBar.visibility    = View.GONE
        binding.tvTimer.text              = ""

        val result = calibManager.finishCalibration()
        if (result.success) {
            binding.tvGuide.text = "✅ 캘리브레이션 완료!\n" +
                    "평균 볼륨: ${String.format("%.1f", result.avgRmsDb)} dB\n" +
                    "평균 음높이: ${String.format("%.1f", result.avgPitchHz)} Hz\n\n" +
                    "이제 발표 화면으로 돌아가 발표를 시작하세요."
        } else {
            binding.tvGuide.text       = "❌ 데이터가 부족합니다. 최소 5초 이상 읽어주세요."
            binding.btnStartCalib.isEnabled = true
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        broadcaster?.stop()
        tarsosAnalyzer?.stop()
        super.onDestroy()
    }
}
