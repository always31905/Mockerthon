package com.speechcoach.audio

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.speechcoach.R
import com.speechcoach.ui.hud.PresentationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * RecordingService (포그라운드 서비스)
 *
 * 화면이 꺼지거나 앱이 백그라운드로 가도
 * 마이크 녹음이 유지되도록 보장하는 서비스.
 *
 * AudioBroadcaster 인스턴스를 보유하며,
 * Activity에서 Binder를 통해 직접 접근한다.
 */
class RecordingService : Service() {

    companion object {
        private const val NOTIFICATION_ID   = 1001
        private const val CHANNEL_ID        = "recording_channel"
        private const val CHANNEL_NAME      = "발표 녹음 중"
    }

    // ── Binder: Activity ↔ Service 직접 연결 ──────────────────────
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    private val binder = RecordingBinder()

    // ── 코루틴 스코프 (서비스 수명과 동일) ─────────────────────────
    val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── AudioBroadcaster (외부에서 접근 가능) ─────────────────────
    var broadcaster: AudioBroadcaster? = null
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        broadcaster?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── 녹음 시작 (Activity에서 호출) ────────────────────────────
    fun startRecording(pcmPath: String) {
        broadcaster = AudioBroadcaster(pcmPath, serviceScope).also { it.start() }
    }

    // ── 녹음 중지 ────────────────────────────────────────────────
    fun stopRecording() {
        broadcaster?.stop()
        broadcaster = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── 포그라운드 알림 생성 ──────────────────────────────────────
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PresentationActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpeechCoach 발표 분석 중")
            .setContentText("녹음이 진행 중입니다. 탭하여 앱으로 돌아가기")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "발표 녹음이 진행 중임을 알립니다"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
