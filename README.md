기술 아키텍쳐

1. 실시간 음성 수집
AudioRecord API를 사용
실시간 음성 분석 가능
설정 스펙: 샘플링 레이트는 16,000Hz (16kHz), 채널은 MONO, 포맷은 16-bit PCM으로 설정.
-> 구글 STT와 TFLite 모델이 가장 빠르고 정확하게 소화할 수 있는 글로벌 표준 규격
AudioRecord.read() 메서드를 통해 0.1~0.5초 단위로 쪼개진 오디오 데이터 조각을 실시간으로 가져와서, STT 스레드와 오디오 분석 스레드로 동시에 쏴주는(Broadcasting) 구조로 짜야함


2. 온디바이스 STT 연동
구글 안드로이드 기본 STT보다는 오픈소스 VOSK를 쓰는걸 추천
(용량이 가벼움, JSON 타임스탬프를 실시간으로 확인해서 습관어 카운팅이나 침국구간 분석 로직을 짜기 편함)


3. 경량화 오디오 신호 처리(볼륨 && 떨림 분석)
무거운 AI를 돌리기전에 가벼운 수학처리로 1차 데이터를 뽑아내 연산량 줄이고 발열 감소
->TarsosDSP 라이브러리 사용
TarsosDSP의 RMS 추출 시능을 사용해서 마이크로 들어오는 데시벨을 숫자로 뽑아줌(목소리의 크기를 분석)
TarsosDSP의 Pitch Detection 알고리즘을 이용해 1초동안 음높이가 불규칙하게 요동친 배열 데이터를 만들어서 이를 4번 AI 모델에 입력값으로 넘겨줌


4. 온디바이스 AI모델 및 경량화(얘가 떨고 있는지, 자신감이 있는지를 확인)
1D-CNN이나 가벼운 랜덤 포레스트 모델을 사용
-> 일정시간(ex) 3초) 동안의 볼륨과 음높이 배열 데이터를 넣으면 [자신감: xx%, 떨림: yy%]등으로 분석해줌
이런 모델은 파이썬으로 먼저 학습시킴
이후 경량화 할때는 Float32 -> INT8로 압축


5. 안드로이드 시스템 스레드 분리 구조
Kotlin Coroutines을 써서 무거운 AI 연산(백엔드)과 가벼운 UI(프론트엔드를 구별)



실시간과 발표종료후로 기능 분리

🟢 TRACK 1. 발표 중 (실시간 HUD 모드) - 페이스 유지에만 집중
앱이 켜져 있는 동안에는 폰에 무리가 가지 않는 아주 가벼운 연산만 돌림

실시간 수집: AudioRecord로 마이크 입력 (백그라운드에서 임시 파일로도 동시에 녹음 저장)


실시간 속도 체크 (경량): Vosk STT가 백그라운드에서 돌면서 텍스트를 뱉어내면 .wav 파일로 바꾸고(파일의 입출력 형식 지정) TarsosDSP로 전송함과 동시에 5~10초 단위로 단어 갯수만 세어서 말이 빨라지면 테두리를 주황색으로 깜빡여줌

UX 효과: 발표자는 운전할 때 속도계만 보듯, 자기 페이스만 편안하게 조절하며 발표를 끝마칠 수 있음



🔵 TRACK 2. 발표 종료 후 (포스트 리포트 모드) - 정밀 AI 진단
사용자가 발표 종료 버튼을 누르는 순간, 로컬에 저장해 둔 녹음 파일과 전체 STT 데이터를 기반으로 무거운 분석을 돌리고 사용자에게 최종 결과를 보여줌

습관어 분석: "어...", "이제" 같은 불필요한 단어를 전체 스크립트에서 정규식(Regex)으로 완벽하게 찾아내 횟수와 위치(타임스탬프)를 매핑해줌


목소리 떨림 진단, 볼륨체크: 10분짜리 발표 녹음 파일중에서 TarsosDSP를 이용해 목소리의 크기와 떨림만을 추출해서 TFLite(1D-CNN) 모델에 입력값으로 넣음. 이후 AI가 해당 데이터를 분석후 "자신감 85%, 떨림 위험 구간: 2분 30초대" 등의 결과를 뽑아냄
목소리의 떨림을 분석하는 방법 2가지
1. 맨 처음 사용자에게 30초정도 짧은 글을 주고 읽어보라고 한뒤, 그걸 바탕으로 사용자 고유의 평상시 목소리를 기준점으로 삼고 이후 발표에서의 떨림을 분석함
2. Pitch 말고 MFCC를 사용하면 음높이뿐만 아니라 음색의 변화도 함께 추출할 수 있어 감정인식에 더욱 용이함


속도체크: 마지막에 그래프로도 전체 발표 시간중 어느 구간이 말이 빨랐고 어디가 느렸고 이런걸 시각적으로 한번에 보여줄 수 있게 해줌(MPAndroidChart를 build.gradle로 추가하여 사용)


최종적으로 모든 결과를 분석해서 종합 진단을 AI가 내려줌("발표 중간에 불필요한 단어의 사용이 많습니다. 또한 긴장을 하면 말이 빨라지는 경향이 있으니 긴장을 푸시고 말을 조금 천천히 해보시는게 어떨까요?" 이런식으로)
-> 얘는 근데 LLM 써야되면 우리가 처음에 강점으로 내세운 개인정보보호와 어긋날 수 있으니 온디바이스 LLM을 사용하거나 아님 그냥 없앨듯













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
