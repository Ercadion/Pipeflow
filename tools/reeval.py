"""
엔진 버전 비교 — 배포 전에 개발자가 PC 에서 실행 (앱 사용자에게는 보이지 않음)

준비: 앱에서 내보낸 측정 zip 들을 개발용 데이터 폴더 하나에 모아 둠 (원본·라벨은 앱 그대로)
  python tools/reeval.py devdata --add 내보낸zip들/*.zip
    - 같은 측정을 다시 가져오면 meta·결과·실측값 기록은 새 것으로, 이미 계산한 eval_*.json 은 유지

비교:
  # 1) 현재 배포 버전 엔진으로 기준값 만들기 (배포 버전을 git worktree 로 꺼내 그 엔진 경로 지정)
  git worktree add ../pipeflow_v0.3.5 <배포 커밋 또는 태그>
  python tools/reeval.py devdata --version 0.3.5 --engine ../pipeflow_v0.3.5/android-app/app/src/main/python --recent 30
  # 2) 개발 중인 엔진(이 작업 폴더)으로 재분석 + 기준과 비교
  python tools/reeval.py devdata --version 0.4.0-dev --baseline 0.3.5 --recent 30

  --baseline 을 안 주면: 다른 버전 eval 중 가장 최근 것, 없으면 촬영 당시 앱 자동 결과(result.json, 예전 형식은 result_auto.json)와 비교
  --recent N : 최근 N개 측정만 (처리량 제한, 0 = 전부)   --force : 이미 있는 eval_<버전>.json 도 다시 계산

결과:
  각 측정 폴더 eval_<버전>.json, 데이터 폴더 reeval_<버전>.json(전체)·reeval_<버전>.csv(측정별 표), 화면에 요약
"""
import argparse, glob, json, os, shutil, sys, tempfile, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pipeflow_reeval as RE            # noqa: E402

DEFAULT_ENGINE = os.path.join(HERE, "..", "android-app", "app", "src", "main", "python")


class _Quiet:
    def onProgress(self, msg):
        pass


def add_zips(data_dir, patterns):
    """zip → data_dir/<앱 저장 구조 그대로>/ (eval_*.json 은 유지, 나머지 파일은 zip 의 것으로 갱신)"""
    os.makedirs(data_dir, exist_ok=True)
    n_new = n_upd = 0
    for pat in patterns:
        for z in glob.glob(pat) or [pat]:
            tmp = tempfile.mkdtemp()
            try:
                with zipfile.ZipFile(z) as f:
                    f.extractall(tmp)
                for rel in RE.find_sessions(tmp):
                    src = os.path.join(tmp, rel)
                    dst = os.path.join(data_dir, rel)
                    new = not os.path.exists(dst)
                    os.makedirs(dst, exist_ok=True)
                    for fn in os.listdir(src):
                        if not fn.startswith("eval_") and os.path.isfile(os.path.join(src, fn)):
                            shutil.copy2(os.path.join(src, fn), os.path.join(dst, fn))
                    n_new += new
                    n_upd += (not new)
            finally:
                shutil.rmtree(tmp, ignore_errors=True)
    print(f"가져오기: 새 측정 {n_new}건, 갱신 {n_upd}건 → {data_dir}")


def main():
    ap = argparse.ArgumentParser(description="엔진 버전 비교 (개발자용)")
    ap.add_argument("data_dir", help="개발용 측정 데이터 폴더 (측정 폴더들이 들어 있음)")
    ap.add_argument("--add", nargs="+", metavar="ZIP", help="앱에서 내보낸 zip 을 데이터 폴더로 가져오기")
    ap.add_argument("--version", help="이번에 시험할 엔진 버전 이름 (예: 0.4.0-dev)")
    ap.add_argument("--engine", default=DEFAULT_ENGINE, help="엔진(Python) 폴더 — 기본: 이 작업 폴더의 앱 엔진")
    ap.add_argument("--baseline", help="비교 기준 버전 (그 버전의 eval 결과가 있어야 함)")
    ap.add_argument("--recent", type=int, default=20, help="최근 몇 건 (0 = 전부)")
    ap.add_argument("--force", action="store_true", help="이미 있는 eval_<버전>.json 도 다시 계산")
    a = ap.parse_args()

    if a.add:
        add_zips(a.data_dir, a.add)
    if not a.version:
        if not a.add:
            ap.error("--version 이 필요합니다")
        return

    sys.path.insert(0, os.path.abspath(a.engine))
    import pipeflow_app                     # 지정한 엔진

    names = RE.recent_sessions(a.data_dir, a.recent)
    print(f"{a.data_dir}: 최근 {len(names)}건 · 엔진 {a.version} ({os.path.abspath(os.path.dirname(pipeflow_app.__file__))})")
    t_all = time.time()
    for i, n in enumerate(names, 1):
        d = os.path.join(a.data_dir, n)
        if not a.force and not RE.needs_eval(d, a.version):
            print(f"  [{i}/{len(names)}] {RE.session_id(n)}: 이미 분석됨")
            continue
        t = time.time()
        r = json.loads(pipeflow_app.analyze(d, json.dumps(RE.auto_params(d, a.version)), _Quiet()))
        print(f"  [{i}/{len(names)}] {RE.session_id(n)}: {'성공' if r.get('ok') else '실패 ' + str(r.get('error'))} ({time.time() - t:.1f}s)")
    rep = RE.version_report(a.data_dir, a.version, names, a.recent, a.baseline)
    base = RE.save_report(a.data_dir, a.version, rep)
    print()
    print(rep["text"])
    print(f"\n보고서: {base}.json / {base}.csv  (총 {time.time() - t_all:.0f}s)")


if __name__ == "__main__":
    main()
