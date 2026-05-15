package com.speechcoach.stt

import android.util.Log
import org.jtransforms.fft.FloatFFT_1D

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

object AudioProcessor {

    private const val TAG = "AudioProcessor"

    // Hann Window 캐싱
    private val window: FloatArray by lazy {
        FloatArray(WhisperConfig.N_FFT) { i ->
            (0.5 - 0.5 * cos((2.0 * PI * i) / WhisperConfig.N_FFT)).toFloat()
        }
    }

    // FFT 객체 재사용
    private val fft = FloatFFT_1D(WhisperConfig.N_FFT.toLong())

    fun logMelSpectrogram(
        audio: FloatArray,
        filters: Array<FloatArray>
    ): Array<FloatArray> {

        val nFft = WhisperConfig.N_FFT
        val hop = WhisperConfig.HOP_LENGTH
        val nMels = WhisperConfig.N_MELS
        val fftBins = nFft / 2 + 1

        // center=True padding
        val padLen = nFft / 2

        val paddedAudio = FloatArray(audio.size + padLen * 2)

        System.arraycopy(
            audio,
            0,
            paddedAudio,
            padLen,
            audio.size
        )

        val numFrames =
            1 + (paddedAudio.size - nFft) / hop

        Log.d(TAG, "numFrames=$numFrames")

        // [frame][freq]
        val magnitudes =
            Array(numFrames) {
                FloatArray(fftBins)
            }

        // JTransforms realForward는
        // 길이 N 배열 하나만 사용
        val frame = FloatArray(nFft)

        // =========================
        // STFT
        // =========================

        var i = 0

        while (i < numFrames) {

            val offset = i * hop

            // window 적용
            var j = 0
            while (j < nFft) {
                frame[j] =
                    paddedAudio[offset + j] * window[j]
                j++
            }

            // FFT
            fft.realForward(frame)

            // magnitude 계산
            // JTransforms packed format 처리

            // DC
            magnitudes[i][0] =
                frame[0] * frame[0]

            // Nyquist
            magnitudes[i][fftBins - 1] =
                frame[1] * frame[1]

            j = 1

            while (j < fftBins - 1) {

                val real = frame[2 * j]
                val imag = frame[2 * j + 1]

                magnitudes[i][j] =
                    real * real + imag * imag

                j++
            }

            i++
        }

        Log.d(TAG, "STFT done")

        // =========================
        // Mel Filter
        // =========================

        val melSpec =
            Array(nMels) {
                FloatArray(numFrames)
            }

        i = 0

        while (i < numFrames) {

            var m = 0

            while (m < nMels) {

                val filter = filters[m]

                var sum = 0f

                var k = 0

                while (k < fftBins) {
                    sum += filter[k] * magnitudes[i][k]
                    k++
                }

                melSpec[m][i] = sum

                m++
            }

            i++
        }

        Log.d(TAG, "Mel filter done")

        // =========================
        // Log scaling
        // =========================

        var maxLog = -Float.MAX_VALUE

        var m = 0

        while (m < nMels) {

            i = 0

            while (i < numFrames) {

                var v = melSpec[m][i]

                if (v < 1e-10f) {
                    v = 1e-10f
                }

                val logV = log10(v)

                melSpec[m][i] = logV

                if (logV > maxLog) {
                    maxLog = logV
                }

                i++
            }

            m++
        }

        Log.d(TAG, "Log scaling done")

        // =========================
        // Whisper normalization
        // =========================

        m = 0

        while (m < nMels) {

            i = 0

            while (i < numFrames) {

                var logV = melSpec[m][i]

                logV = max(
                    logV,
                    maxLog - 8.0f
                )

                melSpec[m][i] =
                    (logV + 4.0f) / 4.0f

                i++
            }

            m++
        }

        Log.d(TAG, "Normalization done")

        return melSpec
    }
}