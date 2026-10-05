"""
부분 충만 원형 관로 수리 계산
 - 수심 h, 내경 D -> 중심각, 통수 단면적, 윤변, 동수반경, 수면폭
 - 유속: Manning (등류, 난류), 층류(해석 근사), 자동 선택
 - 표면유속 -> 단면평균유속 환산 계수
단위: SI (m, m/s, m^3/s)
"""
from __future__ import annotations

import math

G = 9.80665
NU_WATER_20C = 1.004e-6  # 동점성계수 [m^2/s] @20°C

# 대표 Manning 조도계수 n
MANNING_N = {
    "glass": 0.010,
    "acrylic": 0.009,
    "pvc": 0.009,
    "hdpe": 0.011,
    "steel": 0.012,
    "cast_iron": 0.013,
    "concrete": 0.013,
    "corrugated": 0.024,
}


def section(h: float, D: float) -> dict:
    """원형 관 부분 충만 단면 요소. h: 수심 [m], D: 내경 [m]."""
    h = min(max(h, 0.0), D)
    r = D / 2
    if h <= 0:
        return dict(theta=0.0, A=0.0, P=0.0, Rh=0.0, T=0.0, fill=0.0)
    theta = 2 * math.acos(max(-1.0, min(1.0, 1 - 2 * h / D)))  # 수면이 만드는 중심각 [rad]
    A = D**2 / 8 * (theta - math.sin(theta))
    P = D * theta / 2
    T = D * math.sin(theta / 2) if h < D else 0.0
    return dict(theta=theta, A=A, P=P, Rh=A / P, T=T, fill=h / D,
                area_ratio=A / (math.pi * r * r))


def manning_velocity(Rh: float, S: float, n: float) -> float:
    return (1.0 / n) * Rh ** (2 / 3) * math.sqrt(max(S, 0.0))


def laminar_velocity(Rh: float, S: float, nu: float = NU_WATER_20C, K: float = 64.0) -> float:
    """층류 등류 평균유속. Darcy f = K/Re (Re 기준길이 4Rh), K=64 는 원관(만관·반관 정확).
    V = 2 g S (4Rh)^2 / (K ν)  -> K=64 일 때 V = g S Rh^2 / (2ν)."""
    return 2 * G * S * (4 * Rh) ** 2 / (K * nu)


def reynolds(V: float, Rh: float, nu: float = NU_WATER_20C) -> float:
    """관수로와 비교 가능한 Re (기준길이 = 수력직경 4Rh)."""
    return V * 4 * Rh / nu


def froude(V: float, A: float, T: float) -> float:
    if T <= 0 or A <= 0:
        return float("nan")
    return V / math.sqrt(G * A / T)


def velocity_from_depth(h: float, D: float, slope: float, n: float = 0.010,
                        nu: float = NU_WATER_20C, regime: str = "auto") -> dict:
    """수심으로부터 등류(uniform flow) 유속 추정.
    regime: 'manning' | 'laminar' | 'auto' (층류 계산 후 Re<2000 이면 층류, 아니면 Manning)"""
    sec = section(h, D)
    out = dict(section=sec)
    if sec["A"] <= 0:
        out.update(V=0.0, Q=0.0, regime="dry")
        return out
    V_lam = laminar_velocity(sec["Rh"], slope, nu)
    V_man = manning_velocity(sec["Rh"], slope, n)
    Re_lam = reynolds(V_lam, sec["Rh"], nu)
    if regime == "laminar":
        V, used = V_lam, "laminar"
    elif regime == "manning":
        V, used = V_man, "manning"
    else:
        V, used = (V_lam, "laminar") if Re_lam < 2000 else (V_man, "manning")
    Re = reynolds(V, sec["Rh"], nu)
    warn = []
    if used == "laminar" and Re >= 2000:
        warn.append(f"층류 가정이지만 Re={Re:.0f} ≥ 2000 → 실제로는 난류일 가능성 큼")
    if used == "manning" and Re < 2000:
        warn.append(f"Manning 은 난류 공식인데 Re={Re:.0f} < 2000")
    out.update(V=V, Q=V * sec["A"], regime=used, Re=Re,
               Fr=froude(V, sec["A"], sec["T"]),
               V_laminar=V_lam, V_manning=V_man, warnings=warn)
    return out


def surface_to_mean_coef(Re: float | None) -> float:
    """표면유속 -> 단면평균유속 환산계수.
    난류: 0.85 (하천/관거 수면 유속계 관행값)
    층류: 0.5  (반관 층류는 만관 Poiseuille 의 대칭 절반 → 평균/최대 = 0.5)"""
    if Re is not None and Re < 2000:
        return 0.5
    return 0.85
