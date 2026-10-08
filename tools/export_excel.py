"""
측정 데이터 → 엑셀(.xlsx) — PC 에서 모아 둔 측정 전체(또는 일부)를 회사 분석용 엑셀 하나로
앱의 '엑셀로 내보내기' 와 같은 코드(android-app/app/src/main/python/pipeflow_export.py)라 양식이 똑같음

  pip install numpy openpyxl
  python tools/export_excel.py devdata --out measurements.xlsx            # devdata 안의 측정 전부
  python tools/export_excel.py devdata 받은zip들/*.zip --out all.xlsx --csv csv폴더
  python tools/export_excel.py devdata --since 20261001 --until 20261031 --out 10월.xlsx

입력: 측정 폴더가 들어 있는 폴더(날짜 폴더 구조 포함) 또는 앱에서 내보낸 zip. 같은 측정ID 는 한 번만
"""
import argparse, glob, os, re, shutil, sys, tempfile, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(HERE, "..", "android-app", "app", "src", "main", "python")
sys.path.insert(0, ENGINE)
import pipeflow_export as PE      # noqa: E402


def find(base):
    out = []
    for dp, dns, fns in os.walk(base):
        if "meta.json" in fns:
            out.append(dp)
            dns[:] = []
        dns.sort()
    return out


def main():
    ap = argparse.ArgumentParser(description="측정 데이터 → 엑셀")
    ap.add_argument("inputs", nargs="+", help="측정 데이터 폴더 또는 앱에서 내보낸 zip")
    ap.add_argument("--out", default="measurements.xlsx")
    ap.add_argument("--csv", help="시트별 CSV 도 저장할 폴더")
    ap.add_argument("--since", help="이 날짜(yyyyMMdd)부터")
    ap.add_argument("--until", help="이 날짜(yyyyMMdd)까지")
    a = ap.parse_args()
    tmp = tempfile.mkdtemp()
    try:
        dirs = {}
        for p in a.inputs:
            for q in glob.glob(p) or [p]:
                if q.lower().endswith(".zip"):
                    sub = tempfile.mkdtemp(dir=tmp)
                    with zipfile.ZipFile(q) as z:
                        z.extractall(sub)
                    cands = find(sub)
                elif os.path.isdir(q):
                    cands = find(q)
                else:
                    print(f"건너뜀: {q}")
                    continue
                for d in cands:
                    sid, _ = PE.session_id(d)
                    day = (re.match(r"(\d{8})", sid) or [None, ""])[1]
                    if (a.since and day < a.since) or (a.until and day > a.until):
                        continue
                    dirs.setdefault(sid, d)       # 같은 측정은 처음 것만
        if not dirs:
            sys.exit("측정을 찾지 못했습니다")
        res = PE.write_xlsx(list(dirs.values()), a.out, created_by="PC tools/export_excel.py", csv_dir=a.csv)
        print(f"측정 {res['n']}건 → {a.out}" + (f" (+ CSV {a.csv})" if a.csv else ""))
        for e in res["errors"]:
            print("  읽지 못함:", e)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
