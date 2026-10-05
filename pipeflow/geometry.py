"""
기하 계산 모듈 (PyTorch)
 - 타원 피팅 (Fitzgibbon direct least squares)
 - conic <-> (중심, 반축, 회전각) 변환
 - 약투시(weak perspective) 가정 하 카메라 기울기 / 원 복원(rectification)
 - 완전투시(perspective) 원 자세 추정 (초점거리 f 를 알 때)
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import torch

DTYPE = torch.float64


# ---------------------------------------------------------------------------
# 타원 표현
# ---------------------------------------------------------------------------
@dataclass
class Ellipse:
    cx: float
    cy: float
    a: float      # 장반축 (px)
    b: float      # 단반축 (px)
    phi: float    # 장축이 이미지 x축과 이루는 각 (rad)

    # --- 파생 값 -----------------------------------------------------------
    @property
    def ratio(self) -> float:
        return self.b / self.a

    @property
    def tilt_deg(self) -> float:
        """원 단면 법선과 카메라 광축 사이 각 (약투시 근사)."""
        return math.degrees(math.acos(max(-1.0, min(1.0, self.ratio))))

    @property
    def minor_dir(self) -> tuple[float, float]:
        """단축 방향 단위벡터 (카메라가 기울어진 방향)."""
        return (-math.sin(self.phi), math.cos(self.phi))

    def conic(self) -> torch.Tensor:
        """3x3 대칭 conic 행렬 C (x^T C x = 0, x=[u,v,1])."""
        c, s = math.cos(self.phi), math.sin(self.phi)
        R = torch.tensor([[c, -s], [s, c]], dtype=DTYPE)
        D = torch.diag(torch.tensor([1 / self.a**2, 1 / self.b**2], dtype=DTYPE))
        M = R @ D @ R.T
        t = torch.tensor([self.cx, self.cy], dtype=DTYPE)
        C = torch.zeros(3, 3, dtype=DTYPE)
        C[:2, :2] = M
        C[:2, 2] = -M @ t
        C[2, :2] = -M @ t
        C[2, 2] = t @ M @ t - 1.0
        return C

    def points(self, n: int = 360) -> torch.Tensor:
        t = torch.linspace(0, 2 * math.pi, n, dtype=DTYPE)
        c, s = math.cos(self.phi), math.sin(self.phi)
        x = self.a * torch.cos(t)
        y = self.b * torch.sin(t)
        return torch.stack([self.cx + c * x - s * y, self.cy + s * x + c * y], -1)

    def algebraic_distance(self, pts: torch.Tensor) -> torch.Tensor:
        """근사 기하 거리 (Sampson distance, px)."""
        C = self.conic()
        x = torch.cat([pts.to(DTYPE), torch.ones(len(pts), 1, dtype=DTYPE)], 1)
        f = ((x @ C) * x).sum(1)
        g = 2 * (x @ C)[:, :2]
        return f.abs() / g.norm(dim=1).clamp_min(1e-12)

    def to_dict(self) -> dict:
        return dict(cx=self.cx, cy=self.cy, a=self.a, b=self.b,
                    phi_deg=math.degrees(self.phi), ratio=self.ratio,
                    tilt_deg=self.tilt_deg)


# ---------------------------------------------------------------------------
# 타원 피팅
# ---------------------------------------------------------------------------
def fit_ellipse(pts: torch.Tensor, weights: torch.Tensor | None = None) -> Ellipse | None:
    """Fitzgibbon(1999) direct least-squares 타원 피팅 (Halir & Flusser 안정화 버전).
    pts: (N,2) 이미지 좌표."""
    pts = pts.to(DTYPE)
    if len(pts) < 5:
        return None
    # 수치 안정을 위한 정규화
    mu = pts.mean(0)
    sc = (pts - mu).abs().mean().clamp_min(1e-9)
    p = (pts - mu) / sc
    x, y = p[:, 0], p[:, 1]
    D1 = torch.stack([x * x, x * y, y * y], 1)
    D2 = torch.stack([x, y, torch.ones_like(x)], 1)
    if weights is not None:
        w = weights.to(DTYPE).sqrt()[:, None]
        D1, D2 = D1 * w, D2 * w
    S1, S2, S3 = D1.T @ D1, D1.T @ D2, D2.T @ D2
    try:
        T = -torch.linalg.solve(S3, S2.T)
    except RuntimeError:
        return None
    M = S1 + S2 @ T
    M = torch.stack([M[2] / 2, -M[1], M[0] / 2])
    evals, evecs = torch.linalg.eig(M)
    evecs = evecs.real
    cond = 4 * evecs[0] * evecs[2] - evecs[1] ** 2
    idx = torch.nonzero(cond > 0).flatten()
    if len(idx) == 0:
        return None
    a1 = evecs[:, idx[0]]
    coef = torch.cat([a1, T @ a1])  # A B C D E F (정규화 좌표)
    A, B, Cc, Dd, E, F = coef.tolist()
    # 정규화 해제: x = (X-mu)/sc
    m0, m1, s = mu[0].item(), mu[1].item(), sc.item()
    A2, B2, C2 = A / s**2, B / s**2, Cc / s**2
    D2_ = Dd / s - 2 * A2 * m0 - B2 * m1
    E2_ = E / s - 2 * C2 * m1 - B2 * m0
    F2_ = (A2 * m0**2 + B2 * m0 * m1 + C2 * m1**2
           - Dd / s * m0 - E / s * m1 + F)
    return conic_to_ellipse(A2, B2, C2, D2_, E2_, F2_)


def conic_to_ellipse(A, B, C, D, E, F) -> Ellipse | None:
    """Ax^2+Bxy+Cy^2+Dx+Ey+F=0 -> Ellipse."""
    den = B * B - 4 * A * C
    if den >= 0:
        return None
    cx = (2 * C * D - B * E) / den
    cy = (2 * A * E - B * D) / den
    M = torch.tensor([[A, B / 2], [B / 2, C]], dtype=DTYPE)
    Fc = A * cx * cx + B * cx * cy + C * cy * cy + D * cx + E * cy + F
    if Fc == 0:
        return None
    ev, V = torch.linalg.eigh(M / -Fc)
    if (ev <= 0).any():
        return None
    # ev 작은 값 -> 장축
    a = 1 / math.sqrt(ev[0].item())
    b = 1 / math.sqrt(ev[1].item())
    vx, vy = V[0, 0].item(), V[1, 0].item()
    phi = math.atan2(vy, vx)
    # phi 를 (-90, 90] 로 정규화
    if phi > math.pi / 2:
        phi -= math.pi
    if phi <= -math.pi / 2:
        phi += math.pi
    return Ellipse(cx, cy, a, b, phi)


# ---------------------------------------------------------------------------
# 약투시 원 복원 (affine rectification)
# ---------------------------------------------------------------------------
def rectify_affine(el: Ellipse, R_world: float):
    """이미지 좌표 -> 단면 평면 좌표(mm)로 보내는 2x3 affine 행렬과 역행렬.
    결과 평면에서 원 중심이 원점, 반지름 R_world.
    (회전 1 자유도는 이미지 축을 최대한 유지하도록 선택)"""
    c, s = math.cos(el.phi), math.sin(el.phi)
    Rm = torch.tensor([[c, -s], [s, c]], dtype=DTYPE)
    S = torch.diag(torch.tensor([R_world / el.a, R_world / el.b], dtype=DTYPE))
    # world = Rm @ S @ Rm^T @ (img - c)  (장축/단축 방향 각각 스케일 후 원래 방향으로 회전)
    A = Rm @ S @ Rm.T
    t = -A @ torch.tensor([el.cx, el.cy], dtype=DTYPE)
    Ainv = torch.linalg.inv(A)
    tinv = torch.tensor([el.cx, el.cy], dtype=DTYPE)
    return (A, t), (Ainv, tinv)


def apply_affine(Aff, pts: torch.Tensor) -> torch.Tensor:
    A, t = Aff
    return pts.to(DTYPE) @ A.T + t


# ---------------------------------------------------------------------------
# 완전투시: 원 자세(pose) 추정
# ---------------------------------------------------------------------------
def intrinsics(f_px: float, cx: float, cy: float) -> torch.Tensor:
    return torch.tensor([[f_px, 0, cx], [0, f_px, cy], [0, 0, 1]], dtype=DTYPE)


def _orth_basis(n: torch.Tensor):
    a = torch.tensor([1.0, 0, 0], dtype=DTYPE)
    if abs(n[0]) > 0.9:
        a = torch.tensor([0, 1.0, 0], dtype=DTYPE)
    e1 = torch.linalg.cross(n, a)
    e1 = e1 / e1.norm()
    e2 = torch.linalg.cross(n, e1)
    return e1, e2


def circle_pose(el: Ellipse, K: torch.Tensor, R_world: float):
    """투영 타원 + 내부파라미터 K + 실제 반지름 -> 가능한 두 자세.
    반환: [(normal n, center C), ...]  (카메라 좌표, n 은 카메라에서 멀어지는 방향 z>0)"""
    Cimg = el.conic()
    Q = K.T @ Cimg @ K          # 정규화 카메라 좌표의 원뿔
    Q = Q / Q.abs().max()
    lam, V = torch.linalg.eigh(Q)
    # 부호가 다른 고유값 하나(λ3) + 같은 부호 두 개(λ1>=λ2)
    if (lam > 0).sum() == 1:
        lam, V = -lam, V
    # 이제 양수 2개, 음수 1개
    order = torch.argsort(lam, descending=True)
    lam, V = lam[order], V[:, order]
    l1, l2, l3 = lam.tolist()
    g = math.sqrt(max(l1 - l2, 0) / (l1 - l3))
    h = math.sqrt(max(l2 - l3, 0) / (l1 - l3))
    sols = []
    for sgn in (1.0, -1.0):
        n = V @ torch.tensor([sgn * g, 0.0, h], dtype=DTYPE)
        n = n / n.norm()
        if n[2] < 0:
            n = -n
        # 평면 n·X = 1 과 원뿔의 교선 -> 원 (중심/반지름) 을 수치적으로 구함
        p0 = n.clone()
        e1, e2 = _orth_basis(n)
        # X = p0 + α e1 + β e2,  X^T Q X = 0  ->  2D conic
        B3 = torch.stack([e1, e2, p0], 1)       # [e1 e2 p0]
        Q2 = B3.T @ Q @ B3                       # [α,β,1] 2D conic
        A_, B_, C_ = Q2[0, 0].item(), 2 * Q2[0, 1].item(), Q2[1, 1].item()
        D_, E_, F_ = 2 * Q2[0, 2].item(), 2 * Q2[1, 2].item(), Q2[2, 2].item()
        e = conic_to_ellipse(A_, B_, C_, D_, E_, F_)
        if e is None:
            continue
        r1 = 0.5 * (e.a + e.b)
        X0 = p0 + e.cx * e1 + e.cy * e2
        C3 = X0 * (R_world / r1)
        sols.append(dict(normal=n, center=C3, circ_err=abs(e.a - e.b) / r1))
    return sols


def project(K: torch.Tensor, X: torch.Tensor) -> torch.Tensor:
    x = X @ K.T
    return x[..., :2] / x[..., 2:3]


def backproject_to_plane(K, uv: torch.Tensor, n: torch.Tensor, P0: torch.Tensor):
    """픽셀 uv (N,2) 의 광선을 평면 (n, P0) 과 교차. 반환 (N,3), t (N,)."""
    Kinv = torch.linalg.inv(K)
    rays = torch.cat([uv.to(DTYPE), torch.ones(len(uv), 1, dtype=DTYPE)], 1) @ Kinv.T
    t = (P0 @ n) / (rays @ n)
    return rays * t[:, None], t


def f_px_from_exif(path) -> float | None:
    """원본(크롭 안 한) 사진의 EXIF 35mm 환산 초점거리로 f[px] 추정.
    f_px = f35 * (이미지 대각선 px) / 43.27 (35mm 필름 대각선)"""
    try:
        from PIL import Image
        im = Image.open(path)
        ex = im.getexif()
        sub = ex.get_ifd(0x8769) if hasattr(ex, "get_ifd") else {}
        f35 = sub.get(41989) or ex.get(41989)
        if not f35:
            return None
        W, H = im.size
        return float(f35) * math.hypot(W, H) / 43.27
    except Exception:
        return None
