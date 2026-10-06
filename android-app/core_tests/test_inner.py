"""관 두께 입력 없이 내경 테두리 선택 검증 (합성, 위에서 30~75° 내려다봄, 두께 4·12 mm).
자동 RANSAC / 실시간 seed(바깥 또는 안쪽 테두리 근처) → select_inner_rim → measure_level(D, 0, rim_is='inner')"""
import os, sys, math
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, os.path.join(HERE, "..", ".."))
from tests.synth import Scene
from pipeflow_core.geometry import Ellipse, fit_ellipse
from pipeflow_core.pipeline import measure_level
from pipeflow_core.detect import refine_rim_from_seed, select_inner_rim, detect_rim

KW = dict(f_px=800, principal=(480, 360), waterline_kwargs=dict(texture_sign=0))
print(f"{'두께':>4} {'각도':>4} {'참h':>4} | {'자동':>7} {'seed바깥':>8} {'seed안쪽':>8} | 선택(자동/바깥/안쪽)")
worst = 0
for wall in [4, 12]:
    for ang in [30, 45, 60, 75]:
        for depth in [25, 75]:
            dist = 260.0
            sc = Scene(D=100, wall=wall, depth=depth, cam_h=dist * math.sin(math.radians(ang)),
                       cam_z=dist * math.cos(math.radians(ang)), roll_deg=4, f=800, W=960, H=720)
            g = sc.render(noise=3, seed=1)[..., 0].astype(float)
            out_el = fit_ellipse(sc.outer_circle_pts().numpy())
            in_el = fit_ellipse(sc.inner_circle_pts().numpy())
            res, src = [], []
            el_a, _ = detect_rim(g)
            # 자동
            e, info = select_inner_rim(g, el_a, f_px=800, principal=(480, 360)); src.append(info["inner_source"][:5])
            res.append(measure_level(g, 100, 0, rim_is="inner", ellipse=e, **KW).depth_mm)
            # 실시간 seed (오차: 중심 3px, 반축 3%)
            for tgt in (out_el, in_el):
                seed = Ellipse(tgt.cx + 3, tgt.cy - 3, tgt.a * 1.03, tgt.b * 0.97, tgt.phi + 0.02)
                e, _ = refine_rim_from_seed(g, seed)
                e, info = select_inner_rim(g, e, f_px=800, principal=(480, 360)); src.append(info["inner_source"][:5])
                res.append(measure_level(g, 100, 0, rim_is="inner", ellipse=e, **KW).depth_mm)
            worst = max(worst, max(abs(r - depth) for r in res))
            print(f"{wall:4d} {ang:4d} {depth:4d} | {res[0]:7.2f} {res[1]:8.2f} {res[2]:8.2f} | {'/'.join(src)}")
print(f"최대 오차 {worst:.2f} mm")
