package com.speechcoach.ui.hud

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.speechcoach.audio.AudioConfig
import com.speechcoach.audio.RecordingService
import com.speechcoach.databinding.ActivityPresentationBinding
import com.speechcoach.stt.SpeedAnalyzer
import com.speechcoach.ui.report.ReportActivity
import kotlinx.coroutines.launch

/**
 * PresentationActivity (Track 1 - 실시간 HUD)
 *
 * 권한 요청 순서 (Android 13+ 기준):
 *  Step 1. POST_NOTIFICATIONS → 포그라운드 서비스 알림 허용
 *  Step 2. RECORD_AUDIO       → 마이크 허용
 *  Step 3. 서비스 바인딩 → RecordingService 시작 → 녹음 시작
 *
 * [핵심 버그 원인]
 * Android 13(API 33)+에서 POST_NOTIFICATIONS가 런타임 권한으로 변경됨.
 * 이 권한 없이 startForegroundService()를 호출하면
 * 시스템이 알림을 차단(Suppressing notification)하고
 * 서비스가 5초 안에 startForeground()를 호출하지 못해 강제 종료됨.
 * → broadcaster = null → 녹음 미실행
 */
class PresentationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPresentationBinding
    private val viewModel: PresentationViewModel by viewModels()

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            serviceBound = true
            Log.d(TAG, "서비스 연결 완료 → 녹음 시작")
            startRecordingAndPresentation()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            recordingService = null
        }
    }

    private var blinkAnimator: ObjectAnimator? = null

    // ── Step 1: 알림 권한 (Android 13+) ─────────────────────────
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 허용/거부 무관하게 다음 단계 진행
        // (거부해도 일부 기기에서 동작하며, 허용 안 내도 마이크는 필요)
        checkMicPermission()
    }

    // ── Step 2: 마이크 권한 ───────────────────────────────────────
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindAndStartService()
        else binding.tvStatus.text = "⚠️ 마이크 권한이 필요합니다\n설정 > 앱 > 권한에서 마이크를 허용해주세요"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresentationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.initialize(this, filesDir.absolutePath)
        setupButtons()
        observeState()
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { checkNotificationPermission() }
        binding.btnStop.setOnClickListener  { stopPresentation() }
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
    }

    // ── 권한 체크 흐름 ────────────────────────────────────────────
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkMicPermission()
    }

    private fun checkMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        bindAndStartService()
    }

    private fun bindAndStartService() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startRecordingAndPresentation() {
        val pcmPath = "${filesDir.absolutePath}/${AudioConfig.TEMP_PCM_FILENAME}"
        recordingService?.startRecording(pcmPath)
        val broadcaster = recordingService?.broadcaster ?: run {
            Log.e(TAG, "broadcaster null")
            binding.tvStatus.text = "녹음 시작 실패. 다시 시도해주세요."
            return
        }
        viewModel.startPresentation(this, broadcaster)
    }

    private fun stopPresentation() {
        recordingService?.stopRecording()
        viewModel.stopPresentation()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.hudState.collect { updateHudUI(it) }
        }
        lifecycleScope.launch {
            viewModel.reportState.collect { state ->
                if (state is ReportState.Ready) {
                    startActivity(
                        Intent(this@PresentationActivity, ReportActivity::class.java)
                            .putExtra(ReportActivity.EXTRA_REPORT_JSON, state.report.toJson())
                    )
                }
            }
        }
    }

    private fun updateHudUI(state: HudState) {
        when (state) {
            is HudState.Idle -> {
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled  = false
                binding.tvStatus.text      = "발표 준비 완료"
                binding.tvWpm.text         = "- WPM"
                stopBlink(); setBorderColor(COLOR_NORMAL)
            }
            is HudState.Recording -> {
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled  = true
                binding.tvWpm.text         = "${state.wpm} WPM"
                binding.tvStatus.text      = state.speedStateLabel
                binding.tvConfidence.text  = "자신감 ${state.confidencePercent}%"
                binding.tvTremor.text      = "떨림 ${state.tremorPercent}%"
                when (state.speedState) {
                    SpeedAnalyzer.SpeedState.FAST   -> { setBorderColor(COLOR_FAST); startBlink() }
                    SpeedAnalyzer.SpeedState.SLOW   -> { setBorderColor(COLOR_SLOW); stopBlink() }
                    SpeedAnalyzer.SpeedState.NORMAL -> { setBorderColor(COLOR_NORMAL); stopBlink() }
                }
            }
            is HudState.Analyzing -> {
                binding.tvStatus.text     = "분석 중..."
                binding.btnStop.isEnabled = false
                stopBlink()
            }
            is HudState.Error -> {
                binding.tvStatus.text      = "오류: ${state.message}"
                binding.btnStart.isEnabled = true
                stopBlink()
            }
        }
    }

    private fun setBorderColor(hex: String) {
        binding.borderView.setBackgroundColor(android.graphics.Color.parseColor(hex))
    }

    private fun startBlink() {
        if (blinkAnimator?.isRunning == true) return
        blinkAnimator = ObjectAnimator.ofFloat(binding.borderView, "alpha", 1f, 0f).apply {
            duration = 500; repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE; interpolator = LinearInterpolator(); start()
        }
    }

    private fun stopBlink() {
        blinkAnimator?.cancel(); blinkAnimator = null
        binding.borderView.alpha = 1f
    }

    override fun onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        super.onDestroy()
    }

    companion object {
        private const val TAG          = "PresentationActivity"
        private const val COLOR_NORMAL = "#4CAF50"
        private const val COLOR_FAST   = "#FF9800"
        private const val COLOR_SLOW   = "#2196F3"
    }
}
