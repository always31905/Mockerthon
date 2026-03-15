"""
train_voice_model.py
────────────────────────────────────────────────────────────────
SpeechCoach TFLite 1D-CNN 모델 학습 + INT8 양자화 변환 스크립트

입력 피처: [rms_dB, pitch_Hz] × N 프레임 (3초 윈도우)
출력 레이블: [confidence(0~1), tremor(0~1)]

실행 순서:
  1. 라이브러리 설치: pip install tensorflow numpy scikit-learn
  2. 학습 데이터 준비 (아래 generate_dummy_data 참고)
  3. python train_voice_model.py
  4. 생성된 voice_analysis.tflite → Android Studio assets/ 폴더에 복사
────────────────────────────────────────────────────────────────
"""

import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, models
from sklearn.model_selection import train_test_split
import os

# ── 설정 ────────────────────────────────────────────────────────
WINDOW_FRAMES   = 15        # 3초 × 5fps (200ms 청크) = 15 프레임
N_FEATURES      = 2         # rms_dB, pitch_Hz
MODEL_OUT_PATH  = "voice_analysis.tflite"
KERAS_OUT_PATH  = "voice_analysis.keras"
N_SAMPLES       = 3000      # 더미 데이터 샘플 수 (실제 데이터로 교체 권장)


# ══════════════════════════════════════════════════════════════
# 1. 학습 데이터 생성 (실제 수집 데이터로 교체)
# ══════════════════════════════════════════════════════════════
def generate_dummy_data(n_samples: int):
    """
    실제 수집 데이터가 없을 때 사용하는 더미 데이터 생성기.

    레이블 기준:
    - confidence: 볼륨이 크고(-20~-10dB) 음높이가 안정적(std 낮음) → 높음
    - tremor:     음높이 표준편차가 크고 볼륨이 불규칙 → 높음

    실제 서비스에서는 발표자 녹음 + 전문가 레이블링 데이터로 교체해야 한다.
    """
    X = []
    y = []

    for _ in range(n_samples):
        # 자신감 있는 목소리: 볼륨 안정(-25~-15dB), 피치 안정(150~200Hz, std 낮음)
        if np.random.rand() > 0.5:
            rms_seq   = np.random.uniform(-25, -15, WINDOW_FRAMES)
            pitch_seq = np.random.uniform(150, 200, WINDOW_FRAMES) \
                        + np.random.normal(0, 5, WINDOW_FRAMES)   # std=5Hz (안정)
            label = [0.8, 0.2]   # confidence 높음, tremor 낮음

        # 떨리는 목소리: 볼륨 불안정(-45~-20dB, 변동 큼), 피치 요동(std 큰)
        else:
            rms_seq   = np.random.uniform(-45, -20, WINDOW_FRAMES) \
                        + np.random.normal(0, 8, WINDOW_FRAMES)
            pitch_seq = np.random.uniform(130, 250, WINDOW_FRAMES) \
                        + np.random.normal(0, 30, WINDOW_FRAMES)  # std=30Hz (불안정)
            label = [0.3, 0.75]  # confidence 낮음, tremor 높음

        frame = np.stack([rms_seq, pitch_seq], axis=-1)  # (WINDOW_FRAMES, 2)
        X.append(frame)
        y.append(label)

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32)


# ══════════════════════════════════════════════════════════════
# 2. 정규화
# ══════════════════════════════════════════════════════════════
def normalize(X: np.ndarray) -> np.ndarray:
    """
    rms_dB : -70 ~ 0  → 0~1
    pitch_Hz: 0  ~ 400 → 0~1  (0 이하는 묵음 → 0)
    """
    X_norm = X.copy()
    X_norm[:, :, 0] = (X_norm[:, :, 0] + 70) / 70   # rms
    X_norm[:, :, 1] = np.clip(X_norm[:, :, 1], 0, 400) / 400  # pitch
    return np.clip(X_norm, 0, 1)


# ══════════════════════════════════════════════════════════════
# 3. 1D-CNN 모델 정의
# ══════════════════════════════════════════════════════════════
def build_model(window: int, features: int) -> tf.keras.Model:
    """
    경량 1D-CNN 구조:
    Conv1D(32) → Conv1D(64) → GlobalAvgPool → Dense(2, sigmoid)

    파라미터 수: ~10K (INT8 양자화 후 ~2.5KB)
    """
    inp = layers.Input(shape=(window, features), name="audio_window")

    x = layers.Conv1D(32, kernel_size=3, activation='relu', padding='same')(inp)
    x = layers.BatchNormalization()(x)
    x = layers.Conv1D(64, kernel_size=3, activation='relu', padding='same')(x)
    x = layers.BatchNormalization()(x)
    x = layers.GlobalAveragePooling1D()(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Dense(32, activation='relu')(x)
    out = layers.Dense(2, activation='sigmoid', name="confidence_tremor")(x)
    # out[0] = confidence (0~1)
    # out[1] = tremor     (0~1)

    model = models.Model(inputs=inp, outputs=out)
    model.compile(
        optimizer='adam',
        loss='mse',
        metrics=['mae']
    )
    return model


# ══════════════════════════════════════════════════════════════
# 4. 학습
# ══════════════════════════════════════════════════════════════
def train(model: tf.keras.Model, X: np.ndarray, y: np.ndarray):
    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.2, random_state=42
    )

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor='val_loss', patience=10, restore_best_weights=True
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor='val_loss', factor=0.5, patience=5
        )
    ]

    history = model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=100,
        batch_size=32,
        callbacks=callbacks,
        verbose=1
    )
    return history


# ══════════════════════════════════════════════════════════════
# 5. TFLite 변환 + INT8 양자화 (Float32 → INT8)
# ══════════════════════════════════════════════════════════════
def convert_to_tflite_int8(model: tf.keras.Model,
                            representative_X: np.ndarray,
                            out_path: str):
    """
    INT8 양자화로 모델 크기를 Float32 대비 ~4배 압축.
    representative_dataset: 실제 데이터 분포를 반영하는 샘플 필수.
    """
    def representative_dataset():
        for i in range(min(200, len(representative_X))):
            sample = representative_X[i:i+1]  # (1, WINDOW_FRAMES, N_FEATURES)
            yield [sample]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type  = tf.int8
    converter.inference_output_type = tf.int8

    tflite_model = converter.convert()
    with open(out_path, 'wb') as f:
        f.write(tflite_model)

    size_kb = os.path.getsize(out_path) / 1024
    print(f"\n✅ TFLite INT8 모델 저장 완료: {out_path} ({size_kb:.1f} KB)")


# ══════════════════════════════════════════════════════════════
# 6. 메인
# ══════════════════════════════════════════════════════════════
if __name__ == "__main__":
    print("=== SpeechCoach 1D-CNN 모델 학습 ===\n")

    # 데이터 준비
    print(f"[1/4] 데이터 생성 ({N_SAMPLES}개)...")
    X_raw, y = generate_dummy_data(N_SAMPLES)
    X = normalize(X_raw)
    print(f"      X shape: {X.shape}, y shape: {y.shape}")

    # 모델 빌드
    print("\n[2/4] 모델 빌드...")
    model = build_model(WINDOW_FRAMES, N_FEATURES)
    model.summary()

    # 학습
    print("\n[3/4] 학습 시작...")
    train(model, X, y)

    # Keras 모델 저장
    model.save(KERAS_OUT_PATH)
    print(f"\n      Keras 모델 저장: {KERAS_OUT_PATH}")

    # TFLite INT8 변환
    print("\n[4/4] TFLite INT8 양자화 변환...")
    convert_to_tflite_int8(model, X[:200], MODEL_OUT_PATH)

    print("\n📱 다음 단계:")
    print(f"   {MODEL_OUT_PATH} 파일을")
    print("   Android Studio → app/src/main/assets/ 폴더에 복사하세요.")
    print("\n   VOSK 한국어 모델 (vosk-model-small-ko-0.22) 도 함께 배치:")
    print("   app/src/main/assets/vosk-model-small-ko/")
