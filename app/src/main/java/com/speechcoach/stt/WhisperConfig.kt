package com.speechcoach.stt

object WhisperConfig {
    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val HOP_LENGTH = 160
    const val N_MELS = 80
    const val CHUNK_LENGTH = 30 // 30초
    const val N_SAMPLES = CHUNK_LENGTH * SAMPLE_RATE
    const val N_FRAMES = N_SAMPLES / HOP_LENGTH // 3000

    const val SOT = 50258
    const val KO_TOKEN = 50264
    const val TRANSCRIBE = 50359
    const val EOT = 50257
    const val TIMESTAMP_BEGIN = 50364
}