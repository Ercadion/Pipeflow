"""합성 장면 영상(정지 무늬 + 센서 잡음 포함)에서 프레임쌍 위상상관 vs H-STIV 표면유속 비교."""
import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import numpy as np
from tests.synth import Scene
from pipeflow.pipeline import measure_level, velocity_from_frames, velocity_from_frames_stiv

fps, n = 60.0, 120
print(f"{'v_true':>7} {'static':>6} | {'phaseCorr':>9} {'err%':>6} | {'H-STIV':>8} {'err%':>6} {'stride':>6} {'used':>5} {'agree':>6} {'spread':>7}")
t0 = time.time()
for v_true, static in [(0.02, 0), (0.05, 0), (0.15, 0), (0.4, 0), (0.8, 0),
                       (0.02, 25), (0.05, 25), (0.15, 25), (0.4, 25), (-0.2, 25)]:
    sc = Scene(D=100, wall=4, depth=45, cam_h=90, cam_z=170, roll_deg=3, seed=3)
    frames = [sc.render(t=k / fps, v=v_true * 1000, static=static, noise=3.0, seed=k) for k in range(n)]
    lvl = measure_level(frames[0], 100, f_px=sc.f, waterline_kwargs=dict(texture_sign=0))
    try:
        a = velocity_from_frames(frames, 1 / fps, lvl)["v_surface"]
    except Exception as e:
        a = float("nan")
    b = velocity_from_frames_stiv(frames, 1 / fps, lvl)
    used = "".join(sorted(set(l["used"] for l in b["lines"])))
    ag = max(l["agreement"] for l in b["lines"])
    e = lambda x: abs(x - v_true) / abs(v_true) * 100
    print(f"{v_true:7.3f} {static:6.0f} | {a:9.4f} {e(a):6.1f} | {b['v_surface']:8.4f} {e(b['v_surface']):6.2f} "
          f"{b['stride']:6d} {used:>5} {ag:6.3f} {b['spread']:7.4f}")
print(f"소요 {time.time()-t0:.0f}s")
