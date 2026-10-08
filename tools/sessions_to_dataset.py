"""
앱에서 내보낸 측정 데이터(zip 또는 세션 폴더) → 신경망 학습용 데이터셋으로 정리

사용:
  python tools/sessions_to_dataset.py exports/*.zip  --out dataset
  python tools/sessions_to_dataset.py devdata --out dataset      (폴더: 안의 측정을 모두 찾음, 날짜 폴더 구조 포함)
결과:
  dataset/images/<세션>.png        첫 프레임(회전 보정된 회색조)
  dataset/sti/<세션>_line<k>.npy   측정선별 시공간영상 (유속 모델용, --sti 옵션)
  dataset/index.csv               측정별 라벨: 테두리 타원, 수면선(자동 / 사람이 고친 / 실측 수심으로 만든), 수심, 유속, 메모
                                  + 자동 결과(auto_*) 와 사람이 고친 결과(final_*) 를 나란히

측정 폴더 읽기는 session_record.py (앱 v0.3.6 형식과 예전 형식 모두)
수면선 라벨 우선순위 (label_source)
  truth_depth  실측 수심 + (고친/자동) 결과의 카메라 자세로 계산한 수면선 (wl_truth, 완전투시일 때)
  corrected    저장 전 확인에서 사람이 고친 수면선
  auto_confirmed / final_confirmed / auto_confirmed_legacy   예전 형식의 '맞음' 표시
  auto_accepted   고치지 않고 저장한 자동 결과 (사람이 보고 저장했지만 확인 정도는 알 수 없음)
  auto_unverified 저장 전이거나 예전 형식에서 확인 표시 없음
"""
import argparse, csv, glob, json, os, re, shutil, sys, tempfile, zipfile

import math

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from session_record import read_session, waterline   # noqa: E402

ENGINE = os.path.join(HERE, "..", "android-app", "app", "src", "main", "python")


def wl_from_truth(d, meta, ref, truth_depth_mm):
    """
    실측 수심 → 영상 속 수면선 끝점 [x1,y1,x2,y2] (회전 보정 영상 px)
    ref(고친 결과 또는 자동 결과)의 테두리·수면선으로 카메라 자세(관 끝단 원의 3D 위치·기울기)를 구하고,
    수면 높이만 실측 수심으로 바꿔 투영: 끝점 = C + d·g ± half·u, d = r_in − h, half = √(r_in² − d²)
    초점거리(완전투시)가 없거나 계산이 안 되면 None
    """
    wl, ell = waterline(ref), ((ref or {}).get("overlay") or {}).get("ellipse")
    if wl is None or ell is None or truth_depth_mm is None:
        return None
    intr = meta.get("intrinsics") or {}
    if not intr.get("f_px"):
        return None
    if ENGINE not in sys.path:
        sys.path.insert(0, ENGINE)
    import pipeflow_app as PA
    from pipeflow_core.geometry import Ellipse, project
    from pipeflow_core.pipeline import measure_level
    st = meta["still"]
    rot = int(meta.get("rotation_degrees", 0))
    still = PA._rot_img(PA._load_y(os.path.join(d, st["file"]), st["width"], st["height"]), rot).astype(np.float64)
    principal = PA._rot_pt(intr.get("cx", st["width"] / 2), intr.get("cy", st["height"] / 2), st["width"], st["height"], rot)
    D = float((ref.get("level") or {}).get("diameter_mm") or meta.get("params", {}).get("diameter_mm", 100))
    lvl = measure_level(still, D, 0.0, rim_is="inner", ellipse=Ellipse.from_dict(ell),
                        waterline_pts=[[wl[0], wl[1]], [wl[2], wl[3]]], f_px=intr["f_px"], principal=principal,
                        camera_above=bool(meta.get("params", {}).get("camera_above", True)))
    if lvl.pose is None:
        return None
    r_in = D / 2
    dd = r_in - float(truth_depth_mm)
    if not -r_in < dd < r_in:
        return None
    P = lvl.pose
    half = math.sqrt(r_in ** 2 - dd ** 2)
    O = P["center"] + dd * P["g"]
    uv = project(P["K"], np.stack([O - half * P["u"], O + half * P["u"]]))
    a, b = uv[0], uv[1]
    # 참고 수면선과 같은 방향(왼→오른)으로
    if (b[0] - a[0]) * (wl[2] - wl[0]) + (b[1] - a[1]) * (wl[3] - wl[1]) < 0:
        a, b = b, a
    return [round(float(v), 2) for v in (a[0], a[1], b[0], b[1])]


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
    ap.add_argument("--no-truth-line", action="store_true", help="실측 수심 → 수면선 계산 생략 (빠름)")
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
        S = read_session(d)
        meta, auto, corr, ann, lab = S["meta"], S["auto"], S["corrected"], S["ann"], S["labels"]
        img = still_image(d, meta)
        Image.fromarray(img).save(os.path.join(a.out, "images", name + ".png"))
        fin = corr or auto or {}
        A, Fn = _summary(auto), _summary(fin)         # 자동 / 최종(고쳤으면 고친 결과)
        evs = sorted(glob.glob(os.path.join(d, "eval_*.json")), key=os.path.getmtime)
        er = json.load(open(evs[-1], encoding="utf-8")) if evs else None
        E = dict(ver=(er or {}).get("version"), depth=((er or {}).get("level") or {}).get("depth_mm"),
                 v=((er or {}).get("velocity_stiv") or {}).get("v_mean_mps"))
        ell = Fn["ell"] or {}
        wl_auto = A["wl"] or lab.get("auto_waterline") or []
        wl_corr = (Fn["wl"] if corr else None) or lab.get("manual_waterline") or lab.get("final_waterline")
        t_depth = ann.get("truth_depth_mm")
        wl_truth = None
        if t_depth is not None and not a.no_truth_line:
            try:
                wl_truth = wl_from_truth(d, meta, corr or auto, float(t_depth))
            except Exception as e:      # 계산 실패는 라벨 없이 계속
                print(f"  {name}: 실측 수심 수면선 계산 실패 ({type(e).__name__}: {e})")
        auto_fb = lab.get("auto_feedback", "")
        if wl_truth:
            src, wl_label = "truth_depth", wl_truth
        elif wl_corr:
            src, wl_label = "corrected", wl_corr
        elif auto_fb == "correct":
            src, wl_label = "auto_confirmed", wl_auto
        elif lab.get("feedback") == "correct":
            src, wl_label = "auto_confirmed_legacy", wl_auto
        elif S["record"] is not None:
            src, wl_label = "auto_accepted", wl_auto
        else:
            src, wl_label = "auto_unverified", wl_auto
        k = S["correction"] or {}
        def diff(x, y):
            return None if x is None or y is None else round(y - x, 3)
        lv = fin.get("level", {})
        rows.append(dict(
            session=name, app_version=meta.get("app_version") or ((re.search(r"_v([\w.\-]+)$", name) or [None, ""])[1]).split("_")[0],
            app_build=meta.get("app_build", ""), data_format=S["format"], integrity=S["integrity"], pending=S["pending"],
            image=f"images/{name}.png", width=img.shape[1], height=img.shape[0],
            diameter_mm=meta.get("params", {}).get("diameter_mm"),
            ell_cx=ell.get("cx"), ell_cy=ell.get("cy"), ell_a=ell.get("a"), ell_b=ell.get("b"), ell_phi_deg=ell.get("phi_deg"),
            ell_auto=json.dumps(A["ell"]) if auto else "",
            wl_auto=json.dumps(wl_auto), wl_corrected=json.dumps(wl_corr) if wl_corr else "",
            wl_truth=json.dumps(wl_truth) if wl_truth else "", wl_label=json.dumps(wl_label),
            label_source=src, corrected=corr is not None,
            corr_waterline=bool(k.get("waterline_pts")), corr_rim_redetect=bool(k.get("rim_redetect")),
            auto_feedback=auto_fb, final_feedback=lab.get("final_feedback", ""), feedback_legacy=lab.get("feedback", ""),
            note=ann.get("note", ""),
            auto_result_available=auto is not None, legacy_auto_result_lost=S["auto_lost"],
            # 자동 결과 vs 최종(고친) 결과
            auto_depth_mm=A["depth"], final_depth_mm=Fn["depth"], depth_change_mm=diff(A["depth"], Fn["depth"]),
            auto_v_surface_mps=A["v"], final_v_surface_mps=Fn["v"],
            auto_Q_Lps=A["q"], final_Q_Lps=Fn["q"], auto_Q_formula_Lps=A["q_formula"], final_Q_formula_Lps=Fn["q_formula"],
            # 실측 참값 · 가장 최근 버전 재분석(eval_*.json)
            truth_depth_mm=t_depth, truth_v_mean_mps=ann.get("truth_v_mean_mps"), truth_Q_Lps=ann.get("truth_Q_Lps"),
            auto_depth_err_mm=diff(t_depth, A["depth"]) if t_depth is not None else None,
            latest_eval_version=E["ver"], latest_eval_depth_mm=E["depth"], latest_eval_v_mean_mps=E["v"],
            depth_mm=Fn["depth"], method=lv.get("method"), rim_source=lv.get("rim_source"), inner_source=lv.get("inner_source"),
            v_surface_mps=Fn["v"], fps=(fin.get("velocity_stiv") or {}).get("fps"),
            f_px=(meta.get("intrinsics") or {}).get("f_px"), zoom_ratio=meta.get("zoom_ratio"), device=meta.get("device", ""),
            gravity=json.dumps(meta.get("gravity")),
            lat=(meta.get("location") or {}).get("lat"), lon=(meta.get("location") or {}).get("lon"),
            place=ann.get("place", "")))
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
