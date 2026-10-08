# 개발자 도구 (PC)

앱 사용자에게는 보이지 않는, 배포 전 점검·학습 데이터 정리용 도구입니다.

## 준비 (한 번)
- Python 3.10 이상, `pip install numpy pillow openpyxl`
- 이 저장소(pipeflow) 폴더에서 실행 — 엔진은 앱과 같은 코드(`android-app/app/src/main/python`)를 그대로 씀

## 1. 측정 데이터 모으기
1. 폰: 앱 메뉴 **측정 데이터** → 측정 체크 → **선택 내보내기 (zip)** → 드라이브·메일·메신저 등으로 PC 에 보냄
   - zip 안: 앱 저장 구조 그대로 `<촬영날짜>/<촬영시각_v앱버전>/` (예: `20261007/192009_v0.3.5/`). 측정 이름은 `20261007_192009_v0.3.5`
2. PC: 개발용 데이터 폴더(예: `devdata`)로 가져오기
   ```
   python tools/reeval.py devdata --add 받은zip들/*.zip
   ```
   - 같은 측정을 다시 가져오면 실측값 기록 등은 새 것으로 갱신, 이미 계산한 점검 결과(`eval_*.json`)는 유지
   - 측정 폴더 읽기는 `tools/session_record.py` 한 곳 (앱 새 형식 `result.json`/`result_corrected.json`/`annotations.json`/`record.json` 과 예전 형식 모두)
   - `devdata` 는 저장소에 커밋하지 않는 것을 권장 (원본 영상이 큼)

## 2. 배포 전 엔진 점검 (새 엔진 vs 현재 배포 엔진)
```
# (a) 현재 배포 버전 엔진으로 기준값 — 배포 커밋을 별도 폴더로 꺼내서 그 엔진 지정 (한 번 만들면 재사용)
git worktree add ../pipeflow_v0.3.5 <배포 커밋 해시 또는 태그>
python tools/reeval.py devdata --version 0.3.5 --engine ../pipeflow_v0.3.5/android-app/app/src/main/python --recent 30

# (b) 지금 작업 중인 엔진으로 같은 측정을 다시 분석 + 기준과 비교
python tools/reeval.py devdata --version 0.4.0-dev --baseline 0.3.5 --recent 30
```
- `--recent N`: 최근 N개 측정만 (처리량 제한, 0 = 전부). 측정 1건당 수 초
- `--force`: 이미 있는 같은 버전 결과도 다시 계산 (엔진을 고친 뒤 같은 버전 이름으로 다시 돌릴 때)
- `--baseline` 생략 시: 다른 버전의 가장 최근 점검 결과, 없으면 촬영 당시 앱 자동 결과(`result.json`, 예전 형식은 `result_auto.json`)와 비교
- 재분석은 **순수 자동**(사람이 고친 수면선·테두리는 안 씀, 촬영 때 포착한 타원·설정은 씀) — 측정 폴더에 `eval_<버전>.json`, 원본·기존 결과는 그대로

## 3. 결과 읽기
화면 요약 + `devdata/reeval_<버전>.json`(전체) + `devdata/reeval_<버전>.csv`(측정별 표, 엑셀로 열기)
- **참값이 있는 측정** (실측값 입력 > 저장 전 확인에서 사람이 고친 결과 > 예전 형식의 '처음 자동 결과 맞음' 표시)
  - 고치지 않고 저장한 자동 결과는 참값으로 쓰지 않음 (기준 엔진에 유리해지므로)
  - 측정별 **개선 / 악화 / 같음** (수심 오차 변화 0.5 mm 기준)
  - 평균 절대오차: 기준 엔진 → 시험 엔진 (수심, 평균유속)
- **참값이 없는 측정**: 결과가 **바뀜 / 같음** 만 (수심 1 mm, 유속 5% 기준) — 바뀐 측정은 직접 사진을 보고 판단
- **나빠진 측정 상위 5개**: 배포 전에 꼭 확인 (원인 수정 → `--force` 로 다시)
- "시험 엔진 실패": 새 엔진이 분석 자체를 못 한 측정 — 예외 메시지가 함께 표시됨

배포 판단 예: 참값 있는 측정의 평균 오차가 줄고, 악화·실패가 없거나 설명 가능하면 배포.

## 4. 엑셀 (회사 분석용)
앱의 '엑셀로 내보내기' 와 같은 코드라 양식이 똑같음. 모아 둔 측정 전체를 엑셀 하나로:
```
pip install openpyxl
python tools/export_excel.py devdata --out measurements.xlsx
python tools/export_excel.py devdata 받은zip들/*.zip --out all.xlsx --csv csv폴더
python tools/export_excel.py devdata --since 20261001 --until 20261031 --out 10월.xlsx
```
- 같은 측정ID 는 한 번만. 열 설명은 엑셀의 '데이터사전' 시트

## 5. 학습 데이터 표
```
python tools/sessions_to_dataset.py devdata --out dataset
```
→ `dataset/images/*.png` + `dataset/index.csv`
- 열: 앱 버전·커밋, `data_format`, `integrity`(record.json 의 SHA-256 와 비교: ok / mismatch:<파일> / no_record), 테두리, 수면선 `wl_auto` / `wl_corrected` / `wl_truth` / `wl_label`, 자동 vs 최종 수심·유속·유량, 실측값, `auto_depth_err_mm`, 최신 점검 결과
- **`wl_truth`**: 실측 수심이 있으면, 고친(없으면 자동) 결과의 테두리·수면선으로 카메라 자세를 구한 뒤 수면 높이만 실측 수심으로 바꿔 영상에 투영한 수면선 (초점거리 있는 측정만). 학습 라벨 1순위
- `label_source`: truth_depth > corrected > (예전) auto_confirmed·final_confirmed > auto_accepted(고치지 않고 저장) > auto_unverified(저장 전 등)
- `--no-truth-line`: wl_truth 계산 생략 (측정당 수 초 절약)
