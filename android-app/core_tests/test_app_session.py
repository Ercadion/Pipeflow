"""pipeflow_app.analyze 를 가짜 세션(합성 영상, 센서 방향 90° 회전, 프레임 지터/누락)으로 검증."""
import os, sys, json, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))
sys.path.insert(0, os.path.join(HERE, "..", ".."))
import numpy as np
from tests.synth import Scene
import pipeflow_app

def make_session(d, v_true=0.25, depth=45, fps=60.0, n=150, jitter=True):
    os.makedirs(d, exist_ok=True)
    W, H, f = 720, 960, 900.0                                   # 세로(업라이트) 영상
    full = Scene(D=100, wall=4, depth=depth, cam_h=90, cam_z=200, roll_deg=0, f=f, W=W, H=H, seed=3)
    rng = np.random.default_rng(0)
    t = np.arange(n) / fps
    if jitter:
        t = t + rng.normal(0, 0.002, n)
        keep = np.ones(n, bool); keep[rng.choice(np.arange(5, n), 8, replace=False)] = False   # 프레임 누락
        t = np.sort(t[keep])
    to_sensor = lambda img: np.ascontiguousarray(np.rot90(img, 1))   # 센서 방향(가로)
    still = to_sensor(full.render(t=t[0], v=v_true * 1000, static=20, noise=2, seed=0)[..., 0])
    still.astype(np.uint8).tofile(os.path.join(d, "still.y"))
    with open(os.path.join(d, "frames.y"), "wb") as fo:
        for k, tk in enumerate(t):
            fullf = full.render(t=tk, v=v_true * 1000, static=20, noise=2, seed=k)[..., 0].astype(np.float64)
            binned = fullf.reshape(H // 2, 2, W // 2, 2).mean((1, 3))      # 앱과 같은 2x2 평균 축소
            fo.write(to_sensor(np.round(binned)).astype(np.uint8).tobytes())
    meta = dict(still=dict(file="still.y", width=H, height=W),
                frames=dict(file="frames.y", width=H // 2, height=W // 2, count=len(t),
                            timestamps_ns=(t * 1e9).astype(np.int64).tolist(), fps=fps),
                rotation_degrees=90,
                intrinsics=dict(f_px=f, cx=H / 2, cy=W / 2 - 1, source="synthetic"),
                gravity=[0.0, 9.5, 2.0], params=dict(diameter_mm=100, wall_mm=4))
    json.dump(meta, open(os.path.join(d, "meta.json"), "w"))

class L:
    def onProgress(self, m): print("   ..", m)

for v in [0.05, 0.25, -0.15]:
    d = f"/tmp/claude-0/sess/v{v}"
    make_session(d, v_true=v)
    t = time.time()
    r = json.loads(pipeflow_app.analyze(d, json.dumps(dict(diameter_mm=100, wall_mm=4, slope=0.005, texture_sign=0)), L()))
    if not r["ok"]:
        print(r["traceback"]); break
    s = r["velocity_stiv"]
    print(f"v_true={v:+.2f}: h={r['level']['depth_mm']} (참 45), method={r['level']['method']}, "
          f"roll={r['level']['roll_deg']} (예상 {r['level']['expected_roll_deg']}), "
          f"v_surf={s['v_surface_mps']:+.4f} ({(s['v_surface_mps']-v)/abs(v)*100:+.2f}%), "
          f"fps={s['fps']:.1f}, 보간={s['resampled']}, 등류공식 V={r['velocity_formula']['V_mean_mps']:.3f}, {time.time()-t:.1f}s")
