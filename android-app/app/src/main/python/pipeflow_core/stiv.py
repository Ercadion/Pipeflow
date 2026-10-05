"""
시공간영상(STI) 유속 분석 (numpy) — C-STIV / F-STIV / H-STIV
참고: 류권규 (2024) Ecology and Resilient Infrastructure 11(3):100-109
STI 규약: S[t, x], 줄무늬 I(x - u t) 의 u [px/frame]
"""
from __future__ import annotations

import math

import numpy as np

from .imgproc import bilinear

H_THRESHOLD = 2.0


def preprocess_sti(S, remove_static=True, row_norm=True):
    S = np.asarray(S, np.float64)
    if remove_static:
        S = S - S.mean(0, keepdims=True)
    if row_norm:
        S = S - S.mean(1, keepdims=True)
        S = S / np.maximum(S.std(1, keepdims=True), 1e-9)
    return S


def c_stiv(S, max_disp=None, split="consecutive", lag=1):
    T, X = S.shape
    if split == "oddeven":
        n = T // 2
        A, B = S[0:2 * n:2], S[1:2 * n:2]
        lag = 1
    else:
        A, B = S[:-lag], S[lag:]
    Lmax = int(max_disp * lag) + 2 if max_disp is not None else X // 3
    Lmax = max(2, min(Lmax, X - 8))
    shifts = np.arange(-Lmax, Lmax + 1)
    corr = np.empty(len(shifts))
    for i, L in enumerate(shifts):
        if L >= 0:
            a, b = A[:, :X - L], B[:, L:]
        else:
            a, b = A[:, -L:], B[:, :X + L]
        a = a - a.mean(); b = b - b.mean()
        corr[i] = (a * b).sum() / max(math.sqrt((a * a).sum() * (b * b).sum()), 1e-12)
    k = int(np.argmax(corr))
    d = float(shifts[k])
    if 0 < k < len(corr) - 1:
        c_m, c_0, c_p = corr[k - 1], corr[k], corr[k + 1]
        if min(c_m, c_0, c_p) > 0:
            lm, l0, lp = math.log(c_m), math.log(c_0), math.log(c_p)
            den = 2 * (lm - 2 * l0 + lp)
            if den < 0:
                d += (lm - lp) / den
        else:
            den = c_m - 2 * c_0 + c_p
            if den < 0:
                d += 0.5 * (c_m - c_p) / den
    return dict(u=d / lag, peak=float(corr[k]))


def f_stiv(S, r_min=0.02, r_max=0.35, coarse_step_deg=0.25):
    T, X = S.shape
    w = np.outer(np.hanning(T), np.hanning(X))
    Fm = np.abs(np.fft.fftshift(np.fft.fft2(S * w)))
    Fm = np.log1p(Fm / (Fm.mean() + 1e-12))
    r = np.linspace(r_min, r_max, 160)
    r = np.concatenate([-r[::-1], r])

    def score(alphas):
        kx = r[None, :] * np.cos(alphas)[:, None]
        om = r[None, :] * np.sin(alphas)[:, None]
        return bilinear(Fm, kx * X + X // 2, om * T + T // 2).mean(-1)

    a = np.deg2rad(np.arange(-89.75, 89.76, coarse_step_deg))
    sc = score(a)
    k = int(np.argmax(sc))
    fine = np.linspace(a[max(k - 1, 0)], a[min(k + 1, len(a) - 1)], 201)
    alpha = float(fine[int(np.argmax(score(fine)))])
    return dict(u=-math.tan(alpha), alpha_deg=math.degrees(alpha),
                contrast=float(sc[k] / np.median(sc)))


def h_stiv(S, threshold=H_THRESHOLD, remove_static=True, c_split="consecutive", max_disp=None):
    Sp = preprocess_sti(S, remove_static=remove_static)
    c = c_stiv(Sp, max_disp=max_disp, split=c_split)
    f = f_stiv(Sp)
    u_ref = f["u"] if abs(c["u"]) < threshold else c["u"]
    use = "F" if abs(u_ref) < threshold else "C"
    u = f["u"] if use == "F" else c["u"]
    return dict(u=u, used=use, u_C=c["u"], u_F=f["u"], c_peak=c["peak"],
                f_contrast=f["contrast"], agreement=abs(c["u"] - f["u"]) / max(abs(u), 0.1),
                angle_deg=math.degrees(math.atan(u)))


def artificial_sti(u, T=300, X=300, n_particles=600, dp=3.0, seed=0, noise=0.0, static=0.0):
    rng = np.random.default_rng(seed)
    span = X + abs(u) * T + 20
    x0 = rng.random(n_particles) * span - (abs(u) * T + 10 if u > 0 else 10)
    I0 = 0.5 + 0.5 * rng.random(n_particles)
    t = np.arange(T, dtype=np.float64)[:, None, None]
    x = np.arange(X, dtype=np.float64)[None, :, None]
    S = np.zeros((T, X))
    for i in range(0, n_particles, 50):
        S += (I0[i:i + 50] * np.exp(-8 * (x - (x0[None, None, i:i + 50] + u * t)) ** 2 / dp ** 2)).sum(-1)
    if static:
        xs = rng.random(40) * X
        S += static * np.exp(-8 * (np.arange(X)[:, None] - xs[None, :]) ** 2 / dp ** 2).sum(-1)[None, :]
    if noise:
        S += noise * rng.standard_normal(S.shape)
    return S
