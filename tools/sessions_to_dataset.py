"""
앱에서 내보낸 측정 데이터(zip 또는 세션 폴더) → 신경망 학습용 데이터셋으로 정리

사용:
  python tools/sessions_to_dataset.py exports/*.zip  --out dataset
결과:
  dataset/images/<세션>.png        첫 프레임(회전 보정된 회색조)
  dataset/sti/<세션>_line<k>.npy   측정선별 시공간영상 (유속 모델용, --sti 옵션)
  dataset/index.csv               세션별 라벨 (테두리 타원, 수면선 자동/수동, 수심, 유속, 피드백, 메모)

라벨 우선순위: 사용자가 수정한 수면선(manual_waterline) > 자동 결과 + feedback=correct
"""
import argparse, csv, glob, json, os, shutil, sys, tempfile, zipfile

import numpy as np
from PIL import Image


def load_session(d):
    meta = json.load(open(os.path.join(d, "meta.json"), encoding="utf-8"))
    res = json.load(open(os.path.join(d, "result.json"), encoding="utf-8")) if os.path.exists(os.path.join(d, "result.json")) else {}
    lab = json.load(open(os.path.join(d, "labels.json"), encoding="utf-8")) if os.path.exists(os.path.join(d, "labels.json")) else {}
    return meta, res, lab


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
    paths = []
    for p in a.inputs:
        for q in glob.glob(p) or [p]:
            if q.endswith(".zip"):
                with zipfile.ZipFile(q) as z:
                    z.extractall(tmp)
                    paths += sorted({os.path.join(tmp, n.split("/")[0]) for n in z.namelist()})
            elif os.path.isdir(q):
                paths.append(q)
    for d in paths:
        if not os.path.exists(os.path.join(d, "meta.json")):
            continue
        name = os.path.basename(d.rstrip("/"))
        meta, res, lab = load_session(d)
        img = still_image(d, meta)
        Image.fromarray(img).save(os.path.join(a.out, "images", name + ".png"))
        ov = res.get("overlay", {})
        ell = ov.get("ellipse", {})
        wl_auto = lab.get("auto_waterline") or sum(ov.get("waterline", []), [])
        wl_final = lab.get("manual_waterline") or lab.get("final_waterline") or wl_auto
        lv = res.get("level", {})
        st = res.get("velocity_stiv", {})
        rows.append(dict(
            session=name, image=f"images/{name}.png", width=img.shape[1], height=img.shape[0],
            diameter_mm=meta.get("params", {}).get("diameter_mm"), wall_mm=meta.get("params", {}).get("wall_mm"),
            ell_cx=ell.get("cx"), ell_cy=ell.get("cy"), ell_a=ell.get("a"), ell_b=ell.get("b"), ell_phi_deg=ell.get("phi_deg"),
            wl_auto=json.dumps(wl_auto), wl_label=json.dumps(wl_final),
            label_source="manual" if lab.get("manual_waterline") else ("auto_confirmed" if lab.get("feedback") == "correct" else "auto_unverified"),
            feedback=lab.get("feedback", ""), note=lab.get("note", ""),
            depth_mm=lv.get("depth_mm"), method=lv.get("method"),
            v_surface_mps=st.get("v_surface_mps"), fps=st.get("fps"),
            f_px=(meta.get("intrinsics") or {}).get("f_px"), device=meta.get("device", ""),
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
