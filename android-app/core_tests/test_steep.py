"""위에서 내려다보는 각도(30~75°)별 수위 정확도 + 실시간 타원 seed 정밀화 검증 (합성 영상)."""
import os, sys, math, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, os.path.join(HERE, "..", ".."))
import numpy as np
from tests.synth import Scene
from pipeflow_core.geometry import Ellipse, fit_ellipse
from pipeflow_core.pipeline import measure_level
from pipeflow_core.detect import refine_rim_from_seed

print(f"{'각도':>4} {'참h':>4} | {'자동 RANSAC':>11} {'live seed':>10} {'(seed 오차)':>10} {'정답 타원':>9} | 시간(auto/seed)")
for ang in [30, 45, 60, 70, 75]:
    for depth in [25, 50, 75]:
        dist = 260.0
        sc = Scene(D=100, wall=4, depth=depth, cam_h=dist * math.sin(math.radians(ang)),
                   cam_z=dist * math.cos(math.radians(ang)), roll_deg=4, f=800, W=960, H=720)
        g = sc.render(noise=3, seed=1)[..., 0].astype(float)
        true_el = fit_ellipse(sc.outer_circle_pts().numpy())
        t = time.time()
        a = measure_level(g, 100, 4, f_px=800, principal=(480, 360), waterline_kwargs=dict(texture_sign=0))
        ta = time.time() - t
        # 실시간 검출 수준의 오차를 준 seed (중심 3px, 반축 3%)
        seed = Ellipse(true_el.cx + 3, true_el.cy - 3, true_el.a * 1.03, true_el.b * 0.97, true_el.phi + 0.02)
        t = time.time()
        el, _ = refine_rim_from_seed(g, seed)
        b = measure_level(g, 100, 4, ellipse=el, f_px=800, principal=(480, 360), waterline_kwargs=dict(texture_sign=0))
        tb = time.time() - t
        c = measure_level(g, 100, 4, ellipse=true_el, f_px=800, principal=(480, 360), waterline_kwargs=dict(texture_sign=0))
        print(f"{ang:4d} {depth:4d} | {a.depth_mm:11.2f} {b.depth_mm:10.2f} {'':>10} {c.depth_mm:9.2f} | {ta:.1f}s / {tb:.1f}s")
