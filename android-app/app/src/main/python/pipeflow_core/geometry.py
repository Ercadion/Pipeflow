"""
기하 계산 (numpy) — 타원 피팅, conic 변환, 약투시 복원, 완전투시 원 자세 추정
(데스크톱 pipeflow/geometry.py 의 PyTorch 구현을 numpy 로 1:1 이식)
"""
from __future__ import annotations

import math

import numpy as np


class Ellipse:
    def __init__(self, cx, cy, a, b, phi):
        self.cx, self.cy, self.a, self.b, self.phi = float(cx), float(cy), float(a), float(b), float(phi)

    @property
    def ratio(self):
        return self.b / self.a

    @property
    def tilt_deg(self):
        return math.degrees(math.acos(max(-1.0, min(1.0, self.ratio))))

    @property
    def minor_dir(self):
        return (-math.sin(self.phi), math.cos(self.phi))

    def conic(self) -> np.ndarray:
        c, s = math.cos(self.phi), math.sin(self.phi)
        R = np.array([[c, -s], [s, c]])
        M = R @ np.diag([1 / self.a ** 2, 1 / self.b ** 2]) @ R.T
        t = np.array([self.cx, self.cy])
        C = np.zeros((3, 3))
        C[:2, :2] = M
        C[:2, 2] = -M @ t
        C[2, :2] = -M @ t
        C[2, 2] = t @ M @ t - 1.0
        return C

    def coef6(self) -> np.ndarray:
        C = self.conic()
        v = np.array([C[0, 0], 2 * C[0, 1], C[1, 1], 2 * C[0, 2], 2 * C[1, 2], C[2, 2]])
        return v / np.linalg.norm(v)

    def points(self, n=360) -> np.ndarray:
        t = np.linspace(0, 2 * math.pi, n)
        c, s = math.cos(self.phi), math.sin(self.phi)
        x, y = self.a * np.cos(t), self.b * np.sin(t)
        return np.stack([self.cx + c * x - s * y, self.cy + s * x + c * y], -1)

    def scaled(self, k: float) -> "Ellipse":
        return Ellipse(self.cx * k, self.cy * k, self.a * k, self.b * k, self.phi)

    def to_dict(self):
        return dict(cx=self.cx, cy=self.cy, a=self.a, b=self.b, phi_deg=math.degrees(self.phi),
                    ratio=self.ratio, tilt_deg=self.tilt_deg)

    @staticmethod
    def from_dict(d):
        return Ellipse(d["cx"], d["cy"], d["a"], d["b"], math.radians(d["phi_deg"]))


def conic_to_ellipse(A, B, C, D, E, F):
    den = B * B - 4 * A * C
    if den >= 0:
        return None
    cx = (2 * C * D - B * E) / den
    cy = (2 * A * E - B * D) / den
    Fc = A * cx * cx + B * cx * cy + C * cy * cy + D * cx + E * cy + F
    if Fc == 0:
        return None
    ev, V = np.linalg.eigh(np.array([[A, B / 2], [B / 2, C]]) / -Fc)
    if (ev <= 0).any():
        return None
    a, b = 1 / math.sqrt(ev[0]), 1 / math.sqrt(ev[1])
    phi = math.atan2(V[1, 0], V[0, 0])
    if phi > math.pi / 2:
        phi -= math.pi
    if phi <= -math.pi / 2:
        phi += math.pi
    return Ellipse(cx, cy, a, b, phi)


def fit_ellipse(pts: np.ndarray):
    """Fitzgibbon direct LSQ (Halir & Flusser)."""
    pts = np.asarray(pts, np.float64)
    if len(pts) < 5:
        return None
    mu = pts.mean(0)
    sc = max(np.abs(pts - mu).mean(), 1e-9)
    p = (pts - mu) / sc
    x, y = p[:, 0], p[:, 1]
    D1 = np.stack([x * x, x * y, y * y], 1)
    D2 = np.stack([x, y, np.ones_like(x)], 1)
    S1, S2, S3 = D1.T @ D1, D1.T @ D2, D2.T @ D2
    try:
        T = -np.linalg.solve(S3, S2.T)
    except np.linalg.LinAlgError:
        return None
    M = S1 + S2 @ T
    M = np.stack([M[2] / 2, -M[1], M[0] / 2])
    _, evecs = np.linalg.eig(M)
    evecs = np.real(evecs)
    cond = 4 * evecs[0] * evecs[2] - evecs[1] ** 2
    idx = np.flatnonzero(cond > 0)
    if len(idx) == 0:
        return None
    a1 = evecs[:, idx[0]]
    A, B, C, D, E, F = np.concatenate([a1, T @ a1])
    m0, m1, s = mu[0], mu[1], sc
    A2, B2, C2 = A / s ** 2, B / s ** 2, C / s ** 2
    D2_ = D / s - 2 * A2 * m0 - B2 * m1
    E2_ = E / s - 2 * C2 * m1 - B2 * m0
    F2_ = A2 * m0 ** 2 + B2 * m0 * m1 + C2 * m1 ** 2 - D / s * m0 - E / s * m1 + F
    return conic_to_ellipse(A2, B2, C2, D2_, E2_, F2_)


def rectify_affine(el: Ellipse, R_world: float):
    """이미지 -> 단면 평면(원 중심 원점, 반지름 R_world) affine 과 역변환."""
    c, s = math.cos(el.phi), math.sin(el.phi)
    Rm = np.array([[c, -s], [s, c]])
    A = Rm @ np.diag([R_world / el.a, R_world / el.b]) @ Rm.T
    t = -A @ np.array([el.cx, el.cy])
    return (A, t), (np.linalg.inv(A), np.array([el.cx, el.cy]))


def intrinsics(f_px, cx, cy) -> np.ndarray:
    return np.array([[f_px, 0, cx], [0, f_px, cy], [0, 0, 1.0]])


def _orth_basis(n):
    a = np.array([1.0, 0, 0]) if abs(n[0]) <= 0.9 else np.array([0, 1.0, 0])
    e1 = np.cross(n, a); e1 /= np.linalg.norm(e1)
    e2 = np.cross(n, e1)
    return e1, e2


def circle_pose(el: Ellipse, K: np.ndarray, R_world: float):
    """투영 타원 + K + 실제 반지름 -> 가능한 두 자세 [(normal, center, circ_err)]."""
    Q = K.T @ el.conic() @ K
    Q = Q / np.abs(Q).max()
    lam, V = np.linalg.eigh(Q)
    if (lam > 0).sum() == 1:
        lam = -lam
    order = np.argsort(-lam)
    lam, V = lam[order], V[:, order]
    l1, l2, l3 = lam
    g = math.sqrt(max(l1 - l2, 0) / (l1 - l3))
    h = math.sqrt(max(l2 - l3, 0) / (l1 - l3))
    sols = []
    for sgn in (1.0, -1.0):
        n = V @ np.array([sgn * g, 0.0, h])
        n /= np.linalg.norm(n)
        if n[2] < 0:
            n = -n
        e1, e2 = _orth_basis(n)
        B3 = np.stack([e1, e2, n], 1)
        Q2 = B3.T @ Q @ B3
        e = conic_to_ellipse(Q2[0, 0], 2 * Q2[0, 1], Q2[1, 1], 2 * Q2[0, 2], 2 * Q2[1, 2], Q2[2, 2])
        if e is None:
            continue
        r1 = 0.5 * (e.a + e.b)
        X0 = n + e.cx * e1 + e.cy * e2
        sols.append(dict(normal=n, center=X0 * (R_world / r1), circ_err=abs(e.a - e.b) / r1))
    return sols


def project(K, X):
    x = X @ K.T
    return x[..., :2] / x[..., 2:3]


def backproject_to_plane(K, uv, n, P0):
    rays = np.concatenate([np.asarray(uv, np.float64), np.ones((len(uv), 1))], 1) @ np.linalg.inv(K).T
    t = (P0 @ n) / (rays @ n)
    return rays * t[:, None], t


# ---------------------------------------------------------------------------
# 렌즈 왜곡 (OpenCV / Camera2 공통 Brown 모델: k1,k2,p1,p2,k3)
# ---------------------------------------------------------------------------
def undistort_image(img: np.ndarray, K: np.ndarray, dist) -> np.ndarray:
    """왜곡 보정 영상 (같은 K 의 핀홀). 출력 픽셀(이상 좌표) -> 왜곡 모델 순방향 -> 원본 샘플."""
    from .imgproc import bilinear
    k1, k2, p1, p2, k3 = (list(dist) + [0, 0, 0, 0, 0])[:5]
    H, W = img.shape[:2]
    u, v = np.meshgrid(np.arange(W, dtype=np.float64), np.arange(H, dtype=np.float64))
    x = (u - K[0, 2]) / K[0, 0]
    y = (v - K[1, 2]) / K[1, 1]
    r2 = x * x + y * y
    rad = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 ** 3
    xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
    yd = y * rad + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
    ud = xd * K[0, 0] + K[0, 2]
    vd = yd * K[1, 1] + K[1, 2]
    if img.ndim == 2:
        return bilinear(img, ud, vd)
    return np.stack([bilinear(img[..., c], ud, vd) for c in range(img.shape[2])], -1)


def undistort_points(pts, K, dist, iters=20) -> np.ndarray:
    """왜곡 좌표 -> 이상 좌표 (반복법)."""
    k1, k2, p1, p2, k3 = (list(dist) + [0, 0, 0, 0, 0])[:5]
    pts = np.asarray(pts, np.float64).reshape(-1, 2)
    xd = (pts[:, 0] - K[0, 2]) / K[0, 0]
    yd = (pts[:, 1] - K[1, 2]) / K[1, 1]
    x, y = xd.copy(), yd.copy()
    for _ in range(iters):
        r2 = x * x + y * y
        rad = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 ** 3
        dx = 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
        dy = p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
        x = (xd - dx) / rad
        y = (yd - dy) / rad
    return np.stack([x * K[0, 0] + K[0, 2], y * K[1, 1] + K[1, 2]], 1)
