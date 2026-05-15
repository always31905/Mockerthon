package com.speechcoach.audio

import java.io.*

/**
 * WavConverter
 *
 * 녹음 중 저장된 RAW PCM 파일에 WAV 헤더를 붙여서
 * 표준 .wav 파일로 변환한다.
 *
 * WAV 헤더 구조 (44 bytes):
 * - RIFF 청크: "RIFF", 파일크기, "WAVE"
 * - fmt  청크: 포맷, 채널수, 샘플레이트, 바이트레이트, 블록얼라인, 비트수
 * - data 청크: "data", 데이터 크기
 */
object WavConverter {

    /**
     * PCM 파일 → WAV 파일 변환
     * @param pcmPath  입력 RAW PCM 파일 경로
     * @param wavPath  출력 WAV 파일 경로
     */
    fun convert(pcmPath: String, wavPath: String) {
        val pcmFile = File(pcmPath)
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            throw IOException("PCM 파일이 존재하지 않거나 비어 있습니다: $pcmPath")
        }

        val pcmData = pcmFile.readBytes()
        FileOutputStream(wavPath).use { out ->
            writeWavHeader(out, pcmData.size.toLong())
            out.write(pcmData)
        }
    }

    // ── WAV 헤더 44 bytes 작성 ───────────────────────────────────
    private fun writeWavHeader(out: OutputStream, pcmDataSize: Long) {
        val sampleRate    = AudioConfig.SAMPLE_RATE.toLong()
        val channels      = 1L
        val bitsPerSample = 16L
        val byteRate      = sampleRate * channels * bitsPerSample / 8
        val blockAlign    = channels * bitsPerSample / 8
        val totalDataLen  = pcmDataSize + 36  // 헤더 44 - "RIFF" 8 = 36

        out.write("RIFF".toByteArray())
        out.writeInt32LE(totalDataLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeInt32LE(16)              // fmt 청크 크기 (PCM = 16)
        out.writeInt16LE(1)               // AudioFormat: PCM = 1
        out.writeInt16LE(channels)
        out.writeInt32LE(sampleRate)
        out.writeInt32LE(byteRate)
        out.writeInt16LE(blockAlign)
        out.writeInt16LE(bitsPerSample)
        out.write("data".toByteArray())
        out.writeInt32LE(pcmDataSize)
    }

    private fun OutputStream.writeInt16LE(value: Long) {
        write((value and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
    }

    private fun OutputStream.writeInt32LE(value: Long) {
        write((value and 0xFF).toInt())
        write((value shr 8 and 0xFF).toInt())
        write((value shr 16 and 0xFF).toInt())
        write((value shr 24 and 0xFF).toInt())
    }
}
