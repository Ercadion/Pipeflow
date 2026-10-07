"""합성 장면으로 수위 계산 검증 (기하 정확도: 타원/수면선을 정확히 줬을 때 + 자동검출)."""
import sys, math, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import numpy as np, cv2
from tests.synth import Scene
from pipeflow.geometry import fit_ellipse
from pipeflow.pipeline import measure_level

rows = []
for depth in [15, 30, 50, 70, 85]:
    for cam_h, cam_z, roll in [(20, 250, 0), (60, 180, 5), (100, 150, -8), (60, 400, 10)]:
        sc = Scene(D=100, wall=4, depth=depth, cam_h=cam_h, cam_z=cam_z, roll_deg=roll)
        el = fit_ellipse(sc.inner_circle_pts())   # 테두리 타원 = 관 내경
        wl = sc.waterline_pts().numpy()
        weak = measure_level(sc.render(), 100, ellipse=el, waterline_pts=wl)
        per = measure_level(sc.render(), 100, ellipse=el, waterline_pts=wl, f_px=sc.f)
        rows.append((depth, cam_h, cam_z, roll, el.tilt_deg, weak.depth_mm, per.depth_mm))
print(f"{'h':>4} {'cam_h':>5} {'cam_z':>5} {'roll':>4} {'tilt':>6} {'weak':>7} {'persp':>7}")
for r in rows:
    print(f"{r[0]:4.0f} {r[1]:5.0f} {r[2]:5.0f} {r[3]:4.0f} {r[4]:6.1f} {r[5]:7.2f} {r[6]:7.2f}")
ew = np.array([r[5] - r[0] for r in rows]); ep = np.array([r[6] - r[0] for r in rows])
print("weak  : MAE %.2f mm, max %.2f mm" % (np.abs(ew).mean(), np.abs(ew).max()))
print("persp : MAE %.3f mm, max %.3f mm" % (np.abs(ep).mean(), np.abs(ep).max()))

# 자동 검출 (rim + waterline) 테스트
print("\n[auto detection on synthetic renders]")
for depth in [30, 50, 70]:
    sc = Scene(D=100, wall=4, depth=depth, cam_h=60, cam_z=200, roll_deg=4)
    r = measure_level(sc.render(), 100, waterline_kwargs=dict(texture_sign=0))
    print(depth, "->", round(r.depth_mm, 2), "tilt", round(r.tilt_deg, 1))
