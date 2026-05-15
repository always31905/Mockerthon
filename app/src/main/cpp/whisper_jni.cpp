#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <thread>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static whisper_context* g_ctx = nullptr;

// UTF-8 유효성 검증 헬퍼
static std::string sanitize_utf8(const std::string& input) {
    std::string output;
    output.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        unsigned char c = (unsigned char)input[i];
        size_t char_len = 0;
        if (c < 0x80) char_len = 1;
        else if ((c & 0xE0) == 0xC0) char_len = 2;
        else if ((c & 0xF0) == 0xE0) char_len = 3;
        else if ((c & 0xF8) == 0xF0) char_len = 4;
        else { i++; continue; }
        if (i + char_len > input.size()) break;
        bool valid = true;
        for (size_t k = 1; k < char_len; k++) {
            if (((unsigned char)input[i + k] & 0xC0) != 0x80) { valid = false; break; }
        }
        if (valid) output.append(input, i, char_len);
        i += char_len;
    }
    return output;
}

extern "C" JNIEXPORT jboolean
Java_com_speechcoach_stt_WhisperEngine_loadModel(JNIEnv* env, jobject thiz, jstring modelPath) {
    if (modelPath == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;

    g_ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        LOGE("모델 로드 실패");
        return JNI_FALSE;
    }
    LOGI("모델 로드 완료");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring
Java_com_speechcoach_stt_WhisperEngine_transcribe(JNIEnv* env, jobject thiz, jfloatArray pcmData, jint numSamples) {
    if (g_ctx == nullptr) {
        LOGE("모델이 로드되지 않음");
        return env->NewStringUTF("");
    }

    jfloat* samples = env->GetFloatArrayElements(pcmData, nullptr);
    if (samples == nullptr) return env->NewStringUTF("");

    std::vector<float> pcm(samples, samples + numSamples);
    env->ReleaseFloatArrayElements(pcmData, samples, JNI_ABORT);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language         = "ko";
    wparams.translate        = false;
    wparams.no_context       = true;
    wparams.single_segment   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = true;
    wparams.token_timestamps = true;

    // CPU 쓰레드 설정: 하드웨어 코어 수를 확인하여 설정 (최대 4~6개 권장)
    int n_threads = std::thread::hardware_concurrency();
    wparams.n_threads = (n_threads > 0) ? (n_threads > 4 ? 4 : n_threads) : 2;
    LOGI("Transcription 시작 (사용 쓰레드: %d)", wparams.n_threads);

    if (whisper_full(g_ctx, wparams, pcm.data(), (int)pcm.size()) != 0) {
        LOGE("whisper_full 실행 실패");
        return env->NewStringUTF("");
    }

    std::string result_data;
    const int n_segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(g_ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(g_ctx, i);

        if (text && text[0] != '\0') {
            float start_sec = t0 / 100.0f;
            float end_sec   = t1 / 100.0f;
            std::string safe_text = sanitize_utf8(std::string(text));
            result_data += safe_text + "|" + std::to_string(start_sec) + "|" + std::to_string(end_sec) + "\n";
        }
    }
    return env->NewStringUTF(result_data.c_str());
}

extern "C" JNIEXPORT void
Java_com_speechcoach_stt_WhisperEngine_releaseModel(JNIEnv* env, jobject thiz) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("모델 해제 완료");
    }
}
