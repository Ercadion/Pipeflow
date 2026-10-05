"""합성 장면: 수면 무늬가 v 로 흐를 때 연속 프레임 추적 유속 검증 + 등류 공식 sanity check."""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import numpy as np
from tests.synth import Scene
from pipeflow.pipeline import measure_level, velocity_from_frames, velocity_from_level
from pipeflow import hydraulics as hyd

fps = 60.0
for v_true, cam_h, depth in [(100, 80, 40), (250, 80, 40), (250, 120, 60), (400, 60, 30), (-150, 100, 50)]:
    sc = Scene(D=100, wall=4, depth=depth, cam_h=cam_h, cam_z=160, roll_deg=3, seed=1)
    frames = [sc.render(t=k / fps, v=v_true) for k in range(6)]
    lvl = measure_level(frames[0], 100, 4, f_px=sc.f, waterline_kwargs=dict(texture_sign=0))
    res = velocity_from_frames(frames, 1 / fps, lvl, res_mm=0.5)
    print(f"v_true={v_true/1000:+.3f} m/s  h_true={depth}  h_est={lvl.depth_mm:6.2f}  "
          f"v_surf_est={res['v_surface']:+.4f} m/s  graze={res['graze_deg']:.1f}°  warn={res['warnings']}")

print("\n[hydraulics sanity]")
s = hyd.section(0.05, 0.1)
print("half-full: A/A_full =", round(s['area_ratio'], 4), " Rh =", s['Rh'], "(expect D/4=0.025)")
s = hyd.section(0.1, 0.1); print("full: Rh =", s['Rh'])
r = hyd.velocity_from_depth(0.05, 0.1, slope=0.005, n=0.010)
print("Manning half-full D=100mm S=0.5%% n=0.010 -> V=%.3f m/s Q=%.3f L/s regime=%s Re=%.0f Fr=%.2f" %
      (r['V'], r['Q'] * 1000, r['regime'], r['Re'], r['Fr']))
r = hyd.velocity_from_depth(0.05, 0.1, slope=1e-7, n=0.010)
print("tiny slope -> V=%.5f m/s regime=%s Re=%.0f" % (r['V'], r['regime'], r['Re']))
