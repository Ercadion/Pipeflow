"""CI 용 빠른 검증 (numpy 만 필요): 인공 STI H-STIV + 합성 원 기하."""
import os, sys, math
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "python"))
import numpy as np
from pipeflow_core.stiv import artificial_sti, h_stiv
from pipeflow_core.geometry import Ellipse, fit_ellipse, circle_pose, intrinsics, project, _orth_basis

errs = []
for u in [0.1, 0.5, 1.5, 3.0, 8.0, 16.0]:
    r = h_stiv(artificial_sti(u, noise=0.3, static=1.0, seed=1))
    errs.append(abs(r["u"] - u) / u)
print("H-STIV max rel err", max(errs))
assert max(errs) < 0.01

K = intrinsics(1000, 320, 240)
n = np.array([0, -math.sin(0.5), math.cos(0.5)]); C = np.array([30., -20, 400]); R = 50
e1, e2 = _orth_basis(n)
t = np.linspace(0, 2 * math.pi, 400)
X = C + R * (np.cos(t)[:, None] * e1 + np.sin(t)[:, None] * e2)
el = fit_ellipse(project(K, X))
best = min(circle_pose(el, K, R), key=lambda s: np.linalg.norm(s["normal"] - n))
print("pose err", np.linalg.norm(best["center"] - C))
assert np.linalg.norm(best["center"] - C) < 1e-3
print("OK")
