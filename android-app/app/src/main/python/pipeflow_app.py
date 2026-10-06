"""
안드로이드 앱 ↔ 계산 엔진 연결부 (Chaquopy 에서 Kotlin 이 호출)

세션 폴더 구조 (Kotlin CaptureActivity 가 작성)
  meta.json   촬영 정보 (해상도, 회전, 타임스탬프, 카메라 내부파라미터, 중력벡터, 설정값)
  still.y     첫 프레임 Y(밝기) 평면, 원 해상도, uint8 (센서 방향 그대로)
  frames.y    연속 프레임 Y 평면 (T × h × w, uint8, 1/2 해상도)
  result.json 분석 결과 (이 모듈이 작성)
  labels.json 사용자 확인/수정 내용 (Kotlin 이 작성) — 향후 신경망 학습 데이터

analyze(session_dir, params_json, listener) -> result JSON 문자열
"""
from __future__ import annotations

import json
import math
import os
import time
import traceback

import numpy as np

from pipeflow_core import hydraulics as hyd
from pipeflow_core.geometry import Ellipse
from pipeflow_core.pipeline import (LevelResult, measure_level, velocity_from_frames_stiv,
                                    velocity_from_level)


def _say(listener, msg):
    if listener is not None:
        try:
            listener.onProgress(msg)
        except Exception:
            pass


def _rot_img(img, deg):
    k = (int(deg) // 90) % 4
    return np.ascontiguousarray(np.rot90(img, -k)) if k else img


def _rot_pt(x, y, W, H, deg):
    """센서 방향 (W×H) 좌표 -> 시계방향 deg 회전한 영상 좌표."""
    k = (int(deg) // 90) % 4
    if k == 0:
        return x, y
    if k == 1:
        return H - 1 - y, x
    if k == 2:
        return W - 1 - x, H - 1 - y
    return y, W - 1 - x


def _load_y(path, w, h, count=None):
    a = np.fromfile(path, dtype=np.uint8)
    if count is None:
        return a[: w * h].reshape(h, w)
    n = min(count, a.size // (w * h))
    return a[: n * w * h].reshape(n, h, w)


def _expected_roll_deg(gravity):
    """중력 센서값(장치 좌표, 세로 화면 기준)으로 영상 안의 '아래' 방향 각도."""
    if not gravity or len(gravity) < 3:
        return None
    gx, gy, gz = gravity
    dx, dy = -gx, gy
    if math.hypot(dx, dy) < 2.0:          # 폰이 거의 수평(바닥/천장을 봄) -> 신뢰 낮음
        return None
    return math.degrees(math.atan2(dx, dy))


def analyze(session_dir, params_json="{}", listener=None):
    t0 = time.time()
    out = dict(ok=False, version="0.3.2")
    try:
        p = json.loads(params_json) if params_json else {}
        with open(os.path.join(session_dir, "meta.json"), encoding="utf-8") as f:
            meta = json.load(f)
        out["session"] = os.path.basename(session_dir.rstrip("/"))
        D = float(p.get("diameter_mm", meta.get("params", {}).get("diameter_mm", 100)))
        rot = int(meta.get("rotation_degrees", 0))

        # ---- 1) 수위 (첫 프레임, 원 해상도) -------------------------------------
        _say(listener, "수위 계산: 관 테두리 검출 중…")
        st = meta["still"]
        still = _rot_img(_load_y(os.path.join(session_dir, st["file"]), st["width"], st["height"]), rot)
        Hs, Ws = still.shape
        intr = meta.get("intrinsics") or {}
        f_px = intr.get("f_px")
        principal = None
        if f_px:
            cx, cy = intr.get("cx", st["width"] / 2), intr.get("cy", st["height"] / 2)
            principal = _rot_pt(cx, cy, st["width"], st["height"], rot)
        roll = _expected_roll_deg(meta.get("gravity"))
        wk = dict(texture_sign=int(p.get("texture_sign", 1)))
        if roll is not None and p.get("use_gravity", True):
            wk.update(theta_center_deg=roll, max_angle_deg=12.0)
        wl_pts = p.get("waterline_pts")
        ell = Ellipse.from_dict(p["ellipse"]) if p.get("ellipse") else None
        rim_source = "manual_or_previous" if ell is not None else "auto_ransac"
        live = meta.get("live_ellipse")
        if ell is None and live and p.get("use_live_ellipse", True):
            # 촬영 화면에서 실시간으로 잡은 타원(회전 보정된 still 좌표)을 원 해상도에서 정밀화
            from pipeflow_core.detect import refine_rim_from_seed
            _say(listener, "수위 계산: 화면에서 포착한 테두리 정밀화 중…")
            ell, rinfo = refine_rim_from_seed(still.astype(np.float64), Ellipse.from_dict(live))
            rim_source = "live_seed"
        inner_source = None
        if ell is None:
            from pipeflow_core.detect import detect_rim
            ell, _ = detect_rim(still.astype(np.float64))
        if rim_source != "manual_or_previous":
            # 관 두께 입력 없이 내경 테두리 선택 (끝단 면의 바깥/안쪽 동심 테두리 중 안쪽)
            from pipeflow_core.detect import select_inner_rim
            _say(listener, "수위 계산: 관 내경 테두리 확인 중…")
            ell, iinfo = select_inner_rim(still.astype(np.float64), ell, f_px=f_px, principal=principal)
            inner_source = iinfo.get("inner_source")
        lvl = measure_level(still.astype(np.float64), D, 0.0, rim_is="inner", ellipse=ell, waterline_pts=wl_pts,
                            f_px=f_px, principal=principal,
                            camera_above=bool(p.get("camera_above", True)), waterline_kwargs=wk)
        out["level"] = lvl.summary()
        out["level"]["expected_roll_deg"] = None if roll is None else round(roll, 1)
        out["level"]["rim_source"] = rim_source
        out["level"]["inner_source"] = inner_source
        out["overlay"] = dict(width=Ws, height=Hs, ellipse=lvl.ellipse.to_dict(),
                              waterline=np.round(lvl.waterline_img, 2).tolist(),
                              rotation_degrees=rot)
        out["t_level_s"] = round(time.time() - t0, 2)

        # ---- 2) 등류 공식 유속 ------------------------------------------------
        Re = None
        slope = p.get("slope")
        if slope:
            v = velocity_from_level(lvl, float(slope), p.get("manning_n"), p.get("material", "glass"),
                                    p.get("regime", "auto"))
            Re = v.get("Re")
            out["velocity_formula"] = dict(V_mean_mps=v["V"], Q_Lps=v["Q"] * 1000, regime=v["regime"],
                                           Re=v.get("Re"), Fr=v.get("Fr"),
                                           Rh_mm=v["section"]["Rh"] * 1000,
                                           area_mm2=v["section"]["A"] * 1e6,
                                           warnings=v.get("warnings", []))

        # ---- 3) H-STIV 표면유속 -------------------------------------------------
        fr = meta.get("frames")
        if fr and fr.get("count", 0) >= 24 and lvl.pose is not None:
            _say(listener, "유속 계산: 시공간영상 생성 중…")
            frames = _load_y(os.path.join(session_dir, fr["file"]), fr["width"], fr["height"], fr["count"])
            frames = [_rot_img(f, rot).astype(np.float64) for f in frames]
            hf, wf = frames[0].shape
            sf = wf / Ws
            # 프레임 해상도용 수위 결과 (3D 자세는 같고, 영상 좌표·K 만 축소)
            lf = LevelResult(**{k: v for k, v in lvl.__dict__.items()})
            lf.ellipse = lvl.ellipse.scaled(sf)
            lf.waterline_img = lvl.waterline_img * sf
            lf.extra = dict(lvl.extra, image_size=(wf, hf))
            pose = dict(lvl.pose)
            # 2x2 평균 축소 좌표 관계: x_half = (x_full + 0.5)·sf − 0.5
            o = 0.5 * sf - 0.5
            pose["K"] = np.array([[sf, 0, o], [0, sf, o], [0, 0, 1.0]]) @ lvl.pose["K"]
            lf.pose = pose
            ts = np.asarray(fr.get("timestamps_ns", []), np.float64)[: len(frames)] * 1e-9
            times = ts if len(ts) == len(frames) else None
            dt = float(np.median(np.diff(ts))) if times is not None else 1.0 / float(fr.get("fps", 30))
            _say(listener, "유속 계산: H-STIV 분석 중…")
            # top-view 해상도: 끝단에서 영상 1px 에 해당하는 길이(mm) 정도로 (과도한 업샘플 방지)
            mm_per_px = lf.extra["R_rim_mm"] / lf.ellipse.a
            res_mm = float(p.get("res_mm") or min(1.5, max(0.3, mm_per_px)))
            stv = velocity_from_frames_stiv(frames, dt, lf, times=times, Re_hint=Re, res_mm=res_mm,
                                            n_lines=int(p.get("n_lines", 5)))
            out["velocity_stiv"] = dict(
                v_surface_mps=stv["v_surface"], v_mean_mps=stv["v_mean"], coef=stv["coef"],
                stride=stv["stride"], res_mm=res_mm, dt_s=stv["dt"], fps=1.0 / stv["dt"], resampled=stv["resampled"],
                graze_deg=stv["graze_deg"], spread_mps=stv["spread"],
                lines=[dict(l_mm=l["l_mm"], v_mps=l["v_surface"], used=l["used"],
                            agreement=l["agreement"]) for l in stv["lines"]],
                warnings=stv["warnings"])
            # 유량 (H-STIV 평균유속 × 통수단면적)
            sec = hyd.section(lvl.depth_mm / 1000, D / 1000)
            out["velocity_stiv"]["Q_Lps"] = stv["v_mean"] * sec["A"] * 1000
        elif fr and lvl.pose is None:
            out.setdefault("warnings", []).append("초점거리 정보가 없어 영상 유속(H-STIV)을 계산하지 못함")
        out["ok"] = True
    except Exception as e:  # 앱에서 오류 메시지 표시
        out["error"] = f"{type(e).__name__}: {e}"
        out["traceback"] = traceback.format_exc()
    out["t_total_s"] = round(time.time() - t0, 2)
    try:
        with open(os.path.join(session_dir, "result.json"), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=1, default=float)
    except Exception:
        pass
    return json.dumps(out, ensure_ascii=False, default=float)
