"""
정기 기록 (고정 설치) — Kotlin MonitorActivity 가 호출
  카메라를 고정해 두고 1·5·10분마다 사진으로 수위(수심)만 계산 → 경향성 확인용
  일관성: 시작할 때 한 번 찾은 관 내경 테두리(타원)를 모든 기록에 그대로 씀 (오차가 생겨도 매번 같은 방식)

calibrate(y_path, w, h, rot, params_json) -> JSON 문자열
    기준 사진 1장에서 관 내경 테두리 찾기 + 그때의 수심
    params: diameter_mm, intrinsics{f_px,cx,cy}, gravity, texture_sign, camera_above, attempt(다시 찾기 횟수), hint[x,y](회전 보정 좌표, 선택)
measure(y_paths_json, w, h, rot, params_json) -> JSON 문자열
    사진 여러 장(보통 3장)을 같은 테두리로 계산해 수심의 중앙값 (한 장 튀는 값 완화)
    params: calibrate 와 같음 + ellipse (calibrate 결과)
좌표는 모두 회전 보정(화면 세로 방향) 영상 기준
"""
from __future__ import annotations

import json
import math
import traceback

import numpy as np

from pipeflow_app import _expected_roll_deg, _load_y, _rot_img, _rot_pt
from pipeflow_core.geometry import Ellipse
from pipeflow_core.pipeline import measure_level


def _prep(y_path, w, h, rot, p):
    still = _rot_img(_load_y(y_path, w, h), rot).astype(np.float64)
    intr = p.get("intrinsics") or {}
    f_px = intr.get("f_px")
    principal = None
    if f_px:
        principal = _rot_pt(intr.get("cx", w / 2), intr.get("cy", h / 2), w, h, rot)
    roll = _expected_roll_deg(p.get("gravity"))
    wk = dict(texture_sign=int(p.get("texture_sign", 1)))
    if roll is not None:
        wk.update(theta_center_deg=roll, max_angle_deg=12.0)
    return still, f_px, principal, wk


def _level(still, D, ell, f_px, principal, wk, p):
    lvl = measure_level(still, D, 0.0, rim_is="inner", ellipse=ell, f_px=f_px, principal=principal,
                        camera_above=bool(p.get("camera_above", True)), waterline_kwargs=wk)
    s = lvl.summary()
    return dict(depth_mm=s["depth_mm"], fill_ratio=s["fill_ratio"], roll_deg=s["roll_deg"],
                tilt_deg=s["tilt_deg"], method=s["method"], confidence=s.get("waterline_confidence"),
                waterline=np.round(lvl.waterline_img, 2).tolist(), ellipse=lvl.ellipse.to_dict(),
                warnings=s.get("warnings", []))


def calibrate(y_path, w, h, rot, params_json="{}"):
    out = dict(ok=False)
    try:
        p = json.loads(params_json or "{}")
        D = float(p.get("diameter_mm", 100))
        still, f_px, principal, wk = _prep(y_path, int(w), int(h), int(rot), p)
        from pipeflow_core.detect import detect_rim, select_inner_rim
        attempt = int(p.get("attempt", 0))
        hint = p.get("hint")
        seeds = [attempt * 3 + k for k in range(3)] if hint else [attempt]
        cands = []
        for sd in seeds:
            try:
                e, _ = detect_rim(still, seed=sd)
                cands.append(e)
            except Exception:
                pass
        if not cands:
            raise RuntimeError("관 테두리를 찾지 못함 — 관 끝단이 화면 가운데 크게 보이게 하고 다시 찾기")
        if hint:   # 탭한 위치(관 안쪽)에 중심이 가장 가까운 테두리
            hx, hy = float(hint[0]), float(hint[1])
            cands.sort(key=lambda e: math.hypot(e.cx - hx, e.cy - hy))
        ell, info = select_inner_rim(still, cands[0], f_px=f_px, principal=principal)
        r = _level(still, D, ell, f_px, principal, wk, p)
        out.update(r, ok=True, inner_source=info.get("inner_source"), width=still.shape[1], height=still.shape[0])
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"
        out["traceback"] = traceback.format_exc()
    return json.dumps(out, ensure_ascii=False, default=float)


def measure(y_paths_json, w, h, rot, params_json="{}"):
    out = dict(ok=False, frames=[])
    try:
        p = json.loads(params_json or "{}")
        D = float(p.get("diameter_mm", 100))
        ell = Ellipse.from_dict(p["ellipse"])
        res = []
        for path in json.loads(y_paths_json):
            try:
                still, f_px, principal, wk = _prep(path, int(w), int(h), int(rot), p)
                r = _level(still, D, ell, f_px, principal, wk, p)
                r["brightness"] = round(float(still.mean()), 1)
                res.append(r)
                out["frames"].append(dict(ok=True, depth_mm=r["depth_mm"], confidence=r["confidence"]))
            except Exception as e:
                out["frames"].append(dict(ok=False, error=f"{type(e).__name__}: {e}"))
        if not res:
            raise RuntimeError("모든 사진에서 수면선을 찾지 못함")
        depths = [r["depth_mm"] for r in res]
        k = int(np.argsort(depths)[len(depths) // 2])        # 중앙값 사진
        m = res[k]
        out.update(ok=True, median_index=[i for i, f in enumerate(out["frames"]) if f["ok"]][k],
                   depth_mm=m["depth_mm"], fill_ratio=m["fill_ratio"], roll_deg=m["roll_deg"],
                   confidence=m["confidence"], waterline=m["waterline"], brightness=m["brightness"],
                   spread_mm=round(max(depths) - min(depths), 2), n_ok=len(res), warnings=m["warnings"])
    except Exception as e:
        out["error"] = f"{type(e).__name__}: {e}"
        out["traceback"] = traceback.format_exc()
    return json.dumps(out, ensure_ascii=False, default=float)
