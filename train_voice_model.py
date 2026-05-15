"""
train_voice_model.py
────────────────────────────────────────────────────────────────
SpeechCoach TFLite 1D-CNN 모델 학습 + INT8 양자화 변환 스크립트

입력 피처: [rms_dB, pitch_Hz] × N 프레임 (3초 윈도우)
출력 레이블: [confidence, tremor] -> Softmax 적용 (합계 1.0)

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
N_SAMPLES       = 3000      # 더미 데이터 샘플 수


# ══════════════════════════════════════════════════════════════
# 1. 학습 데이터 생성 (합이 1.0이 되도록 수정)
# ══════════════════════════════════════════════════════════════
def generate_dummy_data(n_samples: int):
    X = []
    y = []

    for _ in range(n_samples):
        # 자신감 있는 목소리: 볼륨 안정, 피치 안정
        if np.random.rand() > 0.5:
            rms_seq   = np.random.uniform(-25, -15, WINDOW_FRAMES)
            pitch_seq = np.random.uniform(150, 200, WINDOW_FRAMES) + np.random.normal(0, 5, WINDOW_FRAMES)
            label = [0.8, 0.2]   # 합계 1.0

        # 떨리는 목소리: 볼륨 불안정, 피치 요동
        else:
            rms_seq   = np.random.uniform(-45, -20, WINDOW_FRAMES) + np.random.normal(0, 8, WINDOW_FRAMES)
            pitch_seq = np.random.uniform(130, 250, WINDOW_FRAMES) + np.random.normal(0, 30, WINDOW_FRAMES)
            label = [0.2, 0.8]   # 합계 1.0

        frame = np.stack([rms_seq, pitch_seq], axis=-1)
        X.append(frame)
        y.append(label)

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32)


# ══════════════════════════════════════════════════════════════
# 2. 정규화
# ══════════════════════════════════════════════════════════════
def normalize(X: np.ndarray) -> np.ndarray:
    X_norm = X.copy()
    X_norm[:, :, 0] = (X_norm[:, :, 0] + 70) / 70
    X_norm[:, :, 1] = np.clip(X_norm[:, :, 1], 0, 400) / 400
    return np.clip(X_norm, 0, 1)


# ══════════════════════════════════════════════════════════════
# 3. 1D-CNN 모델 정의 (Softmax 적용)
# ══════════════════════════════════════════════════════════════
def build_model(window: int, features: int) -> tf.keras.Model:
    inp = layers.Input(shape=(window, features), name="audio_window")

    x = layers.Conv1D(32, kernel_size=3, activation='relu', padding='same')(inp)
    x = layers.BatchNormalization()(x)
    x = layers.Conv1D(64, kernel_size=3, activation='relu', padding='same')(x)
    x = layers.BatchNormalization()(x)
    x = layers.GlobalAveragePooling1D()(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Dense(32, activation='relu')(x)
    
    # [수정] sigmoid -> softmax: 결과값의 합을 항상 1.0(100%)으로 만듦
    out = layers.Dense(2, activation='softmax', name="confidence_tremor")(x)

    model = models.Model(inputs=inp, outputs=out)
    model.compile(
        optimizer='adam',
        loss='categorical_crossentropy', # Softmax에는 CrossEntropy가 더 적합함
        metrics=['accuracy']
    )
    return model


# ══════════════════════════════════════════════════════════════
# 4. 학습
# ══════════════════════════════════════════════════════════════
def train(model: tf.keras.Model, X: np.ndarray, y: np.ndarray):
    X_train, X_val, y_train, y_val = train_test_split(X, y, test_size=0.2, random_state=42)
    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor='val_loss', patience=10, restore_best_weights=True),
        tf.keras.callbacks.ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=5)
    ]
    return model.fit(X_train, y_train, validation_data=(X_val, y_val), epochs=100, batch_size=32, callbacks=callbacks, verbose=1)


# ══════════════════════════════════════════════════════════════
# 5. TFLite 변환 (INT8 양자화)
# ══════════════════════════════════════════════════════════════
def convert_to_tflite_int8(model: tf.keras.Model, representative_X: np.ndarray, out_path: str):
    def representative_dataset():
        for i in range(min(200, len(representative_X))):
            yield [representative_X[i:i+1]]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type  = tf.int8
    converter.inference_output_type = tf.int8

    tflite_model = converter.convert()
    with open(out_path, 'wb') as f:
        f.write(tflite_model)
    print(f"\n✅ TFLite INT8 모델 저장 완료: {out_path} ({os.path.getsize(out_path)/1024:.1f} KB)")


if __name__ == "__main__":
    X_raw, y = generate_dummy_data(N_SAMPLES)
    X = normalize(X_raw)
    model = build_model(WINDOW_FRAMES, N_FEATURES)
    train(model, X, y)
    model.save(KERAS_OUT_PATH)
    convert_to_tflite_int8(model, X[:200], MODEL_OUT_PATH)

    print("\n📱 다음 단계:")
    print(f"   {MODEL_OUT_PATH} 파일을 app/src/main/assets/ 폴더에 복사하세요.")
    print("\n   Whisper 모델 (ggml-base.bin) 도 함께 배치 확인:")
    print("   app/src/main/assets/ggml-base.bin")
