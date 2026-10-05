"""
이미지 검출 모듈
 1) 관로 끝단 테두리(rim) 타원 자동 검출  : Canny 엣지 + 곡선 조각 기반 RANSAC (torch 배치 연산)
 2) 수면선(waterline) 검출                : 원으로 복원(rectify)한 영상에서 직선 Radon/Hough 탐색 (torch grid_sample)
"""
from __future__ import annotations

import math

import cv2
import numpy as np
import torch
import torch.nn.functional as F

from .geometry import DTYPE, Ellipse, fit_ellipse, rectify_affine

WORK_SIZE = 720  # 내부 처리 해상도 (긴 변 px)


# ---------------------------------------------------------------------------
# 이미지 유틸
# ---------------------------------------------------------------------------
def load_image(path_or_array) -> np.ndarray:
    if isinstance(path_or_array, np.ndarray):
        img = path_or_array
    else:
        data = np.fromfile(str(path_or_array), dtype=np.uint8)
        img = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError(f"이미지를 읽을 수 없음: {path_or_array}")
    return img


def to_work(img: np.ndarray):
    s = WORK_SIZE / max(img.shape[:2])
    s = min(s, 1.0)
    small = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA) if s < 1 else img.copy()
    return small, s


def sobel_torch(gray: torch.Tensor):
    """gray (H,W) float -> gx, gy (H,W)."""
    kx = torch.tensor([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]], dtype=gray.dtype) / 8
    ky = kx.T.contiguous()
    g = gray[None, None]
    g = F.pad(g, (1, 1, 1, 1), mode="replicate")
    gx = F.conv2d(g, kx[None, None])[0, 0]
    gy = F.conv2d(g, ky[None, None])[0, 0]
    return gx, gy


# ---------------------------------------------------------------------------
# 1) Rim 타원 검출
# ---------------------------------------------------------------------------
def _conic_through_points_batch(P: torch.Tensor) -> torch.Tensor:
    """P (B,k,2), k>=5 -> conic 계수 (B,6) [A,B,C,D,E,F] (최소제곱 null-space)."""
    x, y = P[..., 0], P[..., 1]
    M = torch.stack([x * x, x * y, y * y, x, y, torch.ones_like(x)], -1)
    _, _, Vh = torch.linalg.svd(M, full_matrices=True)
    return Vh[:, -1, :]


def _batch_conic_to_params(c: torch.Tensor):
    A, B, C, D, E, Fc = c.unbind(-1)
    den = B * B - 4 * A * C
    ok = den < 0
    cx = (2 * C * D - B * E) / den
    cy = (2 * A * E - B * D) / den
    F0 = A * cx * cx + B * cx * cy + C * cy * cy + D * cx + E * cy + Fc
    # 2x2 고유값
    tr = A + C
    dif = torch.sqrt(((A - C) ** 2 + B * B).clamp_min(0))
    l1 = (tr + dif) / 2 / -F0
    l2 = (tr - dif) / 2 / -F0
    ok = ok & (l1 > 0) & (l2 > 0)
    a = 1 / torch.sqrt(torch.minimum(l1, l2).clamp_min(1e-30))
    b = 1 / torch.sqrt(torch.maximum(l1, l2).clamp_min(1e-30))
    return cx, cy, a, b, ok


def _sampson(c: torch.Tensor, pts: torch.Tensor) -> torch.Tensor:
    """c (B,6), pts (N,2) -> |sampson distance| (B,N)."""
    A, B, C, D, E, Fc = [t[:, None] for t in c.unbind(-1)]
    x, y = pts[None, :, 0], pts[None, :, 1]
    f = A * x * x + B * x * y + C * y * y + D * x + E * y + Fc
    fx = 2 * A * x + B * y + D
    fy = B * x + 2 * C * y + E
    gn = torch.sqrt(fx * fx + fy * fy).clamp_min(1e-12)
    return f.abs() / gn, fx / gn, fy / gn


def _edge_points(small: np.ndarray):
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 1.5)
    edges = cv2.Canny(gray, 40, 100)
    g = torch.from_numpy(gray.astype(np.float64))
    gx, gy = sobel_torch(g)
    ys, xs = np.nonzero(edges)
    pts = torch.tensor(np.stack([xs, ys], 1), dtype=DTYPE)
    gxs, gys = gx[ys, xs], gy[ys, xs]
    gn = torch.sqrt(gxs**2 + gys**2).clamp_min(1e-9)
    nrm = torch.stack([gxs / gn, gys / gn], 1)
    # 곡선 조각 (contour)
    cnts, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    segs = [c[:, 0, :].astype(np.float64) for c in cnts if len(c) >= 25]
    return pts, nrm, segs, edges


def _score(c, pts, nrm, shape, n_bins=120, tol=2.0):
    """가설 타원 conic c(B,6) 의 점수: 엣지 inlier 가 덮는 각도 bin 수 (화면 안 부분 기준)."""
    d, nx, ny = _sampson(c, pts)
    cos = (nx * nrm[None, :, 0] + ny * nrm[None, :, 1]).abs()
    inl = (d < tol) & (cos > 0.85)
    cx, cy, a, b, ok = _batch_conic_to_params(c)
    ang = torch.atan2(pts[None, :, 1] - cy[:, None], pts[None, :, 0] - cx[:, None])
    bins = ((ang + math.pi) / (2 * math.pi) * n_bins).long().clamp(0, n_bins - 1)
    occ = torch.zeros(c.shape[0], n_bins, dtype=DTYPE)
    occ.scatter_add_(1, bins, inl.to(DTYPE))
    cover = (occ > 0).sum(1).to(DTYPE)
    return cover, inl.sum(1), ok


def detect_rim(img: np.ndarray, n_hyp: int = 3000, seed: int = 0) -> tuple[Ellipse, dict]:
    """관로 끝단 테두리(가장 바깥의 큰 타원)를 검출. 반환 타원은 원본 해상도 좌표."""
    small, s = to_work(img)
    H, W = small.shape[:2]
    pts, nrm, segs, edges = _edge_points(small)
    rng = np.random.default_rng(seed)
    if len(segs) == 0:
        raise RuntimeError("엣지가 검출되지 않음")
    seg_len = np.array([len(sg) for sg in segs], dtype=np.float64)
    p_seg = seg_len / seg_len.sum()

    # --- 가설 생성: 1~3개 곡선 조각에서 7점 샘플 ---------------------------
    hyps = []
    for _ in range(n_hyp):
        k = rng.integers(1, 4)
        ids = rng.choice(len(segs), size=k, p=p_seg)
        sel = []
        for j in range(7):
            sg = segs[ids[j % k]]
            sel.append(sg[rng.integers(len(sg))])
        hyps.append(sel)
    P = torch.tensor(np.array(hyps), dtype=DTYPE)
    # 정규화 후 conic 피팅
    mu = torch.tensor([W / 2, H / 2], dtype=DTYPE)
    sc = max(W, H) / 2
    c = _conic_through_points_batch((P - mu) / sc)
    # 정규화 해제
    A, B, C, D, E, Fc = c.unbind(-1)
    m0, m1 = mu.tolist()
    A2, B2, C2 = A / sc**2, B / sc**2, C / sc**2
    D2 = D / sc - 2 * A2 * m0 - B2 * m1
    E2 = E / sc - 2 * C2 * m1 - B2 * m0
    F2 = A2 * m0**2 + B2 * m0 * m1 + C2 * m1**2 - D / sc * m0 - E / sc * m1 + Fc
    c = torch.stack([A2, B2, C2, D2, E2, F2], -1)
    c = c / c.norm(dim=1, keepdim=True)

    cx, cy, a, b, ok = _batch_conic_to_params(c)
    m = min(H, W)
    ok = ok & (a > 0.25 * m) & (a < 1.2 * max(H, W)) & (b / a > 0.3)
    ok = ok & (cx > 0.1 * W) & (cx < 0.9 * W) & (cy > 0.1 * H) & (cy < 0.9 * H)
    c = c[ok]
    if len(c) == 0:
        raise RuntimeError("타원 후보가 없음")
    # --- 점수 계산 (chunk) ------------------------------------------------
    covers = []
    for i in range(0, len(c), 256):
        cov, _, _ = _score(c[i:i + 256], pts, nrm, (H, W))
        covers.append(cov)
    cover = torch.cat(covers)
    top = torch.argsort(cover, descending=True)[:40]

    # --- 상위 후보 정제 (inlier 재피팅 반복) ---------------------------------
    best = []
    for idx in top.tolist():
        el = None
        cc = c[idx:idx + 1]
        for _ in range(4):
            d, nx, ny = _sampson(cc, pts)
            cos = (nx * nrm[None, :, 0] + ny * nrm[None, :, 1]).abs()
            inl = ((d < 2.5) & (cos > 0.85))[0]
            if inl.sum() < 20:
                break
            el = fit_ellipse(pts[inl])
            if el is None:
                break
            cc = el.conic()
            cc = torch.stack([cc[0, 0], 2 * cc[0, 1], cc[1, 1], 2 * cc[0, 2], 2 * cc[1, 2], cc[2, 2]])[None]
            cc = cc / cc.norm()
        if el is None:
            continue
        cov, n_in, _ = _score(cc, pts, nrm, (H, W))
        best.append((cov.item(), n_in.item(), el))
    if not best:
        raise RuntimeError("rim 검출 실패")
    best.sort(key=lambda t: -t[0])
    top_cov = best[0][0]
    # 동심 후보(유리 두께의 내/외벽) 중 가장 바깥 것 선택
    ref = best[0][2]
    cands = [t for t in best if t[0] >= 0.8 * top_cov
             and math.hypot(t[2].cx - ref.cx, t[2].cy - ref.cy) < 0.08 * ref.a
             and abs(t[2].ratio - ref.ratio) < 0.08]
    el = max(cands, key=lambda t: t[2].a)[2]
    el_full = Ellipse(el.cx / s, el.cy / s, el.a / s, el.b / s, el.phi)
    info = dict(coverage=max(cands, key=lambda t: t[2].a)[0] / 120.0,
                n_candidates=len(best), scale=s)
    return el_full, info


# ---------------------------------------------------------------------------
# 2) 수면선 검출
# ---------------------------------------------------------------------------
def rectify_image(img: np.ndarray, el: Ellipse, out: int = 512, margin: float = 1.0):
    """타원 내부를 원(반지름 = out/2 px)으로 복원한 영상. 반환: (rect_img, map: rect px -> img px affine)."""
    R = out / 2 / margin
    (A, t), (Ainv, tinv) = rectify_affine(el, R)
    # rect 좌표 (u,v) 에서 원 중심이 (out/2,out/2)
    u = torch.arange(out, dtype=DTYPE) - out / 2 + 0.5
    V, U = torch.meshgrid(u, u, indexing="ij")
    q = torch.stack([U, V], -1).reshape(-1, 2)
    p = q @ Ainv.T + tinv  # 이미지 좌표
    H, W = img.shape[:2]
    grid = torch.stack([p[:, 0] / (W - 1) * 2 - 1, p[:, 1] / (H - 1) * 2 - 1], -1)
    grid = grid.reshape(1, out, out, 2).float()
    src = torch.from_numpy(img).permute(2, 0, 1)[None].float()
    rect = F.grid_sample(src, grid, mode="bilinear", align_corners=True, padding_mode="zeros")
    rect = rect[0].permute(1, 2, 0).clamp(0, 255).byte().numpy()
    return rect, R, (Ainv, tinv)


def _gauss_blur(t: torch.Tensor, sigma: float) -> torch.Tensor:
    r = int(3 * sigma)
    x = torch.arange(-r, r + 1, dtype=t.dtype)
    k = torch.exp(-x**2 / (2 * sigma**2)); k = k / k.sum()
    t = F.pad(t[None, None], (r, r, r, r), mode="replicate")
    t = F.conv2d(t, k[None, None, None, :])
    t = F.conv2d(t, k[None, None, :, None])
    return t[0, 0]


def detect_waterline(rect: np.ndarray, R: float, max_angle_deg: float = 25.0,
                     inner: float = 0.85, texture_sign: int = +1, band: float = 0.25,
                     rho_range: float = 0.8, w_step: float = 2.0, w_edge: float = 1.0,
                     len_pow: float = 2.0):
    """복원 원 영상에서 수면선(현, chord) 탐색.

    후보 직선: 법선각 θ(이미지 수직 기준 ±max_angle), 원 중심으로부터 부호거리 ρ (아래 +).
    점수 = (A) 영역 대비(step) + (B) 직선 엣지 강도
      (A) 직선 양쪽 band(폭 band·R) 의 '텍스처 에너지'(평활한 |∇I|) 평균 차이
          texture_sign=+1 : 물 쪽(아래)이 더 거친 경우 (랩 주름/굴절로 줄무늬가 보이는 현재 장치)
          texture_sign=-1 : 물 쪽이 더 매끈한 경우,  0 : 부호 무관
      (B) 직선을 따라 법선방향 밝기 기울기의 35% 분위수 (현 전체에 걸쳐 이어진 엣지일수록 큼)
    반환 dict(theta, rho [rect px, 원중심 원점, y 아래 +], score, alternatives)"""
    out = rect.shape[0]
    gray = cv2.cvtColor(rect, cv2.COLOR_BGR2GRAY).astype(np.float64)
    g = _gauss_blur(torch.from_numpy(gray), 1.2)
    gx, gy = sobel_torch(g)
    energy = _gauss_blur(torch.sqrt(gx**2 + gy**2), max(2.0, R * 0.015))

    thetas = torch.deg2rad(torch.linspace(-max_angle_deg, max_angle_deg, 101, dtype=DTYPE))
    rhos = torch.linspace(-rho_range * R, rho_range * R, 321, dtype=DTYPE)
    T, P = torch.meshgrid(thetas, rhos, indexing="ij")
    nx, ny = torch.sin(T), torch.cos(T)            # 법선 (물 쪽 = +)
    tx, ty = torch.cos(T), -torch.sin(T)           # 접선
    ns = 96
    lam = torch.linspace(-1, 1, ns, dtype=DTYPE)
    c0 = out / 2 - 0.5

    def sample_on(img2d, offset):
        """직선을 법선방향으로 offset 만큼 이동한 현 위에서 샘플 (T,P,ns)."""
        rr = P + offset
        half = torch.sqrt(((R * inner) ** 2 - rr**2).clamp_min(0))
        X = rr[..., None] * nx[..., None] + lam * half[..., None] * tx[..., None]
        Y = rr[..., None] * ny[..., None] + lam * half[..., None] * ty[..., None]
        grid = torch.stack([(X + c0) / (out - 1) * 2 - 1, (Y + c0) / (out - 1) * 2 - 1], -1)
        v = F.grid_sample(img2d[None, None].float(), grid.reshape(1, -1, ns, 2).float(),
                          align_corners=True)[0, 0].reshape(X.shape).to(DTYPE)
        valid = (half > 0.15 * R).to(DTYPE)
        return v, valid

    # (A) 영역 대비
    offs = torch.linspace(0.03, band, 6, dtype=DTYPE) * R
    up, dn, vu, vd = 0, 0, 0, 0
    for o in offs.tolist():
        a, va = sample_on(energy, -o)
        b, vb = sample_on(energy, +o)
        up = up + (a.mean(-1) * va); vu = vu + va
        dn = dn + (b.mean(-1) * vb); vd = vd + vb
    up = up / vu.clamp_min(1); dn = dn / vd.clamp_min(1)
    step = (dn - up) / (dn + up + 1e-9)
    if texture_sign == 0:
        step = step.abs()
    else:
        step = step * texture_sign
    step = step * ((vu > 3) & (vd > 3)).to(DTYPE)

    # (B) 직선 엣지
    # (B) 직선 엣지: 현 전체에서 '극성(밝→어 / 어→밝)이 일관된' 경계일수록 큼
    #     (랩 주름은 극성이 뒤섞여 평균이 상쇄됨). ±1.5px 법선 오프셋 중 최대.
    edge = None
    for o in (-1.5, 0.0, 1.5):
        gxs, _ = sample_on(gx, o)
        gys, valid0 = sample_on(gy, o)
        gn = gxs * nx[..., None] + gys * ny[..., None]
        m_abs = gn.abs().mean(-1)
        v = gn.mean(-1).abs() / (m_abs + 1e-6) * m_abs.sqrt()
        edge = v if edge is None else torch.maximum(edge, v)
    _, valid0 = sample_on(gy, 0.0)
    edge = edge * valid0 * torch.sqrt((1 - (P / R) ** 2).clamp_min(0)) ** len_pow
    edge = edge / (edge.max() + 1e-9)

    score = w_step * step + w_edge * edge
    k = torch.argmax(score)
    i, j = divmod(k.item(), score.shape[1])
    th, rho = thetas[i].item(), rhos[j].item()
    if 0 < j < score.shape[1] - 1:
        s0, s1, s2 = score[i, j - 1].item(), score[i, j].item(), score[i, j + 1].item()
        den = s0 - 2 * s1 + s2
        if den < 0:
            rho += 0.5 * (s0 - s2) / den * (rhos[1] - rhos[0]).item()
    # 다른 위치의 차순위 후보 (사용자 확인용)
    flat = score.flatten()
    alts = []
    for kk in torch.argsort(flat, descending=True).tolist():
        ii, jj = divmod(kk, score.shape[1])
        r_ = rhos[jj].item()
        if abs(r_ - rho) > 0.1 * R and all(abs(r_ - a[1] * R) > 0.1 * R for a in alts):
            alts.append((math.degrees(thetas[ii].item()), r_ / R, flat[kk].item()))
        if len(alts) >= 2:
            break
    second = alts[0][2] if alts else 0.0
    return dict(theta=th, rho=rho, score=score[i, j].item(),
                confidence=(score[i, j].item() - second) / (abs(score[i, j].item()) + 1e-9),
                step=step[i, j].item(), edge=edge[i, j].item(), alternatives=alts)
