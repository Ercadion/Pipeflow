# PipeFlow 안드로이드 앱

관 끝단을 폰으로 3초 촬영하면 **수심 + H-STIV 표면유속 + 유량**을 폰 안에서(오프라인) 계산합니다.
화면과 카메라는 Kotlin(CameraX), 계산은 Python(numpy)으로 하고, 둘은 Chaquopy로 연결합니다.

```
android-app/
├─ app/src/main/java/io/github/ercadion/pipeflow/   Kotlin
│   ├─ MainActivity      설정 입력 (내경·경사·재질·fps·촬영시간), 메뉴 → 측정 데이터
│   ├─ DataActivity      측정 데이터 목록·선택 내보내기(zip)·삭제
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
2. 설정: 관 내경, (선택) 경사·재질 입력 → **측정 시작** (관 두께는 입력하지 않음 — 내경 테두리를 직접 찾음)
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
- 화면 표시: **빨간색 = 관 내경 테두리**(v0.3.2부터 영상에서 직접 찾음), 노란 선 = 중력 기준 수평
  - 찾는 중: 회색 점선 가이드 / 포착: 빨간 내경 타원 / 촬영 중: 굵은 빨강 + REC
- **촬영 준비 체크리스트**: 관 포착(테두리 확인 %), 크기(화면의 45~98%), 중앙, 테두리 전체가 화면 안, 흔들림(자이로), 각도(관 기울기·내려다보는 각), 밝기
  - 모두 통과하면 촬영 버튼이 초록색 "촬영 (준비 완료)" — 준비 전에도 촬영은 가능
- **조명 버튼**: 후면 플래시(토치) 켜기/끄기, 촬영 중에도 유지. 어두우면 체크리스트가 조명을 권함
- 촬영 순간 포착한 타원을 `meta.json`의 `live_ellipse` 로 저장 → 분석 시 원 해상도에서 정밀화(전역 검출보다 빠르고, 60~70° 내려다볼 때 더 정확)
- 결과 화면 **"테두리 자동 재검출"** 버튼: 포착 타원이 틀렸을 때 전체 영상에서 다시 찾기

### v0.3.1 — 실시간 타원 널뛰기 수정
실폰 시험(노트북 화면 속 관 사진)에서 빨간 타원이 작업표시줄·키보드 같은 **직선 띠를 납작한 타원으로** 잡거나 프레임마다 다른 후보로 바뀌던 문제 수정
- 직선 띠 거부: 둘레 4분면(장축 양 끝·단축 양 끝)이 고르게 확인돼야 함, b/a ≥ 0.20, 포착 기준 덮임 0.35 → 0.5
- 잡음 대비 선명도: 타원을 ±15% 키우고 줄였을 때보다 확실히 잘 맞아야 함(키보드·글자처럼 엣지가 빽빽한 곳의 가짜 타원 제거)
- 새 후보 생성: 평행 접선 쌍의 중점 투표로 중심을 찾고 그 쌍들로 타원 결정 → 관 테두리가 사진 창 테두리 같은 직선과 붙어 있어도 찾음
- 엣지 끊기: 기울기 방향이 급변하는 곳(모서리, 직선↔곡선)에서 조각을 끊음, 엣지 후보 상위 12% → 20%
- 같은 영상이면 항상 같은 결과(난수 고정)
- `LiveRimTracker.java` 히스테리시스: 비슷한 타원 2프레임 연속 → 표시, 전혀 다른 타원은 3프레임 연속이어야 교체, 6프레임 연속 실패 → 해제. 서로 다른 타원을 섞은 중간 모양을 그리지 않음
- 시험(`core_tests/java`): 실폰 화면 6장 모두 관 포착, 흔들림+잡음 연속 프레임에서 프레임 간 이동 평균 1% 이내(장반축 대비), 기존 예제 6장·30~75° 합성 영상 유지

검증(합성 영상, 초점거리 사용): 30~75° 내려다보기, 수심 25/50/75 mm 에서 실시간 타원 seed → 정밀화 후 수심 오차 0.5 mm 이내
(`core_tests/test_steep.py`), 실시간 검출 결과 그림 `core_tests/live_detection_overview.jpg`

### v0.3.2 — 관 두께 입력 제거 (내경 테두리 직접 검출)
- 설정 화면에서 **관 두께 입력 삭제**. 끝단 면이 보이면 바깥·안쪽 두 동심 테두리가 생기는데, 그중 **안쪽(내경)** 을 영상에서 직접 골라 씀
  - 검출한 타원을 0.72~1.38배로 바꿔가며 둘레 덮임을 측정 → 안쪽에 또렷한 동심 테두리가 있으면 그것, 바깥에 있으면 검출한 것이 이미 안쪽, 하나뿐이면(맨홀 벽에 묻혀 끝단 면이 안 보이는 경우 등) 그대로 내경
  - 초점거리를 알면 투시를 정확히 반영한 동심원 타원으로 찾음(가파른 각도에서 단축 방향 간격이 좁아지는 것 반영), 마지막 재피팅은 원 해상도에서 두 테두리 사이를 넘지 않는 좁은 띠로
  - 실시간(Java `selectInner`)과 분석(Python `select_inner_rim`) 같은 규칙, 결과 화면에 "내경: 끝단 면 안쪽 테두리 / 테두리 하나" 표시
- 수위 계산은 `measure_level(D, 0, rim_is="inner")` — 내경 테두리 = 입력한 내경
- 합성 검증(`core_tests/test_inner.py`, 결과 `results_inner.txt`): 두께 4·12 mm, 위에서 30~75°, 자동/바깥 seed/안쪽 seed 모두 내경 선택. 30~60° 수심 오차 ≲0.5 mm, 75°·얇은 벽(4 mm)은 두 테두리 간격이 2~3 px 뿐이라 1~2 mm
- 촬영 화면에는 빨간 내경 타원만 표시(체크리스트에 내경 판정 근거 표시)

### v0.3.3 — 확대/축소 · 터치 초점 · 검사 범위 지정
- **두 손가락 벌리기/모으기**: 카메라 확대/축소 (화면 오른쪽 위에 배율 표시), **두 번 탭**: 1× 로
  - 촬영 순간의 배율을 `meta.json` 의 `zoom_ratio` 로 저장하고 초점거리(f_px)·주점을 배율만큼 보정 → 확대해서 찍어도 완전투시 계산 유지
  - (광학 렌즈가 여러 개인 기기는 배율에 따라 렌즈가 바뀌어 근사값일 수 있음)
- **탭**: 그 위치에 초점·노출을 맞추고(자동 해제 안 함), 그 주변(짧은 변의 45% 반경, 노란 점선 원)을 **관 검사 범위**로 지정
  - 검사 범위 안에 걸친 엣지로만 타원 후보를 만들고, **터치한 점을 품는 타원만** 관으로 인정 → 키보드·창틀처럼 엉뚱한 곳을 잡을 때 관을 탭하면 해결
  - 배율을 바꾸면 검사 범위도 같이 옮겨짐, 촬영 중에는 배율·초점·범위 고정
- **길게 누르기**: 검사 범위 해제 + 자동 초점으로 복귀
- 예: 실폰 화면(노트북 위 사진)에서 자동으로는 키보드를 잡던 장면이 관을 탭하면 관을 잡음 (`core_tests/roi_touch_demo.jpg`)
- 분석: 촬영 시 검사 범위는 `meta.touch_roi` 로 기록(참고용)
- 데스크톱 PyTorch 버전도 관 두께 입력을 없애고 같은 내경 자동 선택 사용 (`--wall` 삭제)

### v0.3.4 — 자동 결과와 사람 수정 결과를 모두 보존
- 측정 폴더 파일
  - `result_auto.json`: **처음 자동 결과**(사람 수정 전). 한 번 저장되면 다시 계산해도 바뀌지 않음
  - `result.json`: 가장 최근(최종) 결과
  - `runs.json`: 분석 회차 기록 — 회차마다 종류(auto / manual_waterline / rim_redetect), 시각, 입력(수면선·타원·실시간 타원 사용 여부), 결과 전체
  - `labels.json`: `auto_feedback`(처음 자동 결과가 맞았나), `final_feedback`(수정한 최종 결과가 맞나 + 그때의 수면선·타원·수심), `edits`(수정 이력: 종류, 수정 전/후 수면선 또는 타원, 수정 전/후 수심, 실제로 바뀌었는지), `auto_waterline`/`manual_waterline`, 메모
- 결과 화면: 수정한 경우 "처음 자동 결과: 수심 … · 유속 … · 유량 …" 을 함께 표시하고, "수정한 최종 결과가 맞나요?" 버튼이 추가로 나타남. 위쪽 버튼은 "처음 자동 결과(수정 전)가 맞았나요?"
- 자동 수면선 좌표는 화면 메모리가 아니라 `result_auto.json` 에서 읽음 → 결과 화면을 닫았다 다시 열어 여러 번 고쳐도 자동 기록이 덮어써지지 않음
- 예전 세션: 처음 열 때 수정 흔적이 없으면 지금 결과를 `result_auto.json` 으로 보존, 이미 수정된 세션은 `legacy_auto_result_lost` 로 표시
- `tools/sessions_to_dataset.py`: `auto_*`(처음 자동) / `final_*`(최종) 수심·유속·유량을 나란히, `depth_change_mm`, `edited`, `edit_types`, `n_runs`, `auto_feedback`, `final_feedback`, `feedback_legacy` 열 추가

### v0.3.5 — 측정 데이터 관리 화면, 폴더 이름에 앱 버전, 실측값 입력
- **저장 구조**: `sessions/<촬영날짜>/<촬영시각>_v<앱버전>/` (예: `sessions/20261007/192009_v0.3.5/`) — 날짜 폴더 하나에 그날 측정이 모임. 예전 측정(`sessions/20261005_221530/`)도 그대로 함께 표시
- **앱 버전**: `android-app/version.properties` 의 `versionName` 을 배포(커밋)할 때 직접 수정 → 폴더 이름·`meta.json`(`app_version`)·결과(`version`)·첫 화면에 자동 기록
  - 설치 업데이트 번호(versionCode)는 GitHub Actions 실행 번호 + 100 으로 자동 (PC 빌드 50)
  - 빌드한 커밋도 `app_build`/`build` 로 자동 기록 (Actions 빌드면 커밋 해시 7자리, PC 빌드는 `local`) → 버전 숫자를 안 올려도 어떤 코드로 측정했는지 구분됨
- **측정 데이터 화면** (첫 화면 오른쪽 위 메뉴 **측정 데이터**, 또는 첫 화면 버튼)
  - 목록: 촬영 시각·앱 버전, 수심·표면유속, 수정함/실측값/자동 맞음·틀림 표시, 크기
  - 결과 화면 오른쪽 위 메뉴(⋮) → **이 측정 삭제**
  - 항목 누르기 → 결과 화면, 체크박스로 선택 → **선택 내보내기**(zip 하나로 묶어 드라이브·메일·메신저 공유) / **선택 삭제**(확인 후, 되돌릴 수 없음)
  - 데이터는 앱 저장소(`Android/data/io.github.ercadion.pipeflow/files/sessions/`)에 저장
- **결과 화면 실측값 입력**: 실측 수심(mm)·평균유속(m/s)·유량(L/s) → `labels.json` 의 `truth_*` (학습 정답·엔진 점검 기준)
- **엔진 점검(배포 전, 개발자 PC)**: `tools/README.md` 참고 — 내보낸 zip 을 모아 새 엔진과 배포 엔진으로 최근 N건 재분석·비교 (앱 사용자 화면에는 없음)

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
