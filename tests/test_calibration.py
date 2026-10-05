"""합성 체스보드(알려진 K·왜곡)로 캘리브레이션 정확도 검증 + 왜곡된 관 영상에서 보정 전/후 수심 비교."""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import numpy as np, cv2
from pipeflow.calibration import calibrate_chessboard, undistort
from pipeflow.pipeline import measure_level
from pipeflow.geometry import fit_ellipse
from tests.synth import Scene

W, H = 960, 720
K_true = np.array([[820.0, 0, 486.0], [0, 820.0, 352.0], [0, 0, 1]])
dist_true = np.array([-0.28, 0.09, 0.0006, -0.0004, 0.0])   # 술통형 (광각 폰카 수준)
pattern, sq = (9, 6), 25.0

def distort_map(K, dist, W, H):
    """왜곡 영상 픽셀 -> 이상(핀홀) 영상 좌표 맵."""
    u, v = np.meshgrid(np.arange(W, dtype=np.float64), np.arange(H, dtype=np.float64))
    pts = np.stack([u, v], -1).reshape(-1, 1, 2)
    und = cv2.undistortPoints(pts, K, dist, None, K, criteria=(cv2.TERM_CRITERIA_COUNT | cv2.TERM_CRITERIA_EPS, 40, 1e-6))
    m = und.reshape(H, W, 2).astype(np.float32)
    return m[..., 0], m[..., 1]

mx, my = distort_map(K_true, dist_true, W, H)

def render_board(rvec, tvec):
    """이상 핀홀 영상 좌표로 보드 평면 교차 -> 체커 색 -> 왜곡 적용(remap)."""
    R, _ = cv2.Rodrigues(rvec)
    u, v = np.meshgrid(np.arange(W, dtype=np.float64), np.arange(H, dtype=np.float64))
    rays = np.stack([(u - K_true[0, 2]) / K_true[0, 0], (v - K_true[1, 2]) / K_true[1, 1], np.ones_like(u)], -1)
    n = R[:, 2]; t = tvec.ravel()
    s = (n @ t) / (rays @ n)
    X = rays * s[..., None] - t
    bx, by = X @ R[:, 0], X @ R[:, 1]
    ix, iy = np.floor(bx / sq + 1), np.floor(by / sq + 1)
    inside = (ix >= 0) & (ix < pattern[0] + 1) & (iy >= 0) & (iy < pattern[1] + 1) & (s > 0)
    img = np.full((H, W), 200.0)
    img[inside] = np.where(((ix + iy) % 2 == 0)[inside], 30.0, 230.0)
    img = cv2.remap(img.astype(np.float32), mx, my, cv2.INTER_LINEAR)
    return cv2.cvtColor(np.clip(img, 0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)

rng = np.random.default_rng(0)
views = []
cx_b, cy_b = (pattern[0] - 1) * sq / 2, (pattern[1] - 1) * sq / 2
for i in range(20):
    ang = rng.uniform(-0.5, 0.5, 3); ang[2] = rng.uniform(-0.3, 0.3)
    R, _ = cv2.Rodrigues(ang)
    z = rng.uniform(330, 520)
    off = np.array([rng.uniform(-90, 90), rng.uniform(-60, 60), z])
    tvec = off - R @ np.array([cx_b, cy_b, 0])
    views.append(render_board(ang, tvec))
cal = calibrate_chessboard(views, pattern, sq)
K = np.array(cal["K"])
print(f"[캘리브레이션] 사용 {cal['n_views']}장, RMS 재투영오차 {cal['rms']:.3f}px")
print(f"  fx {K[0,0]:.1f} (참 820.0), fy {K[1,1]:.1f}, cx {K[0,2]:.1f} (486), cy {K[1,2]:.1f} (352)")
print(f"  k1 {cal['dist'][0]:+.4f} (참 -0.28), k2 {cal['dist'][1]:+.4f} (0.09), p1 {cal['dist'][2]:+.5f}, p2 {cal['dist'][3]:+.5f}")
print(f"  가장자리 최대 왜곡 {cal['max_edge_distortion_px']:.1f}px, 화각 {cal['fov_deg'][0]:.1f}°")

# 왜곡된 관 끝단 영상: 보정 없음 vs 캘리브레이션 사용
print("\n[왜곡된 관 영상 수심 (D=100, 참값 h)]")
print(f"{'h':>4} {'배치':>14} | {'왜곡무시+f':>10} | {'캘리브레이션':>12}")
for depth, cam_z, cam_x, tag in [(25, 230, 0, '중앙,작게'), (45, 230, 0, '중앙,작게'), (70, 230, 0, '중앙,작게'),
                                 (25, 140, 0, '화면 가득'), (45, 140, 0, '화면 가득'), (70, 140, 0, '화면 가득')]:
    sc = Scene(D=100, wall=4, depth=depth, cam_h=50, cam_z=cam_z, cam_x=cam_x, roll_deg=3, f=K_true[0, 0], W=W, H=H,
               pp=(K_true[0, 2], K_true[1, 2]))
    ideal = sc.render()
    dimg = cv2.remap(ideal, mx, my, cv2.INTER_LINEAR)
    a = measure_level(dimg, 100, 4, f_px=K_true[0, 0], principal=(K_true[0, 2], K_true[1, 2]),
                      waterline_kwargs=dict(texture_sign=0))
    b = measure_level(dimg, 100, 4, calib=cal, waterline_kwargs=dict(texture_sign=0))
    print(f"{depth:4d} {tag:>14} | {a.depth_mm:10.2f} | {b.depth_mm:12.2f}")

# 왜곡된 영상에서 STIV 유속: 왜곡 무시 vs 캘리브레이션
from pipeflow.pipeline import velocity_from_frames_stiv
print("\n[왜곡된 영상 STIV 유속, 참값 0.30 m/s, 60fps 2초]")
fps = 60.0
for cam_x, tag in [(0, '관이 화면 중앙'), (60, '관이 화면 가장자리')]:
    sc = Scene(D=100, wall=4, depth=45, cam_h=90, cam_z=150, cam_x=cam_x, roll_deg=3, f=K_true[0, 0], W=W, H=H,
               pp=(K_true[0, 2], K_true[1, 2]), seed=5)
    frames = [cv2.remap(sc.render(t=k / fps, v=300, noise=2, seed=k), mx, my, cv2.INTER_LINEAR) for k in range(120)]
    la = measure_level(frames[0], 100, 4, f_px=K_true[0, 0], principal=(K_true[0, 2], K_true[1, 2]),
                       waterline_kwargs=dict(texture_sign=0))
    va = velocity_from_frames_stiv(frames, 1 / fps, la)["v_surface"]
    lb = measure_level(frames[0], 100, 4, calib=cal, waterline_kwargs=dict(texture_sign=0))
    vb = velocity_from_frames_stiv(frames, 1 / fps, lb)["v_surface"]
    print(f"  {tag:<14} 왜곡무시 {va:.4f} ({(va-0.3)/0.3*100:+.2f}%)  캘리브레이션 {vb:.4f} ({(vb-0.3)/0.3*100:+.2f}%)"
          f"   h: {la.depth_mm:.2f} / {lb.depth_mm:.2f}")
