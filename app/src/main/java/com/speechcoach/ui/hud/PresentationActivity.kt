package com.speechcoach.ui.hud

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.speechcoach.audio.AudioConfig
import com.speechcoach.audio.RecordingService
import com.speechcoach.databinding.ActivityPresentationBinding
import com.speechcoach.ui.report.ReportActivity
import kotlinx.coroutines.launch

class PresentationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPresentationBinding
    private val viewModel: PresentationViewModel by viewModels()

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
            serviceBound = true
            startRecordingAndPresentation()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            recordingService = null
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> checkMicPermission() }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindAndStartService()
        else binding.tvStatus.text = "마이크 권한이 필요합니다"
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
        binding.btnStop.setOnClickListener { stopPresentation() }
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
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
                binding.btnStop.isEnabled = false
                binding.tvStatus.text = "발표 준비 완료"
                binding.tvWpm.visibility = View.INVISIBLE
            }
            is HudState.Recording -> {
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
                binding.tvWpm.visibility = View.INVISIBLE
                binding.tvStatus.text = "🎙 발표 중..."
                binding.tvConfidence.text = "자신감 ${state.confidencePercent}%"
                binding.tvTremor.text = "떨림 ${state.tremorPercent}%"
                binding.borderView.setBackgroundColor(
                    android.graphics.Color.parseColor("#4CAF50"))
            }
            is HudState.Analyzing -> {
                binding.tvStatus.text = "분석 중..."
                binding.btnStop.isEnabled = false
            }
            is HudState.Transcribing -> {
                // 발표 종료 후 STT 처리 중 - 진행 상황 표시
                binding.tvStatus.text = state.message
                binding.btnStop.isEnabled = false
                binding.btnStart.isEnabled = false
            }
            is HudState.Error -> {
                binding.tvStatus.text = "오류: ${state.message}"
                binding.btnStart.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
