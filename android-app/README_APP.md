# PipeFlow 안드로이드 앱

관 끝단을 폰으로 3초 촬영하면 **수심 + H-STIV 표면유속 + 유량**을 폰 안에서(오프라인) 계산합니다.
화면과 카메라는 Kotlin(CameraX), 계산은 Python(numpy)으로 하고, 둘은 Chaquopy로 연결합니다.

```
android-app/
├─ app/src/main/java/io/github/ercadion/pipeflow/   Kotlin
│   ├─ MainActivity      설정 입력 (내경·두께·경사·재질·fps·촬영시간), 측정 기록
│   ├─ CaptureActivity   카메라 미리보기 + 원 가이드 + 녹화 (Y 평면 직접 저장, 실제 타임스탬프, 중력센서)
│   ├─ ResultActivity    결과 표시, 수면선 드래그 수정 → 재계산, 맞음/틀림 라벨, zip 내보내기
│   ├─ CameraIntrinsics  Camera2 정보로 초점거리(px)·주점 계산 → 체스보드 없이 완전투시
│   └─ OverlayImageView / GuideOverlayView / SessionStore / PyBridge / Settings
├─ app/src/main/python/
│   ├─ pipeflow_app.py   Kotlin ↔ Python 진입점 analyze(session_dir, params, listener)
│   └─ pipeflow_core/    numpy 전용 계산 엔진 (데스크톱 PyTorch 버전을 이식, 결과 동일성 검증)
├─ core_tests/           데스크톱 검증 (test_quick: CI용 / test_core·test_app_session: 합성 영상)
└─ app/demo.keystore     데모 서명 키 (빌드마다 같은 서명 → 덮어쓰기 설치 가능)
```

## APK 빌드 (GitHub Actions — PC에 설치할 것 없음)
1. GitHub 에 새 저장소 생성 (예: `Ercadion/pipeflow`, Private 가능)
2. 이 프로젝트 폴더(`관로 수위·유속 측정 (pipeflow)`) 전체를 올림
   - GitHub Desktop: File → Add local repository → 이 폴더 → Publish repository
   - 또는 명령행: `git init && git add . && git commit -m init && git branch -M main && git remote add origin https://github.com/Ercadion/pipeflow.git && git push -u origin main`
3. **빌드 설정 파일 넣기**: `ci/build-apk.yml` 을 `.github/workflows/build-apk.yml` 위치로 복사
   (보안상 `.github` 폴더는 Claude 가 직접 쓸 수 없어 `ci/` 에 두었습니다.
    또는 GitHub 웹 → Actions → *set up a workflow yourself* → 내용 붙여넣기 → Commit)
4. 저장소의 **Actions** 탭 → "Build Android APK" 가 자동 실행 (약 5~10분)
5. 완료된 실행 화면 아래 **Artifacts → PipeFlow-apk** 다운로드 → 압축 풀면 `.apk`
6. 배포용 고정 링크: `git tag v0.2.0 && git push --tags` → **Releases** 에 APK 가 첨부됨

> 첫 빌드가 실패하면 Actions 로그의 빨간 부분을 그대로 복사해 Claude 에게 주세요.
> (이 작업 환경에서는 안드로이드 빌드 도구를 내려받을 수 없어 Gradle 빌드는 아직 실행해 보지 못했습니다.
>  Python 계산부는 데스크톱에서 전부 검증했습니다.)

### PC에서 직접 빌드 (선택)
Android Studio(최신) + Python 3.10 설치 → `android-app` 폴더 열기 → Build → Build APK(s).
Chaquopy 가 PC의 Python 3.10 으로 numpy 를 받아 넣으므로 Python 3.10 이 PATH 에 있어야 합니다.

## 설치·사용
1. 폰에서 APK 열기 → "출처를 알 수 없는 앱 설치 허용" → 설치
2. 설정: 관 내경, 관 두께, (선택) 경사·재질 입력 → **측정 시작**
3. 관 끝단이 원 가이드 안에 오도록, **수면을 위에서 비스듬히 내려다보는** 각도로 폰을 고정 → 촬영 (3초, 움직이지 않기)
4. 결과: 초록 타원(테두리)·빨간 선(수면선) 확인
   - 수면선이 틀리면 **수면선 수정** → 빨간 점 드래그 → **다시 계산**
   - **맞음/틀림** 과 참값 메모(자로 잰 수심, 유량계 값 등)를 남기면 학습 데이터 품질이 올라감
5. **내보내기**: 세션 zip(원본 프레임 + 결과 + 라벨) 공유 → PC 로 모아 학습 데이터셋으로 사용

### 측정 품질 팁
- 실제 fps 는 기기마다 다름(화면 하단 표시). 60fps 미지원 기기는 30fps 로 자동 조정, 실제 타임스탬프로 보정함
- 느린 흐름(<0.05 m/s)은 촬영 시간을 5~8초로
- 수면에 작은 부유 입자 투입, 끝단 랩 주름 최소화, 화면 중앙에 관을 두기
- 초점거리는 Camera2 값으로 자동 계산(`LENS_INTRINSIC_CALIBRATION` 또는 초점거리/센서크기). 렌즈 왜곡 계수는 meta.json 에 기록만 하고 아직 보정에 쓰지 않음

## 촬영 화면 — 실시간 관 포착 (v0.3)
- 미리보기를 긴 변 ~320px 로 줄여 **초당 약 8회 관 테두리를 자동 검출**(`LiveRimDetector.java`, 외부 라이브러리 없음)
  - 엣지 → 곡선 조각 → 조각 1~3개 조합 타원 피팅 → 둘레 덮임 비율로 선택, 이후 프레임은 이전 타원 주변만 추적
  - 데스크톱 기준 전역 탐색 8~50 ms, 추적 2~10 ms. 예제 사진 6장 모두 포착, 위에서 30~75° 내려다본 합성 영상도 포착
- 화면 표시: 흰색 = 바깥 테두리, **빨간색 = 관 내경**(입력한 내경·두께로 축소), 노란 선 = 중력 기준 수평
  - 찾는 중: 회색 점선 가이드 / 포착: 빨간 내경 타원 / 촬영 중: 굵은 빨강 + REC
- **촬영 준비 체크리스트**: 관 포착(테두리 확인 %), 크기(화면의 45~98%), 중앙, 테두리 전체가 화면 안, 흔들림(자이로), 각도(관 기울기·내려다보는 각), 밝기
  - 모두 통과하면 촬영 버튼이 초록색 "촬영 (준비 완료)" — 준비 전에도 촬영은 가능
- **조명 버튼**: 후면 플래시(토치) 켜기/끄기, 촬영 중에도 유지. 어두우면 체크리스트가 조명을 권함
- 촬영 순간 포착한 타원을 `meta.json`의 `live_ellipse` 로 저장 → 분석 시 원 해상도에서 정밀화(전역 검출보다 빠르고, 60~70° 내려다볼 때 더 정확)
- 결과 화면 **"테두리 자동 재검출"** 버튼: 포착 타원이 틀렸을 때 전체 영상에서 다시 찾기

검증(합성 영상, 초점거리 사용): 30~75° 내려다보기, 수심 25/50/75 mm 에서 실시간 타원 seed → 정밀화 후 수심 오차 0.5 mm 이내
(`core_tests/test_steep.py`), 실시간 검출 결과 그림 `core_tests/live_detection_overview.jpg`

## 데이터 → 신경망 (다음 단계)
모든 측정은 `Android/data/io.github.ercadion.pipeflow/files/sessions/` 에 세션 폴더로 남고, zip 으로 내보낼 수 있습니다.
```
python tools/sessions_to_dataset.py 내보낸zip들/*.zip --out dataset
```
→ `dataset/images/*.png` + `dataset/index.csv` (테두리 타원, 자동/수정 수면선, 수심, 유속, 피드백, 메모)

권장 로드맵
1. **수면선·테두리 검출 모델** (가장 효과 큼): 경량 분할/키포인트 CNN(MobileNet·U-Net 계열)을 PyTorch 로 학습
   - 라벨: 앱에서 사용자가 확인/수정한 수면선 + 자동 테두리. 수백 장부터 의미 있음
   - 합성 렌더러(`tests/synth.py`)로 사전학습 → 실제 데이터로 미세조정
   - 기하 계산(타원 → 완전투시 → 수심)은 그대로 유지하고 "검출" 단계만 교체 → 물리적으로 해석 가능한 결과 유지
2. **유속 모델**: STI → 줄무늬 각도 회귀 CNN. 참값(유량계, 부피법 Q/A)이 있는 세션이 필요
3. **폰 배포**: PyTorch → ONNX → ONNX Runtime Android(Kotlin) 또는 ExecuTorch. Kotlin 에서 모델 결과(수면선 좌표)를 `params.waterline_pts` 로 Python 엔진에 넘기면 나머지 계산은 동일
