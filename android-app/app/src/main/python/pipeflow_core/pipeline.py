"""
수위·유속 파이프라인 (numpy) — 데스크톱 pipeflow/pipeline.py 이식
 measure_level(gray, D, wall, ...)        -> LevelResult
 velocity_from_level(level, slope, ...)   -> 등류 공식 유속
 velocity_from_frames_stiv(frames, dt, level) -> H-STIV 표면유속
 draw_overlay(gray_or_rgb, level)         -> RGB uint8
"""
from __future__ import annotations

import math

import numpy as np

from . import hydraulics as hyd
from .detect import detect_rim, detect_waterline, rectify_image
from .geometry import (Ellipse, backproject_to_plane, circle_pose, intrinsics, project,
                       rectify_affine)
from .imgproc import bilinear

RECT_SIZE = 512


class LevelResult:
    def __init__(self, **kw):
        self.pose = None
        self.confidence = None
        self.extra = {}
        self.__dict__.update(kw)

    def summary(self):
        d = dict(depth_mm=round(self.depth_mm, 2), fill_ratio=round(self.fill_ratio, 4),
                 diameter_mm=self.diameter_mm, method=self.method,
                 tilt_deg=round(self.tilt_deg, 2), roll_deg=round(self.roll_deg, 2),
                 ellipse={k: round(v, 3) for k, v in self.ellipse.to_dict().items()},
                 waterline_img=np.round(self.waterline_img, 2).tolist())
        if self.confidence is not None:
            d["waterline_confidence"] = round(self.confidence, 3)
        if self.method == "perspective":
            d["depth_mm_weak_perspective"] = round(self.depth_mm_weak, 2)
        if self.extra.get("warnings"):
            d["warnings"] = list(self.extra["warnings"])
        return d


def _rect_line_to_img(theta, rho, R_rect, Ainv, tinv):
    nx, ny = math.sin(theta), math.cos(theta)
    tx, ty = math.cos(theta), -math.sin(theta)
    half = math.sqrt(max(R_rect ** 2 - rho ** 2, 0.0))
    q = np.array([[rho * nx - half * tx, rho * ny - half * ty],
                  [rho * nx + half * tx, rho * ny + half * ty]])
    return q @ Ainv.T + tinv


def measure_level(gray, diameter_mm, wall_mm=0.0, *, rim_is="outer", ellipse=None,
                  waterline_pts=None, f_px=None, principal=None, camera_above=True,
                  waterline_kwargs=None):
    gray = np.asarray(gray, np.float64)
    H, W = gray.shape
    r_in = diameter_mm / 2
    R_rim = r_in + wall_mm if rim_is == "outer" else r_in
    det_info = {}
    if ellipse is None:
        ellipse, det_info = detect_rim(gray)
    rect, R_rect, (Ainv, tinv) = rectify_image(gray, ellipse, RECT_SIZE)
    A = np.linalg.inv(Ainv)
    if waterline_pts is None:
        wl = detect_waterline(rect, R_rect, **(waterline_kwargs or {}))
        theta, rho, conf = wl["theta"], wl["rho"], wl["confidence"]
    else:
        p = np.asarray(waterline_pts, np.float64).reshape(2, 2)
        q = (p - tinv) @ A.T
        d = q[1] - q[0]
        n = np.array([-d[1], d[0]]); n /= np.linalg.norm(n)
        if n[1] < 0:
            n = -n
        theta, rho, conf, wl = math.atan2(n[0], n[1]), float(n @ q[0]), None, None
    rho_mm = rho * R_rim / R_rect
    depth_weak = r_in - rho_mm
    res = LevelResult(depth_mm=depth_weak, fill_ratio=depth_weak / diameter_mm,
                      diameter_mm=diameter_mm, ellipse=ellipse, tilt_deg=ellipse.tilt_deg,
                      roll_deg=math.degrees(theta), rho_mm=rho_mm,
                      waterline_img=_rect_line_to_img(theta, rho, R_rect * r_in / R_rim, Ainv, tinv),
                      method="weak_perspective", confidence=conf, depth_mm_weak=depth_weak)
    res.extra = dict(R_rect=R_rect, theta=theta, rho_rect=rho, det_info=det_info,
                     R_rim_mm=R_rim, image_size=(W, H), alternatives=(wl or {}).get("alternatives"))
    if not (-r_in < rho_mm < r_in):
        res.extra.setdefault("warnings", []).append("수면선이 관 내경 밖 - 검출 오류 가능")
    if f_px is not None:
        _refine_perspective(res, f_px, principal or (W / 2, H / 2), camera_above)
    return res


def _refine_perspective(res, f_px, principal, camera_above=True):
    el = res.ellipse
    K = intrinsics(f_px, *principal)
    R_rim = res.extra["R_rim_mm"]
    r_in = res.diameter_mm / 2
    sols = circle_pose(el, K, R_rim)
    if not sols:
        return
    theta, rho, R_rect = res.extra["theta"], res.extra["rho_rect"], res.extra["R_rect"]
    _, (Ainv, tinv) = rectify_affine(el, R_rect)
    nx, ny = math.sin(theta), math.cos(theta)
    tx, ty = math.cos(theta), -math.sin(theta)
    q = np.array([[rho * nx - 0.5 * R_rect * tx, rho * ny - 0.5 * R_rect * ty],
                  [rho * nx + 0.5 * R_rect * tx, rho * ny + 0.5 * R_rect * ty],
                  [(rho + 0.2 * R_rect) * nx, (rho + 0.2 * R_rect) * ny],
                  [rho * nx, rho * ny]])
    p = q @ Ainv.T + tinv
    w_img = p[2] - p[3]; w_img /= np.linalg.norm(w_img)
    cands = []
    for s in sols:
        n, C = s["normal"], s["center"]
        far = project(K, (C + 2 * R_rim * n)[None])[0]
        near = project(K, C[None])[0]
        up_score = -float((far - near) @ w_img)
        X, _ = backproject_to_plane(K, p[:3], n, C)
        u = X[1] - X[0]; u /= np.linalg.norm(u)
        g = np.cross(n, u); g /= np.linalg.norm(g)
        if (X[2] - X[0]) @ g < 0:
            g = -g
        d = float((X[0] - C) @ g)
        cands.append(dict(normal=n, center=C, g=g, u=u, rho_mm=d, depth_mm=r_in - d,
                          up_score=up_score))
    pick = [c for c in cands if (c["up_score"] > 0) == camera_above]
    best = pick[0] if pick else cands[0]
    res.depth_mm = best["depth_mm"]
    res.fill_ratio = best["depth_mm"] / res.diameter_mm
    res.rho_mm = best["rho_mm"]
    res.tilt_deg = math.degrees(math.acos(min(1.0, abs(best["normal"][2]))))
    res.method = "perspective"
    res.pose = dict(K=K, normal=best["normal"], center=best["center"], g=best["g"], u=best["u"])


def velocity_from_level(level, slope, n=None, material="glass", regime="auto",
                        nu=hyd.NU_WATER_20C):
    n = n if n is not None else hyd.MANNING_N[material]
    out = hyd.velocity_from_depth(level.depth_mm / 1000, level.diameter_mm / 1000, slope, n,
                                  nu, regime)
    out["inputs"] = dict(slope=slope, n=n, regime=regime, nu=nu)
    return out


# ---------------------------------------------------------------------------
# H-STIV
# ---------------------------------------------------------------------------
def topview_grid(level, s_range_mm, res_mm=0.5, lat_frac=0.9):
    """top-view 격자의 영상 좌표와 가시성 (프레임마다 재사용)."""
    P = level.pose
    K, n, C, g, u = P["K"], P["normal"], P["center"], P["g"], P["u"]
    r_in = level.diameter_mm / 2
    d = level.rho_mm
    half = math.sqrt(max(r_in ** 2 - d ** 2, 0.0))
    O = C + d * g
    s = np.arange(s_range_mm[0], s_range_mm[1], res_mm)
    l = np.arange(-lat_frac * half, lat_frac * half + 1e-9, res_mm)
    S, L = np.meshgrid(s, l, indexing="ij")
    X = O + S[..., None] * n + L[..., None] * u
    uv = project(K, X)
    t = (C @ n) / (X @ n)
    rel = X * t[..., None] - C
    vis = (np.linalg.norm(rel, axis=-1) < r_in) & (rel @ g < d) & (X[..., 2] > 0)
    W, H = level.extra["image_size"]
    vis &= (uv[..., 0] >= 0) & (uv[..., 0] <= W - 1) & (uv[..., 1] >= 0) & (uv[..., 1] <= H - 1)
    ray = X / np.linalg.norm(X, axis=-1, keepdims=True)
    graze = np.degrees(np.arcsin(np.clip(np.abs(ray @ g), 0, 1)))
    return uv, vis, s, graze


def build_stis(frames_gray, level, s_range_mm, res_mm=0.5, n_lines=5, lat_frac=0.7, band_px=1):
    uv, vis, s_axis, graze = topview_grid(level, s_range_mm, res_mm, lat_frac=0.9)
    Nl = vis.shape[1]
    half_idx = (Nl - 1) / 2
    cs = np.linspace(-1, 1, n_lines) if n_lines > 1 else np.array([0.0])
    cols = [int(round(half_idx + c * half_idx * lat_frac / 0.9)) for c in cs]
    lines = []
    for j in cols:
        j0, j1 = max(0, j - band_px), min(Nl, j + band_px + 1)
        ok = vis[:, j0:j1].all(1)
        idx = np.flatnonzero(ok)
        if len(idx) < 32:
            continue
        brk = np.flatnonzero(np.diff(idx) > 1)
        starts = np.concatenate([idx[:1], idx[brk + 1]]); ends = np.concatenate([idx[brk], idx[-1:]])
        k = int(np.argmax(ends - starts))
        r0, r1 = int(starts[k]), int(ends[k])
        if r1 - r0 < 32:
            continue
        seg = uv[r0:r1 + 1, j]
        ppm = float(np.median(np.linalg.norm(np.diff(seg, axis=0), axis=1))) / res_mm   # 영상 px / mm (s 방향)
        lines.append(dict(j0=j0, j1=j1, r0=r0, r1=r1, l_mm=(j - half_idx) * res_mm, img_px_per_mm=ppm,
                          s0_mm=float(s_axis[r0]), s1_mm=float(s_axis[r1])))
    # 필요한 좌표만 샘플 (프레임당)
    stis = [[] for _ in lines]
    for fr in frames_gray:
        for li, ln in enumerate(lines):
            sub = uv[ln["r0"]:ln["r1"] + 1, ln["j0"]:ln["j1"]]
            stis[li].append(bilinear(fr, sub[..., 0], sub[..., 1]).mean(-1))
    for li, ln in enumerate(lines):
        ln["sti"] = np.array(stis[li])
    g_mean = float(graze[vis].mean()) if vis.any() else float("nan")
    return lines, g_mean


def velocity_from_frames_stiv(frames_gray, dt, level, *, f_px=None, principal=None,
                              s_range_mm=None, res_mm=0.5, n_lines=5, lat_frac=0.7,
                              target_disp=(2.0, 15.0), target_img_disp=3.0, threshold=2.0,
                              c_split="consecutive",
                              remove_static=True, camera_above=True, Re_hint=None,
                              progress=None, times=None):
    """times: 프레임별 실제 촬영 시각[s] (휴대폰은 프레임 간격이 일정하지 않을 수 있음).
    주어지면 STI 행을 등간격(중앙값 dt)으로 시간 보간한 뒤 분석."""
    from .stiv import h_stiv
    if level.pose is None:
        W, H = level.extra["image_size"]
        if f_px is None:
            raise ValueError("STIV 에는 초점거리(f_px)가 필요합니다.")
        _refine_perspective(level, f_px, principal or (W / 2, H / 2), camera_above)
    D = level.diameter_mm
    if s_range_mm is None:
        s_range_mm = (0.05 * D, 1.5 * D)
    lines, graze = build_stis(frames_gray, level, s_range_mm, res_mm, n_lines, lat_frac)
    resampled = False
    if times is not None:
        tt = np.asarray(times, np.float64)
        tt = tt - tt[0]
        dts = np.diff(tt)
        dt = float(np.median(dts))
        if np.std(dts) / dt > 0.05:
            tu = np.arange(0, tt[-1] + 1e-9, dt)
            for ln in lines:
                S = ln["sti"]
                ln["sti"] = np.stack([np.interp(tu, tt, S[:, c]) for c in range(S.shape[1])], 1)
            resampled = True
    if not lines:
        raise RuntimeError("STI 를 만들 수 있는 수면 영역이 없음 (카메라가 수면보다 충분히 위에 있어야 함)")
    center = min(lines, key=lambda d: abs(d["l_mm"]))
    u0 = abs(h_stiv(center["sti"], threshold, remove_static, c_split)["u"])
    Tn = center["sti"].shape[0]
    # 프레임 간격(stride): top-view 변위 ≥ target_disp[0] 이고, 원 영상에서의 변위도
    # ≥ target_img_disp px 가 되도록 (원근으로 줄어든 영상 변위가 작으면 보간 오차로 편향 — 합성시험 확인)
    img_disp = u0 * res_mm * center["img_px_per_mm"]
    need = max(target_disp[0] / max(u0, 1e-3), target_img_disp / max(img_disp, 1e-3))
    stride = max(1, min(int(math.ceil(need)), Tn // 24))
    results = []
    for i, ln in enumerate(lines):
        r = h_stiv(ln["sti"][::stride], threshold, remove_static, c_split)
        results.append(dict(l_mm=round(ln["l_mm"], 2), v_surface=r["u"] * res_mm / (stride * dt) / 1000.0,
                            used=r["used"], u_px_per_step=r["u"], u_C=r["u_C"], u_F=r["u_F"],
                            agreement=r["agreement"], c_peak=r["c_peak"], angle_deg=r["angle_deg"],
                            s_range_mm=(ln["s0_mm"], ln["s1_mm"])))
        if progress:
            progress((i + 1) / len(lines))
    vs = np.array([r["v_surface"] for r in results])
    v_surf = float(np.median(vs))
    coef = hyd.surface_to_mean_coef(Re_hint)
    warn = []
    if graze < 10:
        warn.append(f"시선-수면 각 {graze:.1f}° — 카메라를 더 위에서 내려다보세요")
    bad = [r for r in results if r["agreement"] > 0.1]
    if bad:
        warn.append(f"측정선 {len(bad)}/{len(results)} 개에서 C/F 결과 차이 >10% — 수면 추적 무늬 부족 의심")
    disp = abs(results[len(results) // 2]["u_px_per_step"])
    if disp > target_disp[1]:
        warn.append(f"변위 {disp:.1f} px/step 로 큼 — fps 를 높이세요")
    if stride == Tn // 24 and need > stride:
        warn.append("흐름이 느려 프레임 간격을 충분히 늘리지 못함 — 더 길게(5초 이상) 촬영하세요")
    return dict(v_surface=v_surf, v_mean=coef * v_surf, coef=coef, lines=results, stride=stride,
                img_disp_px_per_step=img_disp * stride,
                res_mm=res_mm, graze_deg=graze, method="H-STIV", dt=dt, resampled=resampled,
                spread=float(vs.max() - vs.min()) if len(vs) > 1 else 0.0, warnings=warn)


# ---------------------------------------------------------------------------
# 시각화 (Pillow)
# ---------------------------------------------------------------------------
def draw_overlay(img, level, text=None):
    from PIL import Image, ImageDraw
    a = np.clip(np.asarray(img), 0, 255).astype(np.uint8)
    im = Image.fromarray(a).convert("RGB")
    dr = ImageDraw.Draw(im)
    W, H = im.size
    th = max(2, int(round(max(H, W) / 300)))
    pts = [tuple(p) for p in level.ellipse.points(240).tolist()]
    dr.line(pts + [pts[0]], fill=(0, 230, 0), width=th)
    p = level.waterline_img
    dr.line([tuple(p[0]), tuple(p[1])], fill=(255, 40, 40), width=th + 1)
    for q in p:
        dr.ellipse([q[0] - 3 * th, q[1] - 3 * th, q[0] + 3 * th, q[1] + 3 * th], outline=(255, 40, 40), width=th)
    c = (level.ellipse.cx, level.ellipse.cy)
    dr.ellipse([c[0] - 2 * th, c[1] - 2 * th, c[0] + 2 * th, c[1] + 2 * th], fill=(0, 230, 0))
    return np.asarray(im)
