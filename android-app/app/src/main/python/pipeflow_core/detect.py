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
        ok = ok & (a > 0.25 * m) & (a < 1.2 * max(H, W)) & (b / a > 0.3)
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
