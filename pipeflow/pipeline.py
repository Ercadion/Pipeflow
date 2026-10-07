"""
관로 수위·유속 계산 파이프라인 (데모용 핵심 알고리즘)

    level = measure_level(img, diameter_mm=100)                    # 사진 1장 -> 수위
    v1    = velocity_from_level(level, slope=0.01, n=0.010)        # 수위 -> 등류 공식 유속
    v2    = velocity_from_frames(frames, dt, level, f_px=...)      # 연속 프레임 -> 수면 추적 유속

좌표계
 - 이미지: x 오른쪽, y 아래 (px)
 - 복원 단면(rect): 원 중심 원점, 이미지와 거의 같은 방향, 단위 px(R_rect) 또는 mm
 - 카메라 3D: OpenCV 관례 (x 오른쪽, y 아래, z 전방)
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from . import hydraulics as hyd
from .detect import detect_rim, detect_waterline, load_image, rectify_image, select_inner_rim
from .geometry import (DTYPE, Ellipse, backproject_to_plane, circle_pose, fit_ellipse,
                       intrinsics, project, rectify_affine)

RECT_SIZE = 512


@dataclass
class LevelResult:
    depth_mm: float                 # 수심 (관 내부 바닥 기준)
    fill_ratio: float               # h / D
    diameter_mm: float
    ellipse: Ellipse                # 검출된 끝단 테두리 (이미지 px)
    tilt_deg: float                 # 카메라 광축 vs 관 축 각도 (약투시)
    tilt_dir_deg: float             # 기울기 방향 (이미지에서 단축 방향 각)
    roll_deg: float                 # 수면선 기울기 = 카메라의 관축 둘레 회전(roll)
    rho_mm: float                   # 원 중심 -> 수면선 거리 (물 쪽 +)
    waterline_img: np.ndarray       # 이미지 상 수면선 양 끝점 (2,2)
    method: str                     # 'weak_perspective' | 'perspective'
    confidence: float | None = None
    depth_mm_weak: float | None = None
    pose: dict | None = None
    extra: dict = field(default_factory=dict)

    def summary(self) -> dict:
        d = dict(depth_mm=round(self.depth_mm, 2), fill_ratio=round(self.fill_ratio, 4),
                 diameter_mm=self.diameter_mm, method=self.method,
                 tilt_deg=round(self.tilt_deg, 2), tilt_dir_deg=round(self.tilt_dir_deg, 1),
                 roll_deg=round(self.roll_deg, 2),
                 ellipse=dict((k, round(v, 3)) for k, v in self.ellipse.to_dict().items()))
        if self.confidence is not None:
            d["waterline_confidence"] = round(self.confidence, 3)
        if self.depth_mm_weak is not None and self.method == "perspective":
            d["depth_mm_weak_perspective"] = round(self.depth_mm_weak, 2)
        src = (self.extra.get("det_info") or {}).get("inner_source")
        if src:
            d["inner_source"] = src
        return d


# ---------------------------------------------------------------------------
# 수위
# ---------------------------------------------------------------------------
def _rect_line_to_img(theta, rho, R_rect, Ainv, tinv, inner=1.0):
    """rect 좌표 직선 (법선 (sinθ,cosθ), 거리 ρ) 의 원 내부 현 양 끝점을 이미지 좌표로."""
    nx, ny = math.sin(theta), math.cos(theta)
    tx, ty = math.cos(theta), -math.sin(theta)
    half = math.sqrt(max((R_rect * inner) ** 2 - rho**2, 0.0))
    q = torch.tensor([[rho * nx - half * tx, rho * ny - half * ty],
                      [rho * nx + half * tx, rho * ny + half * ty]], dtype=DTYPE)
    return (q @ Ainv.T + tinv).numpy()


def measure_level(img, diameter_mm: float, *,
                  ellipse: Ellipse | None = None,
                  select_inner: bool = True,
                  waterline_pts=None,
                  f_px: float | None = None, principal=None,
                  camera_above: bool = True,
                  waterline_kwargs: dict | None = None,
                  calib: dict | None = None) -> LevelResult:
    """사진 1장으로 수심 계산.

    diameter_mm  : 관 내경 [mm] (수리 계산 기준)
    ellipse      : 관 내경 테두리 타원을 직접 지정(수동 보정) - 없으면 자동 검출
    select_inner : 자동 검출 시 끝단 면의 바깥/안쪽 동심 테두리 중 안쪽(내경)을 골라 씀 (관 두께 입력 불필요)
    waterline_pts: 수면선 위 두 점 [(x1,y1),(x2,y2)] (이미지 px) - 없으면 자동 검출
    f_px         : 초점거리 [px]. 주면 완전투시 보정 수행 (principal 기본 = 이미지 중심)
    calib        : calibration.calibrate_chessboard 결과. 주면 렌즈 왜곡 보정 후
                   f_px·principal 을 캘리브레이션 값으로 사용 (권장)
    """
    img = load_image(img)
    if calib is not None:
        from .calibration import intrinsics_for, scaled_K, undistort
        if waterline_pts is not None:   # 원본 좌표 -> 왜곡 보정 좌표
            Kc = scaled_K(calib, img.shape)
            pts = np.asarray(waterline_pts, np.float64).reshape(-1, 1, 2)
            waterline_pts = cv2.undistortPoints(pts, Kc, np.array(calib["dist"]), P=Kc).reshape(-1, 2)
        img = undistort(img, calib)
        f_px, principal = intrinsics_for(calib, img.shape)
    H, W = img.shape[:2]
    r_in = diameter_mm / 2
    R_rim = r_in                     # 테두리 타원 = 관 내경
    det_info = {}
    if ellipse is None:
        ellipse, det_info = detect_rim(img)
        if select_inner:
            ellipse, iinfo = select_inner_rim(img, ellipse, f_px=f_px, principal=principal)
            det_info.update(iinfo)

    rect, R_rect, (Ainv, tinv) = rectify_image(img, ellipse, RECT_SIZE)
    A = torch.linalg.inv(Ainv)
    mm_per_rect = R_rim / R_rect

    if waterline_pts is None:
        wl = detect_waterline(rect, R_rect, **(waterline_kwargs or {}))
        theta, rho = wl["theta"], wl["rho"]
        conf = wl["confidence"]
    else:
        p = torch.tensor(np.asarray(waterline_pts, dtype=np.float64), dtype=DTYPE)
        q = (p - tinv) @ A.T
        d = q[1] - q[0]
        n = torch.tensor([-d[1].item(), d[0].item()], dtype=DTYPE)
        n = n / n.norm()
        if n[1] < 0:
            n = -n
        theta = math.atan2(n[0].item(), n[1].item())
        rho = (n @ q[0]).item()
        conf, wl = None, None

    rho_mm = rho * mm_per_rect
    depth_weak = r_in - rho_mm
    wl_img = _rect_line_to_img(theta, rho, R_rect * r_in / R_rim, Ainv, tinv)

    res = LevelResult(
        depth_mm=depth_weak, fill_ratio=depth_weak / diameter_mm, diameter_mm=diameter_mm,
        ellipse=ellipse, tilt_deg=ellipse.tilt_deg,
        tilt_dir_deg=math.degrees(math.atan2(*ellipse.minor_dir[::-1])),
        roll_deg=math.degrees(theta), rho_mm=rho_mm, waterline_img=wl_img,
        method="weak_perspective", confidence=conf, depth_mm_weak=depth_weak,
        extra=dict(rect=rect, R_rect=R_rect, theta=theta, rho_rect=rho, wl=wl,
                   det_info=det_info, R_rim_mm=R_rim, image_size=(W, H), calib=calib,
                   undistorted_image=img if calib is not None else None))

    if not (-r_in < rho_mm < r_in):
        res.extra.setdefault("warnings", []).append("수면선이 관 내경 밖에 위치 - 검출 오류 가능")

    if f_px is not None:
        _refine_perspective(res, f_px, principal or (W / 2, H / 2), camera_above)
    return res


def _refine_perspective(res: LevelResult, f_px, principal, camera_above=True):
    """초점거리를 알 때: 원 자세를 정확히 추정해 수면선을 끝단 평면으로 역투영."""
    el = res.ellipse
    K = intrinsics(f_px, *principal)
    R_rim = res.extra["R_rim_mm"]
    r_in = res.diameter_mm / 2
    sols = circle_pose(el, K, R_rim)
    if not sols:
        return
    theta, rho, R_rect = res.extra["theta"], res.extra["rho_rect"], res.extra["R_rect"]
    _, (Ainv, tinv) = rectify_affine(el, R_rect)
    # 이미지 상 수면선 2점 + 물 쪽 점
    nx, ny = math.sin(theta), math.cos(theta)
    tx, ty = math.cos(theta), -math.sin(theta)
    q = torch.tensor([[rho * nx - 0.5 * R_rect * tx, rho * ny - 0.5 * R_rect * ty],
                      [rho * nx + 0.5 * R_rect * tx, rho * ny + 0.5 * R_rect * ty],
                      [(rho + 0.2 * R_rect) * nx, (rho + 0.2 * R_rect) * ny],
                      [rho * nx, rho * ny]], dtype=DTYPE)
    p = q @ Ainv.T + tinv
    w_img = (p[2] - p[3]); w_img = w_img / w_img.norm()   # 이미지에서 '물 쪽' 방향

    cands = []
    for s in sols:
        n, C = s["normal"], s["center"]
        L = 2 * R_rim
        far = project(K, (C + L * n)[None])[0]
        near = project(K, C[None])[0]
        up_score = -((far - near) @ w_img).item()     # >0 이면 먼 쪽이 이미지 '위'(물 반대)로
        X, _ = backproject_to_plane(K, p[:3], n, C)
        u = X[1] - X[0]; u = u / u.norm()             # 수면선 3D 방향 (끝단 평면 내)
        g = torch.linalg.cross(n, u); g = g / g.norm()
        if ((X[2] - X[0]) @ g) < 0:
            g = -g                                    # g: 끝단 평면 내 중력(물 쪽) 방향
        d = ((X[0] - C) @ g).item()                   # 중심 -> 수면선 (물 쪽 +)
        cands.append(dict(normal=n, center=C, g=g, u=u, rho_mm=d, depth_mm=r_in - d,
                          up_score=up_score, circ_err=s["circ_err"]))
    pick = [c for c in cands if (c["up_score"] > 0) == camera_above]
    best = pick[0] if pick else cands[0]
    res.depth_mm = best["depth_mm"]
    res.fill_ratio = best["depth_mm"] / res.diameter_mm
    res.rho_mm = best["rho_mm"]
    res.tilt_deg = math.degrees(math.acos(min(1.0, abs(best["normal"][2].item()))))
    res.method = "perspective"
    res.pose = dict(K=K, normal=best["normal"], center=best["center"], g=best["g"],
                    u=best["u"], alternatives=[dict(depth_mm=c["depth_mm"]) for c in cands])


# ---------------------------------------------------------------------------
# 유속 (1) 등류 공식
# ---------------------------------------------------------------------------
def velocity_from_level(level: LevelResult, slope: float, n: float | None = None,
                        material: str = "glass", regime: str = "auto",
                        nu: float = hyd.NU_WATER_20C) -> dict:
    n = n if n is not None else hyd.MANNING_N[material]
    out = hyd.velocity_from_depth(level.depth_mm / 1000, level.diameter_mm / 1000, slope, n,
                                  nu, regime)
    out["inputs"] = dict(slope=slope, n=n, regime=regime, nu=nu)
    return out


# ---------------------------------------------------------------------------
# 유속 (2) 연속 프레임 수면 추적 (top-view 변환 + FFT 위상상관)
# ---------------------------------------------------------------------------
def surface_topview(img_gray: torch.Tensor, level: LevelResult, s_range_mm, res_mm=0.5,
                    lat_frac=0.8):
    """수면을 위에서 본 영상(top-view)으로 변환.
    반환: topview (Ns, Nl), valid mask, s 축(mm) 배열.
    s: 끝단에서 관 안쪽 방향 거리, l: 수면 폭 방향."""
    P = level.pose
    K, n, C, g, u = P["K"], P["normal"], P["center"], P["g"], P["u"]
    r_in = level.diameter_mm / 2
    d = level.rho_mm
    half = math.sqrt(max(r_in**2 - d**2, 0.0))
    O = C + d * g                                   # 끝단 평면 위 수면선 중점
    s = torch.arange(s_range_mm[0], s_range_mm[1], res_mm, dtype=DTYPE)
    l = torch.arange(-lat_frac * half, lat_frac * half + 1e-9, res_mm, dtype=DTYPE)
    S, Lg = torch.meshgrid(s, l, indexing="ij")
    X = O + S[..., None] * n + Lg[..., None] * u    # (Ns,Nl,3) 수면 위 3D 점
    uv = project(K, X)
    # 가시성: 카메라->X 광선이 끝단 원판의 '공기 쪽'을 통과해야 함
    t = (C @ n) / (X @ n)
    Y = X * t[..., None]
    rel = Y - C
    vis = (rel.norm(dim=-1) < r_in) & ((rel @ g) < d) & (X[..., 2] > 0)
    Hh, Ww = img_gray.shape
    grid = torch.stack([uv[..., 0] / (Ww - 1) * 2 - 1, uv[..., 1] / (Hh - 1) * 2 - 1], -1)
    inside = (grid.abs() <= 1).all(-1)
    top = F.grid_sample(img_gray[None, None].float(), grid[None].float(), align_corners=True,
                        mode="bilinear")[0, 0].to(DTYPE)
    valid = vis & inside
    # 그레이징 각(시선과 수면의 각) - 작으면 추적 정확도 낮음
    ray = X / X.norm(dim=-1, keepdim=True)
    graze = torch.rad2deg(torch.asin((ray @ g).abs().clamp(max=1.0)))
    return top, valid, s, graze


def phase_correlation(a: torch.Tensor, b: torch.Tensor, mask: torch.Tensor):
    """b 가 a 에 대해 이동한 양 (ds, dl) [px]. torch.fft 기반 + 부화소 보간."""
    w = torch.outer(torch.hann_window(a.shape[0], periodic=False, dtype=DTYPE),
                    torch.hann_window(a.shape[1], periodic=False, dtype=DTYPE))
    m = mask.to(DTYPE) * w
    a = (a - (a * mask).sum() / mask.sum().clamp_min(1)) * m
    b = (b - (b * mask).sum() / mask.sum().clamp_min(1)) * m
    Fa, Fb = torch.fft.fft2(a), torch.fft.fft2(b)
    Rr = Fb * Fa.conj()
    Rr = Rr / (Rr.abs() + 1e-9)
    corr = torch.fft.ifft2(Rr).real
    k = torch.argmax(corr)
    i, j = divmod(k.item(), corr.shape[1])
    Ns, Nl = corr.shape

    def sub(c_m, c_0, c_p):
        den = c_m - 2 * c_0 + c_p
        return 0.5 * (c_m - c_p) / den if den < 0 else 0.0

    di = sub(corr[(i - 1) % Ns, j], corr[i, j], corr[(i + 1) % Ns, j]).__float__()
    dj = sub(corr[i, (j - 1) % Nl], corr[i, j], corr[i, (j + 1) % Nl]).__float__()
    si = i + di if i <= Ns // 2 else i + di - Ns
    sj = j + dj if j <= Nl // 2 else j + dj - Nl
    peak = corr[i, j].item()
    return si, sj, peak


def velocity_from_frames(frames, dt: float, level: LevelResult, *, f_px: float | None = None,
                         principal=None, s_range_mm=None, res_mm: float = 0.5,
                         camera_above: bool = True, Re_hint: float | None = None) -> dict:
    """연속 프레임(같은 카메라 위치)에서 수면 부유물/무늬를 추적해 표면유속 측정.
    level 은 첫 프레임으로 measure_level(..., f_px=...) 한 결과(완전투시 필요)."""
    if level.pose is None:
        if f_px is None:
            raise ValueError("수면 추적에는 초점거리 f_px (px) 가 필요합니다.")
        W, H = level.extra["image_size"]
        _refine_perspective(level, f_px, principal or (W / 2, H / 2), camera_above)
    D = level.diameter_mm
    if s_range_mm is None:
        s_range_mm = (0.05 * D, 1.5 * D)
    tops, masks = [], []
    graze = None
    for gray in _load_gray_frames(frames, level.extra.get("calib")):
        top, valid, s_axis, graze = surface_topview(gray, level, s_range_mm, res_mm)
        tops.append(top); masks.append(valid)
    common = masks[0]
    for m in masks[1:]:
        common = common & m
    rows = common.any(1)
    if rows.sum() < 8:
        raise RuntimeError("관측 가능한 수면 영역이 너무 작음 (카메라가 수면보다 충분히 위에 있어야 함)")
    r0, r1 = torch.nonzero(rows).flatten()[[0, -1]].tolist()
    shifts, peaks = [], []
    for a, b in zip(tops[:-1], tops[1:]):
        ds, dl, pk = phase_correlation(a[r0:r1 + 1], b[r0:r1 + 1], common[r0:r1 + 1])
        shifts.append(ds * res_mm); peaks.append(pk)
    v_s = [s / 1000 / dt for s in shifts]  # m/s, + = 카메라에서 멀어지는 방향(관 안쪽)
    v_surface = float(np.median(v_s))
    coef = hyd.surface_to_mean_coef(Re_hint)
    g_mean = graze[common].mean().item() if common.any() else float("nan")
    warn = []
    if g_mean < 10:
        warn.append(f"시선-수면 각이 {g_mean:.1f}° 로 작아 추적 오차가 큼 (카메라를 더 위에서)")
    if min(peaks) < 0.1:
        warn.append("상관 피크가 낮음 - 수면 추적자(부유 입자) 부족 가능")
    return dict(v_surface=v_surface, v_mean=coef * v_surface, coef=coef,
                per_pair=v_s, peaks=peaks, graze_deg=g_mean, s_range_mm=s_range_mm,
                warnings=warn)


# ---------------------------------------------------------------------------
# 유속 (3) 시공간영상(STIV) — H-STIV + 정지무늬 제거 + 적응형 프레임 간격
# ---------------------------------------------------------------------------
def _load_gray_frames(frames, calib):
    out = []
    for fr in frames:
        fr = load_image(fr)
        if calib is not None:
            from .calibration import undistort
            fr = undistort(fr, calib)
        out.append(torch.from_numpy(cv2.cvtColor(fr, cv2.COLOR_BGR2GRAY).astype(np.float64)))
    return out


def build_stis(frames_gray, level: LevelResult, s_range_mm, res_mm=0.5, n_lines=5,
               lat_frac=0.7, band_px=1):
    """수면 top-view 에서 관 축(s) 방향 측정선 n_lines 개의 시공간영상 생성.
    반환: list[(l_mm, STI (T, Ns))], s 축(mm), 평균 시선-수면각."""
    tops, masks = [], []
    graze = None
    for g in frames_gray:
        top, valid, s_axis, graze = surface_topview(g, level, s_range_mm, res_mm, lat_frac=0.9)
        tops.append(top); masks.append(valid)
    common = torch.stack(masks).all(0)                      # (Ns, Nl)
    Nl = common.shape[1]
    half_idx = (Nl - 1) / 2
    cols = [int(round(half_idx + c * half_idx * lat_frac / 0.9))
            for c in (torch.linspace(-1, 1, n_lines).tolist() if n_lines > 1 else [0.0])]
    T = torch.stack(tops)                                   # (T, Ns, Nl)
    out = []
    for j in cols:
        j0, j1 = max(0, j - band_px), min(Nl, j + band_px + 1)
        ok = common[:, j0:j1].all(1)
        if ok.sum() < 32:
            continue
        idx = torch.nonzero(ok).flatten()
        # 가장 긴 연속 구간
        brk = torch.nonzero(idx[1:] - idx[:-1] > 1).flatten()
        starts = torch.cat([idx[:1], idx[brk + 1]]); ends = torch.cat([idx[brk], idx[-1:]])
        k = int(torch.argmax(ends - starts))
        r0, r1 = int(starts[k]), int(ends[k])
        if r1 - r0 < 32:
            continue
        sti = T[:, r0:r1 + 1, j0:j1].mean(-1)                # (T, Ns)
        l_mm = (j - half_idx) * res_mm
        out.append(dict(l_mm=l_mm, sti=sti, s0_mm=float(s_axis[r0]), s1_mm=float(s_axis[r1])))
    g_mean = graze[common].mean().item() if common.any() else float("nan")
    return out, g_mean


def velocity_from_frames_stiv(frames, dt: float, level: LevelResult, *, f_px=None,
                              principal=None, calib=None, s_range_mm=None, res_mm: float = 0.5,
                              n_lines: int = 5, lat_frac: float = 0.7,
                              target_disp=(2.0, 15.0), threshold: float = 2.0,
                              c_split: str = "consecutive", remove_static: bool = True,
                              camera_above: bool = True, Re_hint: float | None = None) -> dict:
    """연속 프레임 -> STIV 표면유속.
    1) 수면 top-view 위 관 축 방향 측정선(폭 방향 n_lines 개)으로 STI 생성
    2) 중앙선으로 예비 변위 u0 산정 -> u0 < target_disp[0] 이면 프레임 간격(stride) 을 늘려
       변위를 2 px/step 이상으로 유도 (0.5 px 미만 화소 이산성 오차 회피)
    3) 각 측정선 H-STIV (F/C 선택) + 두 결과 일치도
    반환 속도 단위 m/s, + = 카메라에서 멀어지는 방향(관 안쪽)"""
    from .stiv import h_stiv
    calib = calib if calib is not None else level.extra.get("calib")
    if level.pose is None:
        W, H = level.extra["image_size"]
        if calib is not None:
            from .calibration import intrinsics_for
            f_px, principal = intrinsics_for(calib, (H, W))
        if f_px is None:
            raise ValueError("STIV 에는 초점거리(f_px) 또는 캘리브레이션(calib) 이 필요합니다.")
        _refine_perspective(level, f_px, principal or (W / 2, H / 2), camera_above)
    D = level.diameter_mm
    if s_range_mm is None:
        s_range_mm = (0.05 * D, 1.5 * D)
    gray = _load_gray_frames(frames, calib)
    lines, graze = build_stis(gray, level, s_range_mm, res_mm, n_lines, lat_frac)
    if not lines:
        raise RuntimeError("STI 를 만들 수 있는 수면 영역이 없음 (카메라가 수면보다 충분히 위에 있어야 함)")
    center = min(lines, key=lambda d: abs(d["l_mm"]))
    pilot = h_stiv(center["sti"], threshold, remove_static, c_split)
    u0 = abs(pilot["u"])
    Tn = center["sti"].shape[0]
    stride = 1
    if u0 < target_disp[0]:
        stride = int(math.ceil(target_disp[0] / max(u0, 1e-3)))
        stride = max(1, min(stride, Tn // 24))                # STI 행 수 ≥ 24 유지
    results = []
    for ln in lines:
        S = ln["sti"][::stride]
        r = h_stiv(S, threshold, remove_static, c_split)
        v = r["u"] * res_mm / (stride * dt) / 1000.0
        results.append(dict(l_mm=round(ln["l_mm"], 2), v_surface=v, used=r["used"],
                            u_px_per_step=r["u"], u_C=r["u_C"], u_F=r["u_F"],
                            agreement=r["agreement"], c_peak=r["c_peak"],
                            angle_deg=r["angle_deg"], s_range_mm=(ln["s0_mm"], ln["s1_mm"])))
    vs = torch.tensor([r["v_surface"] for r in results], dtype=DTYPE)
    v_surf = float(vs.median())
    coef = hyd.surface_to_mean_coef(Re_hint)
    warn = []
    if graze < 10:
        warn.append(f"시선-수면 각이 {graze:.1f}° 로 작아 오차가 큼 (카메라를 더 위에서)")
    bad = [r for r in results if r["agreement"] > 0.1]
    if bad:
        warn.append(f"측정선 {len(bad)}/{len(results)} 개에서 C/F-STIV 결과 차이 > 10% - 추적 무늬 부족 의심")
    disp = abs(results[len(results) // 2]["u_px_per_step"])
    if disp > target_disp[1]:
        warn.append(f"변위 {disp:.1f} px/step 로 큼 - 프레임 속도(fps)를 높이는 것을 권장")
    return dict(v_surface=v_surf, v_mean=coef * v_surf, coef=coef, lines=results,
                stride=stride, res_mm=res_mm, graze_deg=graze, method="H-STIV",
                spread=float(vs.max() - vs.min()) if len(vs) > 1 else 0.0, warnings=warn)


# ---------------------------------------------------------------------------
# 시각화
# ---------------------------------------------------------------------------
def draw_overlay(img, level: LevelResult, text: str | None = None) -> np.ndarray:
    if level.extra.get("undistorted_image") is not None:
        img = level.extra["undistorted_image"]   # 좌표가 왜곡 보정 영상 기준
    img = load_image(img).copy()
    H, W = img.shape[:2]
    th = max(2, int(round(max(H, W) / 400)))
    pts = level.ellipse.points(360).numpy().round().astype(np.int32)
    cv2.polylines(img, [pts], True, (0, 255, 0), th)
    p = level.waterline_img.round().astype(np.int32)
    cv2.line(img, tuple(p[0]), tuple(p[1]), (0, 0, 255), th + 1)
    c = (int(level.ellipse.cx), int(level.ellipse.cy))
    cv2.circle(img, c, th * 2, (0, 255, 0), -1)
    if text is None:
        text = f"h={level.depth_mm:.1f}mm ({level.fill_ratio*100:.1f}%)  tilt={level.tilt_deg:.1f}deg"
    fs = max(H, W) / 1300
    cv2.putText(img, text, (20, int(50 * fs)), cv2.FONT_HERSHEY_SIMPLEX, fs, (0, 0, 0), th + 3)
    cv2.putText(img, text, (20, int(50 * fs)), cv2.FONT_HERSHEY_SIMPLEX, fs, (255, 255, 255), th)
    return img
