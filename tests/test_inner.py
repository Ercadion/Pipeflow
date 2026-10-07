"""관 두께 입력 없이 내경 테두리 자동 선택 검증 (데스크톱 PyTorch 버전).
두께 4·12 mm, 위에서 30~75° 내려다봄 → measure_level(img, D) 자동 검출 수심 오차"""
import sys, math, os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from tests.synth import Scene
from pipeflow.pipeline import measure_level

worst = 0
print(f"{'두께':>4} {'각도':>4} {'참h':>4} | {'완전투시':>8} {'약투시':>7} | 내경 선택")
for wall in [4, 12]:
    for ang in [30, 45, 60, 75]:
        for depth in [25, 75]:
            dist = 260.0
            sc = Scene(D=100, wall=wall, depth=depth, cam_h=dist * math.sin(math.radians(ang)),
                       cam_z=dist * math.cos(math.radians(ang)), roll_deg=4, f=800, W=960, H=720)
            img = sc.render(noise=3, seed=1)
            p = measure_level(img, 100, f_px=800, principal=(480, 360), waterline_kwargs=dict(texture_sign=0))
            w = measure_level(img, 100, waterline_kwargs=dict(texture_sign=0))
            worst = max(worst, abs(p.depth_mm - depth))
            print(f"{wall:4d} {ang:4d} {depth:4d} | {p.depth_mm:8.2f} {w.depth_mm:7.2f} | "
                  f"{p.extra['det_info'].get('inner_source')} / {w.extra['det_info'].get('inner_source')}")
print(f"완전투시 최대 오차 {worst:.2f} mm")
