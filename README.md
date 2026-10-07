# PipeFlow — 관로 끝단 사진으로 수위·유속 계산 (기초 알고리즘 + 안드로이드 앱)

원형 관로 끝단을 찍은 사진과 **관 내경**을 넣으면 다음을 계산합니다.
1. 끝단 테두리 타원 → 카메라 기울기
2. 수면선 → **수심 h**
3. 유속: (A) 사진 1장 + 관 경사로 계산하는 **등류 공식**, (B) 연속 프레임의 **시공간영상(H-STIV)** 분석 실측값
4. (선택) 체스보드 **카메라 캘리브레이션**으로 렌즈 왜곡 보정 + 초점거리 확보

웹은 아직 없습니다. `pipeflow.pipeline` 의 함수를 나중에 웹 백엔드(FastAPI 등)에서 그대로 호출하면 됩니다.

## 📱 안드로이드 앱 (`android-app/`)
같은 알고리즘을 numpy 엔진(`pipeflow_core`)으로 이식해 폰에서 오프라인으로 실행하는 앱입니다.
빌드·설치·데이터 수집 방법은 [`android-app/README_APP.md`](android-app/README_APP.md) 참고.
`ci/build-apk.yml` 을 `.github/workflows/` 에 두고 GitHub 에 올리면 자동으로 APK 가 만들어집니다.

## 설치
```bash
pip install -r requirements.txt     # torch, opencv-python-headless, numpy, pillow
```

## 사용법
```bash
# 사진 1장 → 수위 + 등류 공식 유속 (내경 100 mm, 경사 0.5 %) — 관 두께는 입력하지 않음(내경 테두리 자동 선택)
python -m pipeflow.cli data/img3.jpg --diameter 100 --slope 0.005 --material glass --out overlay.jpg

# 자동 검출된 수면선이 틀렸을 때: 수면선 위의 두 점을 직접 지정 (이미지 px)
python -m pipeflow.cli data/img2.jpg --diameter 100 --waterline 60 885 1180 878

# 초점거리(px)를 알면 완전투시 보정 (권장)
python -m pipeflow.cli img.jpg --diameter 100 --f-px 1450

# 카메라 캘리브레이션 (체스보드 20~30장, 한 번만) → camera.json
python -m pipeflow.calibration "calib/*.jpg" --pattern 9 6 --square 25 --out camera.json

# 캘리브레이션 적용 (왜곡 보정 + 완전투시) — 권장
python -m pipeflow.cli img.jpg --diameter 100 --calib camera.json

# 연속 프레임 → H-STIV 표면유속 (카메라 고정, 3초 이상 권장)
python -m pipeflow.cli frames/f000.png --diameter 100 --calib camera.json --frames frames/*.png --fps 60
#   --method phase  : 예전 프레임쌍 위상상관 방식, --lines 5 : 폭 방향 측정선 수
```
Python에서 호출:
```python
from pipeflow.pipeline import measure_level, velocity_from_level, velocity_from_frames
lvl = measure_level("img.jpg", diameter_mm=100)                    # f_px=... 주면 투시 보정
v   = velocity_from_level(lvl, slope=0.005, material="glass")       # v["V"], v["Q"], v["Re"] ...

from pipeflow.calibration import load_calibration
from pipeflow.pipeline import velocity_from_frames_stiv
cal = load_calibration("camera.json")
lvl = measure_level(frames[0], 100, calib=cal)
st  = velocity_from_frames_stiv(frames, 1/60, lvl)                  # st["v_surface"], st["lines"], ...
```

## 알고리즘

### 내경 테두리 자동 선택 (`detect.select_inner_rim`) — 관 두께 입력 불필요
- 끝단 면이 보이면 바깥·안쪽 두 동심 테두리가 생김 → **안쪽(내경)** 을 골라 수위 계산의 기준(= 입력 내경)으로 씀
- 검출 타원을 장축 0.72~1.38배(단축은 추가로 0.88~1.12배, 단축 방향 중심 이동 포함)로 바꾼 동심 타원들의 둘레 덮임을 한 번에 계산
  → 안쪽에 또렷한 테두리(덮임 ≥0.55) 있으면 그것, 바깥에 확실한 테두리(≥0.7) 있으면 검출 것이 이미 안쪽, 둘 다 없으면 단일 테두리 = 내경
- 검출 타원이 두 테두리를 섞어 맞춘 경우(장축은 바깥, 단축은 안쪽)도 보정 후 다시 판정
- 마지막 재피팅은 두 테두리 사이 간격을 넘지 않는 좁은 띠로, 원 해상도에서
- 끄기: `--no-inner-select` / `measure_level(..., select_inner=False)` (검출된 테두리를 그대로 내경으로)
- 검증: `tests/test_inner.py` (두께 4·12 mm, 위에서 30~75°), 결과 `tests/results/inner.txt`

### 0. 카메라 캘리브레이션 (`calibration.py`) — 이준형 외(2021) 반영
- 체스보드 Zhang(2000) 방식(OpenCV `calibrateCamera`) → 초점거리·주점·왜곡계수(k1,k2,p1,p2,k3)
- 측정 영상은 먼저 왜곡 보정 후 처리, f·주점은 완전투시 계산과 유속 추적에 사용
- 리사이즈된 영상은 K 를 자동 스케일, **크롭된 영상은 사용 불가**(주점이 달라짐)

### 1. 끝단 테두리 검출 → 카메라 기울기 (`detect.py`, `geometry.py`)
- Canny 엣지 → 곡선 조각에서 7점씩 뽑아 원뿔곡선(conic) 가설 3000개를 **torch 배치 SVD**로 한 번에 피팅
- 각 가설 점수 = 엣지 점(기울기 방향이 타원 법선과 일치)이 덮는 **각도 범위**. 랩 주름 같은 긴 직선보다 둘레 전체를 감싸는 타원이 이김
- 상위 후보를 inlier로 재피팅(Fitzgibbon direct LSQ). 유리 내·외벽처럼 동심 타원이 여럿이면 가장 바깥 것을 고름
- 카메라 기울기 θ = arccos(b/a), 기울기 방향 = 단축 방향

### 2. 수심 (`pipeline.measure_level`)
- 타원을 원으로 펴는 affine 변환(약투시 복원)을 만들고, torch `grid_sample` 로 단면 영상을 원 모양으로 복원
- 복원 영상에서 수면선 = **현(chord)** 을 (각도 θ, 중심거리 ρ) 공간에서 전수 탐색 (Radon/Hough)
  - 영역 대비: 선 위·아래 띠의 텍스처 에너지 차이 (현재 장치는 물 쪽에 랩 주름이 더 많이 보임 → `texture_sign=+1`)
  - 경계 일관성: 현 전체에서 밝기 기울기 **극성이 일관된** 선일수록 높음 (주름은 극성이 섞여 상쇄됨)
- 수면은 수평이므로 현의 법선 = 중력 방향 → 카메라 roll 이 있어도 별도 보정이 필요 없음
- **h = r_in − ρ** (ρ: 원 중심→수면선 거리, 물 쪽이 +)
- `f_px` 를 주면 **완전투시 원 자세 추정**(conic 고유분해, 해 2개 → '카메라가 관 위' 조건으로 선택)으로 수면선을 끝단 평면에 정확히 역투영

### 3-A. 유속: 사진 1장 + 등류 공식 (`hydraulics.py`)
사진 한 장에는 움직임 정보가 없으므로 **등류(정상·균일 흐름) 가정**으로 수심에서 유속을 계산합니다.
- 부분 충만 원관: θ = 2·acos(1 − 2h/D), A = D²/8·(θ − sinθ), P = Dθ/2, Rh = A/P
- Manning(난류): V = (1/n)·Rh^(2/3)·S^(1/2)
- 층류: V = g·S·Rh²/(2ν) (Darcy f = 64/Re, 반관에서 정확)
- `regime="auto"`: 층류로 계산했을 때 Re < 2000 이면 층류, 아니면 Manning. Re, Froude 수도 같이 출력
- 필요 입력: **관 경사 S**, 조도계수 n (재질 기본값: 유리 0.010, 아크릴/PVC 0.009 …)

> "난류 없음" 가정 관련: 내경 100 mm·경사 0.5 %·반관이면 Re ≈ 6만으로, 실제 흐름은 거의 항상 난류입니다.
> 그래서 기본값은 auto이고, 층류 공식을 강제해도 Re 가 2000 을 넘으면 경고를 냅니다.
> 한 방향 1차원 정상 흐름이라는 가정은 등류 공식에 그대로 반영되어 있습니다.

### 3-B. 유속: 연속 프레임 → H-STIV (`stiv.py`, `pipeline.velocity_from_frames_stiv`) — 기본 방식
류권규(2024) H-STIV + 특허 10-1512690 의 시공간영상 개념을 우리 구도에 맞게 적용
1. 수면 top-view(0.5 mm/px) 위에 **관 축 방향 측정선**을 폭 방향으로 5개 긋고, 프레임마다 잘라 쌓아 시공간영상(STI) 생성
2. **정지 무늬 제거**: STI 각 열의 시간 평균을 뺌 → 랩 주름·반사광처럼 안 움직이는 무늬가 '유속 0' 으로 잡히는 것 방지 (논문에 없는 추가 단계)
3. **적응형 프레임 간격**: 예비 변위가 2 px/step 미만이면 프레임을 건너뛰어(stride) 변위를 키움 → 0.5 px 미만 화소 이산성 오차 회피
4. 각 측정선마다
   - C-STIV: 시간 간격 1 의 행 쌍을 x 로 밀며 상호상관 + 3점 가우스 부화소 보간 (기본은 연속행 전체 사용, `c_split="oddeven"` 으로 CASTI 원형 선택 가능)
   - F-STIV: STI 의 2D FFT 에서 원점 지나는 최대 에너지 직선 ω = −u·kx 의 방향 (0.25° 탐색 → 0.01° 정밀화)
   - H-STIV: |u| < 2 px/fr 이면 F, 아니면 C 채택 + **두 결과 일치도(agreement)** 를 신뢰도로 출력, 10% 이상 차이 나면 경고
5. 측정선별 유속의 중앙값 = 표면유속, 측정선 간 차이 = 폭 방향 분포

### 3-C. 유속: 프레임쌍 위상상관 (`pipeline.velocity_from_frames`, `--method phase`) — 이전 방식
- 카메라가 관 축을 따라 들여다보는 구도라 흐름이 시선 방향과 거의 평행합니다. 그래서 영상에서 바로 광류를 구하지 않고
  **수면을 위에서 본 영상(top-view, 0.5 mm/px)으로 투영 변환**한 뒤 추적합니다 (3D 자세 + `grid_sample`)
- top-view 에서는 흐름이 순수한 평행이동이 되므로 **torch.fft 위상상관**으로 프레임 간 이동량(mm)을 구함 → v = Δs/Δt
- 표면유속 → 단면평균유속: 난류 0.85, 층류 0.5
- 조건: 카메라 고정, `f_px` 필요, 카메라가 수면보다 충분히 위에 있어야 함(시선-수면 각 10° 이상 권장), 수면에 추적할 무늬(부유 입자)가 있어야 함

## 검증 결과 (H-STIV · 캘리브레이션, `tests/results/`)

**인공 STI (논문 3.2절 조건 재현, 0.1~19 px/fr 20종)** — `test_stiv.py`
| 구간 | C-STIV 오차 | F-STIV 오차 | H-STIV 오차 |
|---|---|---|---|
| 0.1~0.4 px/fr | 2~3.4 % | ≤ 0.11 % | ≤ 0.11 % |
| 0.5~7 px/fr | ≤ 1.3 % | ≤ 0.13 % | ≤ 0.13 % |
| 8~19 px/fr | ≈ 0 % | 0.07~0.34 % | ≈ 0 % |
- 논문과 같은 경향(C는 작은 변위에, F는 큰 변위에 약함) 재현. 잡음 σ=0.3 + 정지 무늬에서도 H-STIV 평균 0.04 %
- 정지 무늬가 강하면 제거 없이 C/F 모두 '유속 0' 으로 실패 → 정지 무늬 제거 후 정확

**합성 관 영상 (정지 무늬 + 센서 잡음, 60 fps 2초)** — `test_stiv_pipeline.py`
| 참 표면유속 | 프레임쌍 위상상관 | H-STIV |
|---|---|---|
| 0.02 m/s | 오차 93 % | 0.5 % (stride 3) |
| 0.05 m/s | 10~19 % | 0.4 % |
| 0.15~0.8 m/s, 정지 무늬 없음 | 0.2~1.5 % | 0.4 % |
| 0.15~0.4 m/s, 정지 무늬 있음 | 실패 (≈0) | 0.4 % |
| −0.2 m/s (역방향) | 실패 | 0.5 % |

**캘리브레이션** — `test_calibration.py` (참 f=820px, k1=−0.28, 가장자리 왜곡 약 100 px)
- 추정 f 820.3 px, 주점 오차 < 1 px, 재투영 RMS 0.19 px
- 수심: 관이 화면 중앙이면 왜곡 영향 작음 (보정 전/후 모두 0.6 mm 이내)
- 유속: 왜곡 무시 시 +2.7~+4.1 % → 보정 후 +0.14~+0.25 % (논문의 '최대 5~8 %' 와 같은 크기)

## 검증 결과 (1차 데모)

**합성 렌더링 (정답을 아는 장면, `tests/`)** — 내경 100 mm
| 항목 | 결과 |
|---|---|
| 수심, 완전투시(f 알 때) | 20개 자세(기울기 4–33°, roll ±10°)에서 오차 0.000 mm |
| 수심, 약투시(f 모를 때) | 평균 3.1 mm, 최대 9.0 mm. 카메라가 가깝고 많이 기울수록 커짐 |
| 자동검출 + 완전투시 | 오차 0.4 mm 이내 |
| 수면 추적 유속 | 0.10–0.40 m/s 및 역방향 흐름에서 오차 ≤ 3 % |
| Manning 수식 | 반관 D=100 mm, S=0.5 %, n=0.010 → 0.605 m/s (손계산과 일치) |

**업로드한 실제 사진 6장** (`examples/`, 내경 100 mm로 가정 — 당시는 두께 4 mm 입력 방식)
- 테두리 타원: 6/6 정상 검출
- 수면선 자동 검출: 4/6 정상(img1, 3, 4, 6). img2 는 랩 주름 선을 수면선으로 잘못 잡아서 수동 지정으로 보정해야 함 (`examples/img2_manual.jpg`), img5 는 판단이 애매함
- f 를 모르고(EXIF 없음) 카메라가 가깝고 20–36° 기울어 있어서, 약투시 오차가 수 mm 정도 있을 수 있음

## 정확도를 높이려면 (촬영 조건)
1. **끝단 랩 제거** 또는 평평한 투명창 사용: 랩 주름이 수면선 검출을 가장 많이 방해함
2. **물에 색소** 를 넣거나 **배경판**(단색) 사용: 노란 배경 사진은 대비 덕분에 검출이 잘 됨
3. **카메라 고정 + 초점거리 보정**: 체커보드로 한 번 캘리브레이션하거나, 크롭하지 않은 원본 사진의 EXIF 사용(`--exif`). 이렇게 하면 투시 오차가 사라짐
4. 정면에 가깝게, 멀리서 줌으로 촬영: 약투시 오차가 줄어듦
5. 유속 추적용 영상: 관 축보다 위에서 내려다보는 각도로, 수면에 미세 부유 입자 투입

## 촬영 권장 (유속)
- 카메라 고정, 3초 이상 녹화(60 fps 권장). 느린 흐름(< 0.05 m/s)은 프레임 수가 많아야 stride 를 늘릴 수 있음
- 수면 위 미세 부유 입자(추적자) 투입, 시선-수면 각 10° 이상
- 특허 참고: C-STIV 의 짝/홀수행 분할(`c_split="oddeven"`)은 등록특허 10-1512690 청구항. 기본값은 연속행 전체 상관

## 다음 단계
- 웹 데모(FastAPI + 업로드 페이지 + 수면선 드래그 보정 UI)
- 실제 수로(유량 알고 있는 조건)에서 H-STIV 표면유속 → 평균유속 환산계수(현재 0.85) 검증
- 실험 사진이 쌓이면 PyTorch 분할 모델(U-Net 등)로 수면 영역을 학습해 2단계(수면선 검출) 대체. 현재의 기하 계산(타원 → 복원 → h)은 그대로 사용
- 먼 쪽 끝단 타원까지 같이 써서 f 를 사진에서 자동 추정

## 파일 구성
```
pipeflow/calibration.py 체스보드 캘리브레이션, 왜곡 보정, K 스케일 (python -m pipeflow.calibration)
pipeflow/stiv.py        C-STIV / F-STIV / H-STIV, 정지무늬 제거, 인공 STI 생성
pipeflow/geometry.py    타원 피팅, conic 변환, affine 복원, 투시 원 자세 추정, EXIF f
pipeflow/detect.py      테두리 RANSAC (torch 배치), 단면 복원, 수면선 Radon 탐색
pipeflow/hydraulics.py  부분 충만 원관 단면, Manning/층류/Re/Fr
pipeflow/pipeline.py    measure_level / velocity_from_level / velocity_from_frames(_stiv) / draw_overlay
pipeflow/cli.py         명령행 데모
tests/                  합성 장면 렌더러 + 수위/유속/STIV/캘리브레이션 검증, results/ 에 실행 결과
```
