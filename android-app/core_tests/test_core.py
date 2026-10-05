"""numpy 엔진(pipeflow_core) 검증 — 데스크톱에서 실행.
합성 장면 렌더러는 데스크톱 pipeflow/tests/synth.py (PyTorch) 를 사용."""
import os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, os.path.join(HERE, "..", ".."))           # 데스크톱 pipeflow (tests.synth)
import numpy as np
from tests.synth import Scene
from pipeflow_core.geometry import fit_ellipse
from pipeflow_core.pipeline import measure_level, velocity_from_frames_stiv
from pipeflow_core.stiv import artificial_sti, h_stiv

def gray(img):
    return img[..., 0].astype(np.float64)

t0 = time.time()
print("[1] 기하 정확도 (타원·수면선을 정답으로 줌, 완전투시)")
errs = []
for depth in [15, 50, 85]:
    for cam_h, cam_z, roll in [(20, 250, 0), (100, 150, -8), (60, 400, 10)]:
        sc = Scene(D=100, wall=4, depth=depth, cam_h=cam_h, cam_z=cam_z, roll_deg=roll)
        el = fit_ellipse(sc.outer_circle_pts().numpy())
        r = measure_level(gray(sc.render()), 100, 4, ellipse=el, waterline_pts=sc.waterline_pts().numpy(), f_px=sc.f)
        errs.append(abs(r.depth_mm - depth))
print(f"    최대 오차 {max(errs):.4f} mm")

print("[2] 자동 검출 + 완전투시")
for depth in [30, 50, 70]:
    sc = Scene(D=100, wall=4, depth=depth, cam_h=60, cam_z=200, roll_deg=4)
    r = measure_level(gray(sc.render()), 100, 4, f_px=sc.f, waterline_kwargs=dict(texture_sign=0))
    print(f"    h={depth} -> {r.depth_mm:.2f} mm")

print("[3] 인공 STI H-STIV")
e = []
for u in [0.1, 0.3, 0.7, 1.5, 2.0, 5.0, 10.0, 19.0]:
    r = h_stiv(artificial_sti(u, noise=0.3, static=1.0, seed=1))
    e.append(abs(r["u"] - u) / u * 100)
print(f"    평균 {np.mean(e):.3f}% 최대 {np.max(e):.3f}%")

print("[4] 합성 관 영상 H-STIV (정지 무늬+잡음, 60fps 2초)")
fps = 60.0
for v_true in [0.02, 0.15, 0.6, -0.2]:
    sc = Scene(D=100, wall=4, depth=45, cam_h=90, cam_z=170, roll_deg=3, seed=3)
    frames = [gray(sc.render(t=k / fps, v=v_true * 1000, static=25, noise=3.0, seed=k)) for k in range(120)]
    lvl = measure_level(frames[0], 100, 4, f_px=sc.f, waterline_kwargs=dict(texture_sign=0))
    t = time.time()
    st = velocity_from_frames_stiv(frames, 1 / fps, lvl)
    print(f"    v={v_true:+.2f} -> {st['v_surface']:+.4f} m/s ({(st['v_surface']-v_true)/abs(v_true)*100:+.2f}%), "
          f"stride {st['stride']}, h={lvl.depth_mm:.2f}, STIV {time.time()-t:.1f}s")
print(f"총 {time.time()-t0:.0f}s")
