"""
시공간영상(STI) 기반 유속 분석 — C-STIV / F-STIV / H-STIV

참고: 류권규 (2024) 「인공시공간영상을 이용한 시공간영상분석법의 정확도 평가와 혼합분석법의 제안」
      Ecology and Resilient Infrastructure 11(3):100-109

STI 규약: S[t, x]  (행 = 시간(프레임), 열 = 측정선 위 위치(px))
          줄무늬 I(x - u·t) 의 u [px/frame] 가 구하려는 영상변위. 줄무늬각 φ = atan(u).

- C-STIV : 시간 간격 1 의 두 부분영상(연속행 또는 짝/홀수행)을 x 방향으로 밀어가며 상호상관
           → 큰 변위에 정확, 0.5 px/fr 미만은 화소 이산성 때문에 부정확
- F-STIV : STI 의 2D FFT 에서 원점을 지나는 최대 에너지 직선 ω = -u·kx 의 기울기
           → 작은 변위에 정확, 줄무늬각이 90° 에 가까울수록(큰 변위) arctan 때문에 오차 증폭
- H-STIV : |u| < 2.0 px/fr(63.4°) 이면 F, 아니면 C 결과 채택 (논문 제안)
           + 두 결과의 일치도(agreement)를 신뢰도로 함께 반환 (본 구현 추가)
- 정지 무늬 제거 : 각 열의 시간 평균을 빼서 움직이지 않는 무늬(랩 주름, 반사광)를 제거 (본 구현 추가)
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

DTYPE = torch.float64
H_THRESHOLD = 2.0  # px/frame


def preprocess_sti(S: torch.Tensor, remove_static: bool = True, row_norm: bool = True) -> torch.Tensor:
    """정지 무늬 제거(열별 시간평균 차감) + 행별 정규화(조도 변화 보정)."""
    S = S.to(DTYPE)
    if remove_static:
        S = S - S.mean(0, keepdim=True)
    if row_norm:
        S = S - S.mean(1, keepdim=True)
        S = S / S.std(1, keepdim=True).clamp_min(1e-9)
    return S


# ---------------------------------------------------------------------------
# C-STIV
# ---------------------------------------------------------------------------
def c_stiv(S: torch.Tensor, max_disp: float | None = None, split: str = "consecutive",
           lag: int = 1) -> dict:
    """상호상관 기반 STIV.
    split='consecutive': 모든 연속 행 쌍 (t, t+lag) 을 한 번에 상관 (데이터 최대 활용)
    split='oddeven'    : CASTI 원형 — 짝수행 영상과 홀수행 영상을 상관 (특허 10-1512690 방식)
    반환 u [px/frame], 상관 피크 등."""
    T, X = S.shape
    if split == "oddeven":
        n = T // 2
        A, B = S[0:2 * n:2], S[1:2 * n:2]
        lag = 1
    else:
        A, B = S[:-lag], S[lag:]
    Lmax = int(max_disp * lag) + 2 if max_disp is not None else X // 3
    Lmax = max(2, min(Lmax, X - 8))
    shifts = torch.arange(-Lmax, Lmax + 1)
    corr = torch.empty(len(shifts), dtype=DTYPE)
    for i, L in enumerate(shifts.tolist()):
        if L >= 0:
            a, b = A[:, :X - L], B[:, L:]
        else:
            a, b = A[:, -L:], B[:, :X + L]
        a = a - a.mean(); b = b - b.mean()
        corr[i] = (a * b).sum() / torch.sqrt((a * a).sum() * (b * b).sum()).clamp_min(1e-12)
    k = int(torch.argmax(corr))
    d = float(shifts[k])
    if 0 < k < len(corr) - 1:
        c_m, c_0, c_p = corr[k - 1].item(), corr[k].item(), corr[k + 1].item()
        if min(c_m, c_0, c_p) > 0:   # 3점 가우스 보간 (PIV 표준)
            lm, l0, lp = math.log(c_m), math.log(c_0), math.log(c_p)
            den = 2 * (lm - 2 * l0 + lp)
            if den < 0:
                d += (lm - lp) / den
        else:                        # 포물선 보간
            den = c_m - 2 * c_0 + c_p
            if den < 0:
                d += 0.5 * (c_m - c_p) / den
    return dict(u=d / lag, peak=corr[k].item(), corr=corr, shifts=shifts)


# ---------------------------------------------------------------------------
# F-STIV
# ---------------------------------------------------------------------------
def f_stiv(S: torch.Tensor, r_min: float = 0.02, r_max: float = 0.35,
           coarse_step_deg: float = 0.25) -> dict:
    """FFT 기반 STIV. 주파수 평면 (kx, ω) 에서 원점을 지나는 직선 중 에너지 최대인 방향 α 탐색.
    직선 방향 (cos α, sin α) 에서 ω/kx = tan α = -u  →  u = -tan α."""
    T, X = S.shape
    w = torch.outer(torch.hann_window(T, periodic=False, dtype=DTYPE),
                    torch.hann_window(X, periodic=False, dtype=DTYPE))
    Fm = torch.fft.fftshift(torch.fft.fft2(S * w)).abs()
    Fm = torch.log1p(Fm / (Fm.mean() + 1e-12))
    # 주파수 좌표 [cycles/sample] -> 영상 인덱스
    def score(alphas: torch.Tensor) -> torch.Tensor:
        r = torch.linspace(r_min, r_max, 160, dtype=DTYPE)
        r = torch.cat([-r.flip(0), r])
        kx = r[None, :] * torch.cos(alphas)[:, None]
        om = r[None, :] * torch.sin(alphas)[:, None]
        # fftshift 후 인덱스: freq f -> idx = f*N + N//2
        ix = kx * X + X // 2
        it = om * T + T // 2
        gx = ix / (X - 1) * 2 - 1
        gt = it / (T - 1) * 2 - 1
        grid = torch.stack([gx, gt], -1)[None].float()
        v = F.grid_sample(Fm[None, None].float(), grid, align_corners=True,
                          padding_mode="zeros")[0, 0].to(DTYPE)
        return v.mean(-1)

    a = torch.deg2rad(torch.arange(-89.75, 89.76, coarse_step_deg, dtype=DTYPE))
    sc = score(a)
    k = int(torch.argmax(sc))
    # 정밀 탐색 (±1 step, 0.01°)
    fine = torch.linspace(a[max(k - 1, 0)].item(), a[min(k + 1, len(a) - 1)].item(), 201, dtype=DTYPE)
    sf = score(fine)
    kf = int(torch.argmax(sf))
    alpha = fine[kf].item()
    contrast = (sc[k] / sc.median()).item()
    return dict(u=-math.tan(alpha), alpha_deg=math.degrees(alpha), contrast=contrast)


# ---------------------------------------------------------------------------
# H-STIV
# ---------------------------------------------------------------------------
def h_stiv(S: torch.Tensor, threshold: float = H_THRESHOLD, remove_static: bool = True,
           c_split: str = "consecutive", max_disp: float | None = None) -> dict:
    Sp = preprocess_sti(S, remove_static=remove_static)
    c = c_stiv(Sp, max_disp=max_disp, split=c_split)
    f = f_stiv(Sp)
    # 선택: F 결과 기준으로 판정하되, C 가 확실히 큰 변위를 보면 C
    u_ref = f["u"] if abs(c["u"]) < threshold else c["u"]
    use = "F" if abs(u_ref) < threshold else "C"
    u = f["u"] if use == "F" else c["u"]
    agree = abs(c["u"] - f["u"]) / max(abs(u), 0.1)
    return dict(u=u, used=use, u_C=c["u"], u_F=f["u"], c_peak=c["peak"],
                f_contrast=f["contrast"], agreement=agree,
                angle_deg=math.degrees(math.atan(u)))


# ---------------------------------------------------------------------------
# 인공 STI (논문 3.1 절 방식: 가우스 입자, 균일 흐름)
# ---------------------------------------------------------------------------
def artificial_sti(u: float, T: int = 300, X: int = 300, n_particles: int = 600,
                   dp: float = 3.0, seed: int = 0, noise: float = 0.0,
                   static: float = 0.0) -> torch.Tensor:
    g = torch.Generator().manual_seed(seed)
    span = X + abs(u) * T + 20
    x0 = torch.rand(n_particles, generator=g, dtype=DTYPE) * span - (abs(u) * T + 10 if u > 0 else 10)
    I0 = 0.5 + 0.5 * torch.rand(n_particles, generator=g, dtype=DTYPE)
    t = torch.arange(T, dtype=DTYPE)[:, None, None]
    x = torch.arange(X, dtype=DTYPE)[None, :, None]
    S = torch.zeros(T, X, dtype=DTYPE)
    for i in range(0, n_particles, 50):
        xc = x0[None, None, i:i + 50] + u * t
        S += (I0[i:i + 50] * torch.exp(-8 * (x - xc) ** 2 / dp**2)).sum(-1)
    if static:
        xs = torch.rand(40, generator=g, dtype=DTYPE) * X
        xx = torch.arange(X, dtype=DTYPE)[:, None]
        S = S + static * torch.exp(-8 * (xx - xs[None, :]) ** 2 / dp**2).sum(-1)[None, :]
    if noise:
        S = S + noise * torch.randn(S.shape, generator=g, dtype=DTYPE)
    return S
