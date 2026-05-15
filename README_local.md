# SpeechCoach - 온디바이스 발표 분석 앱

> 모든 처리가 기기 내부에서 완결되는 **개인정보 완전 보호** 실시간 발표 코치

---

## 📁 프로젝트 파일 구조

```
SpeechCoach/
├── app/
│   ├── build.gradle                          ← 라이브러리 의존성 전체
│   └── src/main/
│       ├── AndroidManifest.xml               ← 권한, 서비스, 액티비티 선언
│       ├── assets/
│       │   ├── vosk-model-small-ko/          ← ⚠️ 직접 다운로드 필요
│       │   └── voice_analysis.tflite         ← ⚠️ Python 스크립트로 생성 필요
│       ├── java/com/speechcoach/
│       │   ├── audio/
│       │   │   ├── AudioConfig.kt            ← 전역 오디오 상수 (16kHz/MONO/PCM16)
│       │   │   ├── AudioBroadcaster.kt       ← 마이크 → STT/분석 브로드캐스팅 허브
│       │   │   ├── RecordingService.kt       ← 포그라운드 서비스 (백그라운드 녹음)
│       │   │   └── WavConverter.kt           ← PCM → WAV 헤더 변환
│       │   ├── stt/
│       │   │   ├── VoskSTTEngine.kt          ← VOSK 온디바이스 STT + 타임스탬프 파싱
│       │   │   ├── SpeedAnalyzer.kt          ← 실시간 WPM 슬라이딩 윈도우 분석
│       │   │   └── FillerWordAnalyzer.kt     ← 습관어 Regex 탐지 + 타임스탬프 매핑
│       │   ├── analysis/
│       │   │   ├── TarsosAudioAnalyzer.kt    ← RMS 볼륨 + YIN Pitch 실시간 추출
│       │   │   ├── VoiceAnalysisModel.kt     ← TFLite 1D-CNN 추론 (자신감/떨림)
│       │   │   └── CalibrationManager.kt     ← 사용자 기준 목소리 저장/비교
│       │   ├── model/
│       │   │   └── PresentationReport.kt     ← 최종 리포트 데이터 모델 + JSON 직렬화
│       │   └── ui/
│       │       ├── hud/
│       │       │   ├── PresentationViewModel.kt ← Track1/2 통합 ViewModel (Coroutines)
│       │       │   ├── PresentationActivity.kt  ← Track1 실시간 HUD 화면
│       │       │   └── CalibrationActivity.kt   ← 기준 목소리 캘리브레이션 화면
│       │       └── report/
│       │           └── ReportActivity.kt     ← Track2 포스트 리포트 (MPAndroidChart)
│       └── res/
│           ├── layout/
│           │   ├── activity_presentation.xml ← HUD 레이아웃
│           │   ├── activity_report.xml       ← 리포트 레이아웃
│           │   └── activity_calibration.xml  ← 캘리브레이션 레이아웃
│           ├── drawable/
│           │   ├── border_overlay.xml        ← HUD 테두리 깜빡임 shape
│           │   └── ic_mic.xml                ← 알림 아이콘 (벡터)
│           └── values/
│               ├── themes.xml
│               ├── colors.xml
│               └── strings.xml
├── train_voice_model.py                      ← TFLite 모델 학습 Python 스크립트
└── settings.gradle                           ← JitPack / TarsosDSP 레포 등록
```

---

## ⚙️ 데이터 흐름 (아키텍처 요약)

```
마이크 (16kHz/MONO/PCM16)
    │
    ▼
AudioBroadcaster (SharedFlow 브로드캐스팅)
    ├──────────────────────────────────────┐
    ▼                                      ▼
[TRACK 1: 실시간 HUD]              [동시 PCM 파일 저장]
VoskSTTEngine (VOSK)
    │ 단어 + 타임스탬프 JSON
    ▼
SpeedAnalyzer
    │ WPM 슬라이딩 윈도우
    ▼
HUD UI (테두리 색상/깜빡임)

TarsosAudioAnalyzer
    │ RMS dB + Pitch Hz
    ▼
VoiceAnalysisModel (TFLite)
    │ 자신감%, 떨림%
    ▼
HUD UI (실시간 인디케이터)

─── 발표 종료 버튼 ───────────────────────
    │
    ▼
[TRACK 2: 포스트 리포트]
WavConverter (PCM → WAV)
FillerWordAnalyzer (Regex 습관어)
ReportBuilder (종합 점수 + 피드백)
    │
    ▼
ReportActivity (MPAndroidChart 그래프)
```

---

## 🚀 셋업 순서

### 1. VOSK 한국어 모델 다운로드
```
https://alphacephei.com/vosk/models
→ vosk-model-small-ko-0.22.zip 다운로드 (약 82MB)
→ 압축 해제 후 폴더명을 vosk-model-small-ko 로 변경
→ app/src/main/assets/vosk-model-small-ko/ 에 배치
```

### 2. TFLite 모델 학습 (Python)
```bash
pip install tensorflow numpy scikit-learn
python train_voice_model.py
# → voice_analysis.tflite 생성됨
# → app/src/main/assets/ 에 복사
```

> 더미 데이터로 학습된 모델은 정확도가 낮습니다.
> 실제 서비스를 위해서는 실제 발표 녹음 + 레이블링 데이터가 필요합니다.

### 3. Android Studio 빌드
```
File → Sync Project with Gradle Files
Run → Run 'app'
```

---

## 📡 주요 라이브러리

| 라이브러리 | 역할 | 버전 |
|---|---|---|
| **VOSK** | 온디바이스 STT (타임스탬프 JSON) | 0.3.47 |
| **TarsosDSP** | RMS 볼륨 + YIN Pitch Detection | 2.5 |
| **TensorFlow Lite** | 1D-CNN 온디바이스 AI 추론 | 2.14.0 |
| **MPAndroidChart** | WPM/볼륨 시각화 그래프 | 3.1.0 |
| **Kotlin Coroutines** | 백그라운드/UI 스레드 분리 | 1.7.3 |
| **Gson** | VOSK JSON 타임스탬프 파싱 | 2.10.1 |

---

## 📊 리포트 JSON 구조

```json
{
  "presentationId": "pres_1710000000000",
  "durationSec": 612,
  "speedAnalysis": {
    "avgWpm": 145,
    "maxWpm": 220,
    "minWpm": 80,
    "fastSections": [{"startSec": 120.0, "endSec": 135.0, "intensity": 215}],
    "wpmHistory": [[0.0, 130], [1.0, 145], ["..."]]
  },
  "fillerAnalysis": {
    "totalFillers": 12,
    "fillerRatePercent": 3.2,
    "fillerBreakdown": {
      "어": {"count": 5, "timestamps": [1.2, 3.5, 7.8, 15.1, 22.4]},
      "이제": {"count": 3, "timestamps": [12.1, 20.3, 35.0]}
    }
  },
  "voiceAnalysis": {
    "avgConfidencePercent": 72,
    "avgTremorPercent": 28,
    "tremorSections": [{"startSec": 150.0, "endSec": 153.0, "intensity": 75}],
    "volumeHistory": [[0, -32.5], [200, -29.1], ["..."]]
  },
  "overallScore": 76,
  "aiFeedback": "발표 중간에 불필요한 단어 사용이 많습니다..."
}
```

---

## 🔒 개인정보 보호

- 모든 STT, AI 추론, 분석이 **기기 내부에서만** 처리됨
- 서버 전송 없음 — 인터넷 권한 미사용
- 녹음 파일은 앱 내부 저장소(`filesDir`)에만 저장되며 외부 접근 불가

---

## ⚠️ 알려진 제약사항

1. **VOSK 한국어 소형 모델**의 WER(단어 오류율)은 약 15~20% 수준
   - 대형 모델(`vosk-model-ko-0.22`, 1GB+) 사용 시 정확도 향상 가능
2. **TFLite 모델**은 더미 데이터로 학습되어 실제 사용 전 재학습 필수
3. **TarsosDSP Pitch Detection**은 배경 소음이 많은 환경에서 오탐률 증가
   - 조용한 공간에서 사용 권장
