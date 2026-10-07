"""
앱에서 내보낸 측정 데이터(zip 또는 세션 폴더) → 신경망 학습용 데이터셋으로 정리

사용:
  python tools/sessions_to_dataset.py exports/*.zip  --out dataset
  python tools/sessions_to_dataset.py devdata --out dataset      (폴더: 안의 측정을 모두 찾음, 날짜 폴더 구조 포함)
결과:
  dataset/images/<세션>.png        첫 프레임(회전 보정된 회색조)
  dataset/sti/<세션>_line<k>.npy   측정선별 시공간영상 (유속 모델용, --sti 옵션)
  dataset/index.csv               세션별 라벨 (테두리 타원, 수면선 자동/수동, 수심, 유속, 피드백, 메모)
                                  + 처음 자동 결과(auto_*) 와 사람 수정 후 최종 결과(final_*) 를 나란히

세션 파일 (앱 v0.3.4~)
  result_auto.json  처음 자동 결과 (사람 수정 전, 고정)    result.json  가장 최근(최종) 결과
  runs.json         분석 회차 기록                         labels.json  auto_feedback / final_feedback / edits / truth_* / 메모
  eval_<버전>.json  엔진 버전별 재분석 결과 (개발자 PC 의 tools/reeval.py)
라벨 우선순위: 사용자가 수정한 수면선(manual_waterline) > 자동 결과 + auto_feedback=correct
(예전 세션의 feedback 은 '자동' 인지 '최종' 인지 모호 → feedback_legacy 열에 그대로)
"""
import argparse, csv, glob, json, os, re, shutil, sys, tempfile, zipfile

import numpy as np
from PIL import Image


def load_session(d):
    meta = json.load(open(os.path.join(d, "meta.json"), encoding="utf-8"))
    res = json.load(open(os.path.join(d, "result.json"), encoding="utf-8")) if os.path.exists(os.path.join(d, "result.json")) else {}
    lab = json.load(open(os.path.join(d, "labels.json"), encoding="utf-8")) if os.path.exists(os.path.join(d, "labels.json")) else {}
    p_auto = os.path.join(d, "result_auto.json")
    auto = json.load(open(p_auto, encoding="utf-8")) if os.path.exists(p_auto) else None
    p_runs = os.path.join(d, "runs.json")
    runs = json.load(open(p_runs, encoding="utf-8")) if os.path.exists(p_runs) else []
    return meta, res, lab, auto, runs


def _summary(r):
    """결과 json → (수심, 표면유속, STIV 유량, 등류공식 유량, 수면선 4값, 타원)"""
    if not r or not r.get("ok", True):
        return dict(depth=None, v=None, q=None, q_formula=None, wl=None, ell=None)
    lv, st, vf, ov = r.get("level", {}), r.get("velocity_stiv", {}), r.get("velocity_formula", {}), r.get("overlay", {})
    return dict(depth=lv.get("depth_mm"), v=st.get("v_surface_mps"), q=st.get("Q_Lps"), q_formula=vf.get("Q_Lps"),
                wl=sum(ov.get("waterline", []), []) or None, ell=ov.get("ellipse"))


def still_image(d, meta):
    st = meta["still"]
    a = np.fromfile(os.path.join(d, st["file"]), np.uint8)[: st["width"] * st["height"]].reshape(st["height"], st["width"])
    k = (int(meta.get("rotation_degrees", 0)) // 90) % 4
    return np.rot90(a, -k) if k else a


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--out", default="dataset")
    ap.add_argument("--sti", action="store_true", help="측정선별 STI 도 저장 (pipeflow_core 필요)")
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    rows = []
    tmp = tempfile.mkdtemp()
    paths = []          # (측정 폴더 경로, 측정 이름)

    def collect(base):
        for dp, dns, fns in os.walk(base):
            if "meta.json" in fns:
                rel = os.path.relpath(dp, base).replace(os.sep, "/")
                paths.append((dp, (rel if rel != "." else os.path.basename(dp.rstrip("/"))).replace("/", "_")))
                dns[:] = []

    for p in a.inputs:
        for q in glob.glob(p) or [p]:
            if q.endswith(".zip"):
                sub = tempfile.mkdtemp(dir=tmp)
                with zipfile.ZipFile(q) as z:
                    z.extractall(sub)
                collect(sub)
            elif os.path.isdir(q):
                if os.path.isfile(os.path.join(q, "meta.json")):
                    paths.append((q, os.path.basename(q.rstrip("/"))))
                else:
                    collect(q)
    seen = set()
    for d, name in paths:
        if name in seen:
            continue
        seen.add(name)
        meta, res, lab, auto, runs = load_session(d)
        img = still_image(d, meta)
        Image.fromarray(img).save(os.path.join(a.out, "images", name + ".png"))
        if auto is None and not lab.get("manual_waterline") and not lab.get("edits") \
                and not lab.get("legacy_auto_result_lost") and res.get("level", {}).get("rim_source") != "manual_or_previous":
            auto = res                                 # 예전 세션: 수정 흔적이 없으면 현재 결과 = 자동 결과
        A, Fn = _summary(auto), _summary(res)       # 처음 자동 / 최종
        evs = sorted(glob.glob(os.path.join(d, "eval_*.json")), key=os.path.getmtime)
        er = json.load(open(evs[-1], encoding="utf-8")) if evs else None
        E = dict(ver=(er or {}).get("version"), depth=((er or {}).get("level") or {}).get("depth_mm"),
                 v=((er or {}).get("velocity_stiv") or {}).get("v_mean_mps"))
        ell = Fn["ell"] or {}
        wl_auto = (A["wl"] if auto else None) or lab.get("auto_waterline") or Fn["wl"] or []
        wl_final = lab.get("manual_waterline") or lab.get("final_waterline") or Fn["wl"] or wl_auto
        edits = lab.get("edits", [])
        changed = [e for e in edits if e.get("changed", True)]
        edited = bool(lab.get("manual_waterline")) or bool(changed)
        auto_fb = lab.get("auto_feedback", "")
        if lab.get("manual_waterline"):
            src = "manual"
        elif auto_fb == "correct":
            src = "auto_confirmed"
        elif edited and lab.get("final_feedback") == "correct":
            src = "final_confirmed"
        elif not edited and lab.get("feedback") == "correct":
            src = "auto_confirmed_legacy"
        else:
            src = "auto_unverified"
        def diff(x, y):
            return None if x is None or y is None else round(y - x, 3)
        lv = res.get("level", {})
        rows.append(dict(
            session=name, app_version=meta.get("app_version") or ((re.search(r"_v([\w.\-]+)$", name) or [None, ""])[1]).split("_")[0],
            app_build=meta.get("app_build", ""),
            image=f"images/{name}.png", width=img.shape[1], height=img.shape[0],
            diameter_mm=meta.get("params", {}).get("diameter_mm"),
            ell_cx=ell.get("cx"), ell_cy=ell.get("cy"), ell_a=ell.get("a"), ell_b=ell.get("b"), ell_phi_deg=ell.get("phi_deg"),
            ell_auto=json.dumps(A["ell"]) if auto else "",
            wl_auto=json.dumps(wl_auto), wl_label=json.dumps(wl_final),
            label_source=src, edited=edited,
            edit_types="|".join(sorted({e.get("type", "") for e in changed})), n_runs=len(runs),
            auto_feedback=auto_fb, final_feedback=lab.get("final_feedback", ""), feedback_legacy=lab.get("feedback", ""),
            note=lab.get("note", ""),
            auto_result_available=auto is not None, legacy_auto_result_lost=bool(lab.get("legacy_auto_result_lost")),
            # 처음 자동 결과 vs 사람 수정 후 최종 결과
            auto_depth_mm=A["depth"], final_depth_mm=Fn["depth"], depth_change_mm=diff(A["depth"], Fn["depth"]),
            auto_v_surface_mps=A["v"], final_v_surface_mps=Fn["v"],
            auto_Q_Lps=A["q"], final_Q_Lps=Fn["q"], auto_Q_formula_Lps=A["q_formula"], final_Q_formula_Lps=Fn["q_formula"],
            # 실측 참값 (결과 화면 입력) · 가장 최근 버전 재분석(eval_*.json)
            truth_depth_mm=lab.get("truth_depth_mm"), truth_v_mean_mps=lab.get("truth_v_mean_mps"), truth_Q_Lps=lab.get("truth_Q_Lps"),
            latest_eval_version=E["ver"], latest_eval_depth_mm=E["depth"], latest_eval_v_mean_mps=E["v"],
            depth_mm=Fn["depth"], method=lv.get("method"), rim_source=lv.get("rim_source"), inner_source=lv.get("inner_source"),
            v_surface_mps=Fn["v"], fps=res.get("velocity_stiv", {}).get("fps"),
            f_px=(meta.get("intrinsics") or {}).get("f_px"), zoom_ratio=meta.get("zoom_ratio"), device=meta.get("device", ""),
            gravity=json.dumps(meta.get("gravity"))))
    if rows:
        with open(os.path.join(a.out, "index.csv"), "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader(); w.writerows(rows)
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"{len(rows)} 세션 → {a.out}/index.csv")
    by = {}
    for r in rows:
        by[r["label_source"]] = by.get(r["label_source"], 0) + 1
    print("라벨 출처:", by)


if __name__ == "__main__":
    main()
