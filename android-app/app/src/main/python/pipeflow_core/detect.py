"""
검출 (numpy) — 끝단 테두리 타원 RANSAC, 단면 복원, 수면선 Radon 탐색
(데스크톱 pipeflow/detect.py 이식. 입력은 회색조 float 영상)
"""
from __future__ import annotations

import math

import numpy as np

from .geometry import Ellipse, fit_ellipse, rectify_affine
from .imgproc import bilinear, canny, gaussian_blur, label_components, resize_area, sobel

WORK_SIZE = 720


def to_work(gray: np.ndarray):
    s = min(1.0, WORK_SIZE / max(gray.shape[:2]))
    return (resize_area(gray, s) if s < 1 else gray.astype(np.float64).copy()), s


# ---------------------------------------------------------------------------
# 1) 테두리 타원
# ---------------------------------------------------------------------------
def _conic_batch(P):
    x, y = P[..., 0], P[..., 1]
    M = np.stack([x * x, x * y, y * y, x, y, np.ones_like(x)], -1)
    _, _, Vh = np.linalg.svd(M, full_matrices=True)
    return Vh[:, -1, :]


def _params(c):
    A, B, C, D, E, Fc = [c[:, i] for i in range(6)]
    with np.errstate(divide="ignore", invalid="ignore"):
        den = B * B - 4 * A * C
        cx = (2 * C * D - B * E) / den
        cy = (2 * A * E - B * D) / den
        F0 = A * cx * cx + B * cx * cy + C * cy * cy + D * cx + E * cy + Fc
        tr = A + C
        dif = np.sqrt(np.maximum((A - C) ** 2 + B * B, 0))
        l1 = (tr + dif) / 2 / -F0
        l2 = (tr - dif) / 2 / -F0
        ok = (den < 0) & (l1 > 0) & (l2 > 0)
        a = 1 / np.sqrt(np.maximum(np.minimum(l1, l2), 1e-30))
        b = 1 / np.sqrt(np.maximum(np.maximum(l1, l2), 1e-30))
    return cx, cy, a, b, ok


def _sampson(c, pts):
    A, B, C, D, E, Fc = [c[:, i:i + 1] for i in range(6)]
    x, y = pts[None, :, 0], pts[None, :, 1]
    f = A * x * x + B * x * y + C * y * y + D * x + E * y + Fc
    fx = 2 * A * x + B * y + D
    fy = B * x + 2 * C * y + E
    gn = np.maximum(np.sqrt(fx * fx + fy * fy), 1e-12)
    return np.abs(f) / gn, fx / gn, fy / gn


def _edge_points(small):
    g = gaussian_blur(small, 1.5, 5)
    edges = canny(g, 40, 100)
    gx, gy = sobel(g)
    ys, xs = np.nonzero(edges)
    pts = np.stack([xs, ys], 1).astype(np.float64)
    gn = np.maximum(np.hypot(gx[ys, xs], gy[ys, xs]), 1e-9)
    nrm = np.stack([gx[ys, xs] / gn, gy[ys, xs] / gn], 1)
    _, comps = label_components(edges, min_size=25)
    segs = [np.stack([xs_, ys_], 1).astype(np.float64) for ys_, xs_ in comps]
    return pts, nrm, segs


def _score(c, pts, nrm, n_bins=120, tol=2.0):
    d, nx, ny = _sampson(c, pts)
    cos = np.abs(nx * nrm[None, :, 0] + ny * nrm[None, :, 1])
    inl = (d < tol) & (cos > 0.85)
    cx, cy, _, _, _ = _params(c)
    ang = np.arctan2(pts[None, :, 1] - cy[:, None], pts[None, :, 0] - cx[:, None])
    bins = np.clip(((ang + math.pi) / (2 * math.pi) * n_bins).astype(np.int64), 0, n_bins - 1)
    Bn = c.shape[0]
    idx = (np.arange(Bn)[:, None] * n_bins + bins)[inl]
    occ = np.bincount(idx, minlength=Bn * n_bins).reshape(Bn, n_bins)
    return (occ > 0).sum(1).astype(np.float64), inl.sum(1)


def detect_rim(gray: np.ndarray, n_starts: int = 3, n_hyp: int = 4000, seed: int = 0, **kw):
    """다중 시작 RANSAC: 시드별로 타원을 찾고, 둘레 엣지 점수(edge_score)가 가장 높은 것을 채택."""
    small, s = to_work(gray)
    edge_data = _edge_points(small)
    runs = []
    for k in range(n_starts):
        try:
            runs.append(_detect_rim_once(small, s, edge_data, n_hyp=n_hyp, seed=seed + k, **kw))
        except RuntimeError:
            pass
    if not runs:
        raise RuntimeError("테두리 검출 실패")
    el, info = max(runs, key=lambda r: r[1]["edge_score"])
    info["n_starts"] = n_starts
    info["start_scores"] = [round(r[1]["edge_score"], 2) for r in runs]
    return el, info


def _detect_rim_once(small, s, edge_data, n_hyp: int = 4000, seed: int = 0, score_pts: int = 6000,
                     n_refine: int = 60, polish: bool = True):
    H, W = small.shape
    pts, nrm, segs = edge_data
    if not segs:
        raise RuntimeError("엣지가 검출되지 않음")
    rng = np.random.default_rng(seed)
    # 점수 계산용 점 균등 추출 (속도)
    if len(pts) > score_pts:
        sel = rng.choice(len(pts), score_pts, replace=False)
        pts_s, nrm_s = pts[sel], nrm[sel]
    else:
        pts_s, nrm_s = pts, nrm
    # 가설: 1~3개 엣지 조각에서 7점 (벡터화)
    seg_len = np.array([len(sg) for sg in segs])
    seg_off = np.concatenate([[0], np.cumsum(seg_len)[:-1]])
    allp = np.concatenate(segs)
    p_seg = seg_len / seg_len.sum()
    ks = rng.integers(1, 4, n_hyp)
    ids = rng.choice(len(segs), size=(n_hyp, 3), p=p_seg)
    jj = np.arange(7)[None, :] % ks[:, None]
    sid = np.take_along_axis(ids, jj, 1)
    pidx = seg_off[sid] + (rng.random((n_hyp, 7)) * seg_len[sid]).astype(np.int64)
    P = allp[pidx]
    mu = np.array([W / 2, H / 2])
    sc = max(W, H) / 2
    c = _conic_batch((P - mu) / sc)
    A, B, C, D, E, Fc = [c[:, i] for i in range(6)]
    m0, m1 = mu
    A2, B2, C2 = A / sc ** 2, B / sc ** 2, C / sc ** 2
    D2 = D / sc - 2 * A2 * m0 - B2 * m1
    E2 = E / sc - 2 * C2 * m1 - B2 * m0
    F2 = A2 * m0 ** 2 + B2 * m0 * m1 + C2 * m1 ** 2 - D / sc * m0 - E / sc * m1 + Fc
    c = np.stack([A2, B2, C2, D2, E2, F2], -1)
    c /= np.linalg.norm(c, axis=1, keepdims=True)
    cx, cy, a, b, ok = _params(c)
    m = min(H, W)
    with np.errstate(invalid="ignore"):
        ok = ok & (a > 0.25 * m) & (a < 1.2 * max(H, W)) & (b / a > 0.15)   # 75° 내려다봐도 0.26
        ok = ok & (cx > 0.1 * W) & (cx < 0.9 * W) & (cy > 0.1 * H) & (cy < 0.9 * H)
    c = c[ok]
    if len(c) == 0:
        raise RuntimeError("타원 후보가 없음")
    cover = np.concatenate([_score(c[i:i + 128], pts_s, nrm_s)[0] for i in range(0, len(c), 128)])
    top = np.argsort(-cover, kind="stable")[:n_refine]
    best = []
    for idx in top:
        el = None
        cc = c[idx:idx + 1]
        for _ in range(4):
            d, nx, ny = _sampson(cc, pts)
            cos = np.abs(nx * nrm[None, :, 0] + ny * nrm[None, :, 1])
            inl = ((d < 2.5) & (cos > 0.85))[0]
            if inl.sum() < 20:
                break
            el = fit_ellipse(pts[inl])
            if el is None:
                break
            cc = el.coef6()[None]
        if el is None:
            continue
        cov, n_in = _score(cc, pts, nrm)
        best.append((float(cov[0]), int(n_in[0]), el))
    if not best:
        raise RuntimeError("테두리 검출 실패")
    best.sort(key=lambda t: -t[0])
    ref = best[0][2]
    cands = [t for t in best if t[0] >= 0.8 * best[0][0]
             and math.hypot(t[2].cx - ref.cx, t[2].cy - ref.cy) < 0.08 * ref.a
             and abs(t[2].ratio - ref.ratio) < 0.08]
    pick = max(cands, key=lambda t: t[2].a)
    el = pick[2]
    edge_score = None
    if polish:
        el, edge_score = polish_ellipse(small, el)
    return el.scaled(1 / s), dict(coverage=pick[0] / 120.0, n_candidates=len(best), scale=s,
                                  edge_score=edge_score)


def ellipse_edge_score(gx, gy, el: Ellipse, n=360):
    """타원 둘레를 따라 법선방향 밝기 기울기의 평균 |·| (화면 밖 제외)."""
    t = np.linspace(0, 2 * math.pi, n, endpoint=False)
    c, s = math.cos(el.phi), math.sin(el.phi)
    x0, y0 = el.a * np.cos(t), el.b * np.sin(t)
    px = el.cx + c * x0 - s * y0
    py = el.cy + s * x0 + c * y0
    # 법선 (타원 음함수 기울기)
    nxl, nyl = np.cos(t) / el.a, np.sin(t) / el.b
    nx = c * nxl - s * nyl
    ny = s * nxl + c * nyl
    nn = np.hypot(nx, ny); nx /= nn; ny /= nn
    H, W = gx.shape
    ok = (px >= 1) & (px <= W - 2) & (py >= 1) & (py <= H - 2)
    if ok.sum() < n * 0.3:
        return 0.0
    v = np.abs(bilinear(gx, px, py) * nx + bilinear(gy, px, py) * ny)
    return float(v[ok].mean())


def polish_ellipse(g, el: Ellipse, iters=60):
    """국소 최적화(좌표 하강)로 타원을 가장 강한 단일 테두리 엣지에 밀착."""
    gx, gy = sobel(gaussian_blur(g, 1.0))
    p = np.array([el.cx, el.cy, el.a, el.b, el.phi])
    steps = np.array([2.0, 2.0, 2.0, 2.0, math.radians(1.0)])
    f = ellipse_edge_score(gx, gy, el)
    for _ in range(iters):
        improved = False
        for k in range(5):
            for sgn in (1, -1):
                q = p.copy(); q[k] += sgn * steps[k]
                if q[3] > q[2] * 1.0001 or q[3] <= 0:
                    continue
                fq = ellipse_edge_score(gx, gy, Ellipse(*q))
                if fq > f:
                    p, f, improved = q, fq, True
        if not improved:
            steps *= 0.5
            if steps[0] < 0.1:
                break
    return Ellipse(*p), f


def refine_rim_from_seed(gray: np.ndarray, seed: Ellipse):
    """실시간 검출(앱 미리보기)로 얻은 타원을 원 해상도에서 국소 정밀화.
    반환: (타원, info). 전역 RANSAC 보다 수십 배 빠름."""
    small, s = to_work(gray)
    el = seed.scaled(s)
    pts, nrm, _ = _edge_points(small)
    # 1) seed 주변 엣지로 재피팅 (띠를 점점 좁힘) — 실시간 검출의 수 % 오차 제거
    # 실시간 seed 는 이미 관 내경(안쪽 테두리) → 바깥 테두리(보통 수 % 바깥)까지 넘지 않게 띠를 좁게 시작
    for tol in (max(3.0, 0.03 * el.a), max(2.0, 0.018 * el.a), max(1.5, 0.012 * el.a)):
        d, nx, ny = _sampson(el.coef6()[None], pts)
        cos = np.abs(nx * nrm[None, :, 0] + ny * nrm[None, :, 1])
        inl = ((d < tol) & (cos > 0.85))[0]
        if inl.sum() < 30:
            break
        e2 = fit_ellipse(pts[inl])
        if e2 is None or e2.b / e2.a < 0.1:
            break
        el = e2
    # 2) 둘레 엣지 강도로 미세 조정
    el, score = polish_ellipse(small, el)
    return el.scaled(1 / s), dict(source="live_seed", edge_score=score, scale=s)


def _coverage(el: Ellipse, pts, nrm, shape, tol, n_bins=120):
    """타원 둘레(화면 안) 중 엣지 inlier 로 확인된 각도 비율"""
    d, nx, ny = _sampson(el.coef6()[None], pts)
    cos = np.abs(nx * nrm[None, :, 0] + ny * nrm[None, :, 1])
    inl = ((d < tol) & (cos > 0.85))[0]
    c, s_ = math.cos(el.phi), math.sin(el.phi)
    dx, dy = pts[inl, 0] - el.cx, pts[inl, 1] - el.cy
    t = np.arctan2((-s_ * dx + c * dy) / el.b, (c * dx + s_ * dy) / el.a)
    occ = np.zeros(n_bins, bool)
    occ[np.clip(((t + math.pi) / (2 * math.pi) * n_bins).astype(np.int64), 0, n_bins - 1)] = True
    tt = -math.pi + (np.arange(n_bins) + 0.5) * 2 * math.pi / n_bins
    px = el.cx + c * el.a * np.cos(tt) - s_ * el.b * np.sin(tt)
    py = el.cy + s_ * el.a * np.cos(tt) + c * el.b * np.sin(tt)
    H, W = shape
    vis = (px >= 2) & (px < W - 2) & (py >= 2) & (py < H - 2)
    if vis.sum() < n_bins / 3:
        return 0.0
    return float((occ & vis).sum() / vis.sum())


def _ring_profile(e0, pts, nrm, shape, scales, tol, n_bins=120,
                  gs=(1.0, 0.97, 1.03, 0.94, 1.06, 0.91, 1.09, 0.88, 1.12), max_shift_frac=0.06):
    """e0 를 중심 기준으로 f배(장축)·f·g배(단축) 하고 단축 방향으로 d 만큼 옮긴 동심 타원들의 둘레 덮임 비율.
    점마다 '어느 배율의 타원 위에 있는지'를 한 번에 계산 → (g, d) 조합당 O(점 수).
    반환: prof[len(scales)], params[len(scales)] = (g, d)"""
    pts = np.asarray(pts, np.float64); nrm = np.asarray(nrm, np.float64)
    H, W = shape
    c, s_ = math.cos(e0.phi), math.sin(e0.phi)
    ux, uy = -s_, c
    f0, df = float(scales[0]), float(scales[1] - scales[0])
    nS = len(scales)
    tt = -math.pi + (np.arange(n_bins) + 0.5) * 2 * math.pi / n_bins
    step = 1.5 * tol
    ns = int(math.floor(max_shift_frac * e0.a / step))
    prof = np.zeros(nS); params = [(1.0, 0.0)] * nS
    for g in gs:
        bg = e0.b * g
        if bg > e0.a:
            continue
        for k in sorted(range(-ns, ns + 1), key=abs):
            d = step * k
            cx, cy = e0.cx + ux * d, e0.cy + uy * d
            dx, dy = pts[:, 0] - cx, pts[:, 1] - cy
            u = (c * dx + s_ * dy) / e0.a
            v = (-s_ * dx + c * dy) / bg
            rho = np.hypot(u, v)
            # 같은 배율족 타원의 법선(배율과 무관) · 화면상 거리
            gxl, gyl = u / e0.a, v / bg
            gn = np.maximum(np.hypot(gxl, gyl), 1e-12)
            nx, ny = (c * gxl - s_ * gyl) / gn, (s_ * gxl + c * gyl) / gn
            cosok = np.abs(nx * nrm[:, 0] + ny * nrm[:, 1]) > 0.85
            kf = np.rint((rho - f0) / df).astype(np.int64)
            ok = cosok & (kf >= 0) & (kf < nS)
            dist = np.abs(rho - (f0 + kf * df)) * np.maximum(rho, 1e-9) / gn
            ok &= dist < tol
            t = np.arctan2(v, u)
            tb = np.clip(((t + math.pi) / (2 * math.pi) * n_bins).astype(np.int64), 0, n_bins - 1)
            occ = np.zeros((nS, n_bins), bool)
            occ[kf[ok], tb[ok]] = True
            # 화면 안 bin
            fs = np.asarray(scales, np.float64)[:, None]
            xa, yb = fs * e0.a * np.cos(tt)[None], fs * bg * np.sin(tt)[None]
            px = cx + c * xa - s_ * yb; py = cy + s_ * xa + c * yb
            vis = (px >= 2) & (px < W - 2) & (py >= 2) & (py < H - 2)
            nv = vis.sum(1)
            cov = np.where(nv >= n_bins / 3, (occ & vis).sum(1) / np.maximum(nv, 1), 0.0)
            better = cov > prof + 1e-9
            prof = np.where(better, cov, prof)
            for i in np.nonzero(better)[0]:
                params[i] = (g, d)
    return prof, params


def select_inner_rim(gray: np.ndarray, el: Ellipse, min_scale: float = 0.72, max_scale: float = 1.38,
                     min_cov: float = 0.5, f_px=None, principal=None, _pass: int = 0):
    """관 내경(안쪽 테두리) 선택 — 관 두께 입력 불필요. (앱 LiveRimDetector.selectInner 와 같은 규칙)
    끝단 면이 보이면 바깥·안쪽 두 동심 테두리가 생김 → 안쪽을 씀.
    타원을 0.72~1.38배(+ 투시에 의한 단축 방향 중심 이동)로 바꿔가며 둘레 덮임 비율 측정:
    - 안쪽(≤0.97배)에 둘레 대부분이 확인되는 테두리 → 그것을 좁은 띠로 재피팅 (관 벽은 보통 얇아 우선)
    - 아니고 바깥(≥1.03배)에 확실한 테두리 → 검출 타원이 이미 안쪽
    - 둘 다 없으면 검출 타원 = 내경 (맨홀 벽에 묻혀 끝단 면이 안 보이는 경우 등)
    동심 테두리 후보: 장축 f배, 단축 f·g배(g 0.88~1.12: 투시로 안·밖 테두리의 납작함이 조금 다름), 단축 방향 중심 이동.
    (f_px, principal 은 호환용 — 현재 미사용)
    반환: (타원, info)"""
    small, s = to_work(gray)
    e0 = el.scaled(s)
    pts, nrm, _ = _edge_points(small)
    if len(pts) > 8000:
        sel = np.random.default_rng(0).choice(len(pts), 8000, replace=False)
        pts_s, nrm_s = pts[sel], nrm[sel]
    else:
        pts_s, nrm_s = pts, nrm
    tol = max(1.2, 0.01 * e0.a)
    ux, uy = -math.sin(e0.phi), math.cos(e0.phi)
    scales = np.round(np.arange(min_scale, max_scale + 1e-9, 0.01), 3)
    prof, params = _ring_profile(e0, pts_s, nrm_s, small.shape[:2], scales, tol)
    best_el = [Ellipse(e0.cx + ux * d, e0.cy + uy * d, e0.a * f, e0.b * g * f, e0.phi)
               for f, (g, d) in zip(scales.tolist(), params)]

    def is_peak(i):
        return 0 < i < len(scales) - 1 and prof[i] >= min_cov and prof[i] >= prof[i - 1] and prof[i] >= prof[i + 1]

    info = dict(base_cov=round(float(prof[np.argmin(np.abs(scales - 1.0))]), 2))
    near = [i for i in range(len(scales)) if abs(scales[i] - 1) <= 0.025 and is_peak(i)]
    i0 = max(near, key=lambda k: prof[k]) if near else int(np.argmin(np.abs(scales - 1.0)))
    be = best_el[i0]
    if _pass == 0 and be is not None and abs(be.b / (e0.b * scales[i0]) - 1) > 0.02 and prof[i0] > info["base_cov"] + 0.1:
        # 검출 타원이 두 테두리를 섞어 맞춘 것 → 더 잘 맞는 쪽으로 고친 뒤 한 번 더
        return select_inner_rim(gray, be.scaled(1 / s), min_scale=min_scale, max_scale=max_scale,
                                min_cov=min_cov, f_px=f_px, principal=principal, _pass=1)
    def peaks(lo, hi, thr):
        return [i for i in range(1, len(scales) - 1) if lo <= scales[i] <= hi and prof[i] >= thr
                and prof[i] >= prof[i - 1] and prof[i] >= prof[i + 1]]

    def refit(e, other):
        """다른 테두리(other)까지의 화면상 최소 간격을 넘지 않는 좁은 띠로 재피팅"""
        d, _, _ = _sampson(other.coef6()[None], e.points(90))
        gap = float(d.min())
        for t in (min(0.03 * e.a, 0.4 * gap), min(0.015 * e.a, 0.3 * gap), min(0.01 * e.a, 0.25 * gap)):
            t = max(t, 0.7)
            d, nx, ny = _sampson(e.coef6()[None], pts)
            cos = np.abs(nx * nrm[None, :, 0] + ny * nrm[None, :, 1])
            inl = ((d < t) & (cos > 0.85))[0]
            if inl.sum() < 30:
                break
            e2 = fit_ellipse(pts[inl])
            if e2 is None or e2.b / e2.a < 0.1:
                break
            e = e2
        return e, gap

    shape = small.shape[:2]
    e_ref = best_el[i0]
    up = peaks(scales[i0] + 0.03, max_scale, min_cov)
    iu = max(up, key=lambda k: prof[k]) if up else None
    # 안쪽 후보들(덮임 상위 4개)을 각각 재피팅 → 바깥 테두리로 되돌아가지 않고 덮임이 가장 큰 것
    dn = sorted(peaks(min_scale, scales[i0] - 0.03, 0.45), key=lambda k: -prof[k])[:4]
    best_in = None
    for k in dn:
        e_k, gap_k = refit(best_el[k], e_ref)
        d_back, _, _ = _sampson(e_ref.coef6()[None], e_k.points(90))
        if float(np.median(d_back)) < 0.5 * gap_k:        # 바깥 테두리로 되돌아감
            continue
        cov_k = _coverage(e_k, pts_s, nrm_s, shape, 0.8 * tol)
        if best_in is None or cov_k > best_in[0] + 1e-9:
            best_in = (cov_k, k, e_k, gap_k)
    if best_in is not None and best_in[0] >= 0.6 and (iu is None or best_in[0] >= prof[iu] - 0.05):
        _, i, e, gap = best_in
        gap_f = scales[i0] - scales[i]
        other = e_ref
        info["inner_source"] = "inner_ring"
    elif iu is not None and prof[iu] >= 0.7:
        i, gap_f = i0, scales[iu] - scales[i0]
        other = best_el[iu]
        e, gap = refit(e_ref, other)
        info["inner_source"] = "detected_is_inner"
    else:
        info["inner_source"] = "single_rim"
        return el, info
    f = float(scales[i])
    e = e.scaled(1 / s)
    other = other.scaled(1 / s)
    # 원 해상도에서 마지막 재피팅 (가파른 각도에선 두 테두리 간격이 몇 px 뿐이라 축소 영상으로는 부족)
    if s < 1:
        gf = gap / s
        H0, W0 = gray.shape[:2]
        x0 = int(max(0, e.cx - e.a - 4)); x1 = int(min(W0, e.cx + e.a + 5))
        y0 = int(max(0, e.cy - e.a - 4)); y1 = int(min(H0, e.cy + e.a + 5))
        crop = np.asarray(gray, np.float64)[y0:y1, x0:x1]
        if crop.size > 0:
            pf, nf, _ = _edge_points(crop)
            pf = pf + np.array([x0, y0])
            for t in (max(0.8, min(0.012 * e.a, 0.3 * gf)), max(0.7, min(0.008 * e.a, 0.25 * gf))):
                d, nx, ny = _sampson(e.coef6()[None], pf)
                cos = np.abs(nx * nf[None, :, 0] + ny * nf[None, :, 1])
                inl = ((d < t) & (cos > 0.85))[0]
                if inl.sum() < 40:
                    break
                e2 = fit_ellipse(pf[inl])
                if e2 is None or e2.b / e2.a < 0.1:
                    break
                e = e2
    info.update(gap_px=round(gap / s, 2), inner_scale=round(f, 3),
                inner_cov=round(float(prof[i]), 2), gap_scale=round(float(gap_f), 3))
    return e, info


# ---------------------------------------------------------------------------
# 2) 단면 복원 + 수면선
# ---------------------------------------------------------------------------
def rectify_image(gray: np.ndarray, el: Ellipse, out: int = 512):
    R = out / 2
    _, (Ainv, tinv) = rectify_affine(el, R)
    u = np.arange(out, dtype=np.float64) - out / 2 + 0.5
    U, V = np.meshgrid(u, u)
    q = np.stack([U, V], -1).reshape(-1, 2)
    p = q @ Ainv.T + tinv
    rect = bilinear(gray, p[:, 0], p[:, 1]).reshape(out, out)
    return rect, R, (Ainv, tinv)


def detect_waterline(rect: np.ndarray, R: float, max_angle_deg: float = 25.0,
                     inner: float = 0.85, texture_sign: int = +1, band: float = 0.25,
                     rho_range: float = 0.8, w_step: float = 2.0, w_edge: float = 1.0,
                     len_pow: float = 2.0, n_theta: int = 61, n_rho: int = 241, ns: int = 80,
                     theta_center_deg: float = 0.0):
    """복원 원 영상에서 수면선(현) 탐색 — 영역 텍스처 대비 + 극성 일관 경계."""
    out = rect.shape[0]
    g = gaussian_blur(rect, 1.2)
    gx, gy = sobel(g)
    energy = gaussian_blur(np.hypot(gx, gy), max(2.0, R * 0.015))
    thetas = np.deg2rad(theta_center_deg + np.linspace(-max_angle_deg, max_angle_deg, n_theta))
    rhos = np.linspace(-rho_range * R, rho_range * R, n_rho)
    lam = np.linspace(-1, 1, ns)
    c0 = out / 2 - 0.5
    offs = np.linspace(0.03, band, 6) * R

    def chunk(th_sub):
        """theta 일부에 대한 step/edge (메모리 절약용 분할 계산)."""
        T, P = np.meshgrid(th_sub, rhos, indexing="ij")
        nx, ny = np.sin(T)[..., None], np.cos(T)[..., None]
        tx, ty = np.cos(T)[..., None], -np.sin(T)[..., None]

        def sample_on(img2d, offset):
            rr = (P + offset)[..., None]
            half = np.sqrt(np.maximum((R * inner) ** 2 - rr ** 2, 0))
            v = bilinear(img2d, rr * nx + lam * half * tx + c0, rr * ny + lam * half * ty + c0)
            return v, (half[..., 0] > 0.15 * R).astype(np.float64)

        up = dn = vu = vd = 0.0
        for o in offs:
            a_, va = sample_on(energy, -o)
            b_, vb = sample_on(energy, +o)
            up = up + a_.mean(-1) * va; vu = vu + va
            dn = dn + b_.mean(-1) * vb; vd = vd + vb
        up = up / np.maximum(vu, 1); dn = dn / np.maximum(vd, 1)
        st = (dn - up) / (dn + up + 1e-9)
        st = np.abs(st) if texture_sign == 0 else st * texture_sign
        st = st * ((vu > 3) & (vd > 3))
        ed = None
        for o in (-1.5, 0.0, 1.5):
            gxs, _ = sample_on(gx, o)
            gys, _ = sample_on(gy, o)
            gn = gxs * nx + gys * ny
            m_abs = np.abs(gn).mean(-1)
            v = np.abs(gn.mean(-1)) / (m_abs + 1e-6) * np.sqrt(m_abs)
            ed = v if ed is None else np.maximum(ed, v)
        _, valid0 = sample_on(gy, 0.0)
        ed = ed * valid0 * np.sqrt(np.maximum(1 - (P / R) ** 2, 0)) ** len_pow
        return st, ed

    steps, edges_ = [], []
    for k0 in range(0, n_theta, 16):
        st, ed = chunk(thetas[k0:k0 + 16])
        steps.append(st); edges_.append(ed)
    step = np.concatenate(steps); edge = np.concatenate(edges_)
    edge = edge / (edge.max() + 1e-9)

    score = w_step * step + w_edge * edge
    i, j = np.unravel_index(int(np.argmax(score)), score.shape)
    th, rho = float(thetas[i]), float(rhos[j])
    if 0 < j < score.shape[1] - 1:
        s0, s1, s2 = score[i, j - 1], score[i, j], score[i, j + 1]
        den = s0 - 2 * s1 + s2
        if den < 0:
            rho += 0.5 * (s0 - s2) / den * (rhos[1] - rhos[0])
    flat = score.ravel()
    alts = []
    for kk in np.argsort(-flat, kind="stable")[:5000]:
        ii, jj = np.unravel_index(int(kk), score.shape)
        r_ = rhos[jj]
        if abs(r_ - rho) > 0.1 * R and all(abs(r_ - a[1] * R) > 0.1 * R for a in alts):
            alts.append((math.degrees(thetas[ii]), float(r_ / R), float(flat[kk])))
        if len(alts) >= 2:
            break
    best = float(score[i, j])
    second = alts[0][2] if alts else 0.0
    return dict(theta=th, rho=float(rho), score=best,
                confidence=(best - second) / (abs(best) + 1e-9), alternatives=alts)
