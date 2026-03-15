package com.speechcoach.audio

import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.FileOutputStream

/**
 * AudioBroadcaster
 *
 * AudioRecord로 마이크 입력을 읽어서
 * ┌─ STT 스레드  (VOSK)
 * └─ 오디오 분석 스레드 (TarsosDSP / TFLite)
 * 두 곳으로 동시에 브로드캐스팅(SharedFlow)하는 핵심 허브.
 *
 * 동시에 임시 PCM 파일로도 저장 → 발표 종료 후 WAV 변환에 사용
 */
class AudioBroadcaster(
    private val pcmOutputPath: String,        // 임시 PCM 녹음 파일 경로
    private val scope: CoroutineScope         // 외부에서 주입받는 코루틴 스코프
) {

    companion object {
        private const val TAG = "AudioBroadcaster"
    }

    // ── SharedFlow: 여러 구독자가 동시에 동일 청크를 수신 ─────────
    // replay=0: 과거 데이터 재전송 없음 (실시간 only)
    // extraBufferCapacity=8: 구독자가 느려도 최대 8청크 버퍼
    private val _audioChunkFlow = MutableSharedFlow<ShortArray>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val audioChunkFlow: SharedFlow<ShortArray> = _audioChunkFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var pcmOutputStream: FileOutputStream? = null
    private var isRecording = false

    // ── 녹음 시작 ────────────────────────────────────────────────
    fun start() {
        if (isRecording) return

        audioRecord = AudioRecord(
            AudioConfig.AUDIO_SOURCE,
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT,
            AudioConfig.MIN_BUFFER_SIZE
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 초기화 실패 - 마이크 권한 확인 필요")
                return
            }
        }

        pcmOutputStream = FileOutputStream(pcmOutputPath)
        isRecording = true
        audioRecord!!.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(AudioConfig.CHUNK_SIZE / 2)  // Short = 2 bytes

            Log.d(TAG, "녹음 시작: 청크 크기=${buffer.size} samples, " +
                    "청크 주기=${AudioConfig.CHUNK_DURATION_MS}ms")

            while (isActive && isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (readCount > 0) {
                    val chunk = buffer.copyOf(readCount)

                    // 1. SharedFlow로 브로드캐스팅 (STT + 오디오 분석 동시 수신)
                    _audioChunkFlow.tryEmit(chunk)

                    // 2. PCM 파일 동시 저장 (발표 종료 후 WAV 변환용)
                    val byteBuffer = shortArrayToByteArray(chunk)
                    pcmOutputStream?.write(byteBuffer)
                }
            }
        }
    }

    // ── 녹음 중지 ────────────────────────────────────────────────
    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        pcmOutputStream?.flush()
        pcmOutputStream?.close()
        pcmOutputStream = null
        Log.d(TAG, "녹음 중지 및 PCM 파일 저장 완료")
    }

    // ── Short 배열 → Byte 배열 변환 (Little-Endian, PCM16) ────────
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2]     = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    val isActive: Boolean get() = isRecording
}
