"""
카메라 캘리브레이션 (Zhang 2000, OpenCV) — 렌즈 왜곡 보정 + 초점거리 획득

참고: 이준형·윤병만·김서준 (2021) 「표면영상유속측정법을 이용한 유속 측정 시 카메라 왜곡 영향 분석」
      → 체스보드 20~30장(최소 5장), 영상의 넓은 영역을 차지하도록, 가장자리까지 여러 각도로 촬영

사용:
    calib = calibrate_chessboard(glob("calib/*.jpg"), pattern=(9, 6), square_mm=25)
    save_calibration(calib, "camera.json")
    calib = load_calibration("camera.json")
    img_u = undistort(img, calib)             # 왜곡 보정
    f_px, principal = intrinsics_for(calib, img.shape)
"""
from __future__ import annotations

import json
import math

import cv2
import numpy as np


def find_corners(img, pattern=(9, 6)):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    ok, corners = cv2.findChessboardCorners(
        gray, pattern, flags=cv2.CALIB_CB_ADAPTIVE_THRESH | cv2.CALIB_CB_NORMALIZE_IMAGE)
    if not ok:
        return None
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 50, 1e-4)
    return cv2.cornerSubPix(gray, corners, (11, 11), (-1, -1), crit)


def calibrate_chessboard(images, pattern=(9, 6), square_mm: float = 25.0,
                         min_views: int = 5) -> dict:
    """images: 경로 또는 BGR 배열 목록. pattern: 내부 코너 수 (가로, 세로)."""
    objp = np.zeros((pattern[0] * pattern[1], 3), np.float32)
    objp[:, :2] = np.mgrid[0:pattern[0], 0:pattern[1]].T.reshape(-1, 2) * square_mm
    obj_pts, img_pts, used = [], [], []
    size = None
    for i, im in enumerate(images):
        if isinstance(im, str):
            im = cv2.imdecode(np.fromfile(im, np.uint8), cv2.IMREAD_COLOR)
        if im is None:
            continue
        if size is None:
            size = (im.shape[1], im.shape[0])
        elif (im.shape[1], im.shape[0]) != size:
            continue  # 해상도가 다른 영상은 제외
        c = find_corners(im, pattern)
        if c is None:
            continue
        obj_pts.append(objp); img_pts.append(c); used.append(i)
    if len(used) < min_views:
        raise RuntimeError(f"체스보드 검출 영상이 {len(used)}장 — 최소 {min_views}장 필요")
    rms, K, dist, rvecs, tvecs = cv2.calibrateCamera(obj_pts, img_pts, size, None, None)
    # 영상별 재투영 오차
    per = []
    for o, p, r, t in zip(obj_pts, img_pts, rvecs, tvecs):
        q, _ = cv2.projectPoints(o, r, t, K, dist)
        per.append(float(np.sqrt(((q - p) ** 2).sum(-1).mean())))
    # 영상 가장자리에서의 최대 왜곡량(px) — 논문의 왜곡 분포도 지표
    W, H = size
    edge = np.array([[0, 0], [W - 1, 0], [0, H - 1], [W - 1, H - 1],
                     [W / 2, 0], [0, H / 2]], np.float64).reshape(-1, 1, 2)
    und = cv2.undistortPoints(edge, K, dist, P=K)
    max_dist_px = float(np.abs(und - edge).reshape(-1, 2).__pow__(2).sum(1).max() ** 0.5)
    return dict(K=K.tolist(), dist=dist.ravel().tolist(), image_size=list(size), rms=float(rms),
                per_view_rms=per, n_views=len(used), used=used, pattern=list(pattern),
                square_mm=square_mm, max_edge_distortion_px=max_dist_px,
                fov_deg=[math.degrees(2 * math.atan(W / 2 / K[0][0])),
                         math.degrees(2 * math.atan(H / 2 / K[1][1]))])


def save_calibration(calib: dict, path: str):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(calib, f, ensure_ascii=False, indent=2)


def load_calibration(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def scaled_K(calib: dict, shape) -> np.ndarray:
    """캘리브레이션 해상도와 다른 크기(같은 종횡비로 리사이즈)의 영상에 맞게 K 스케일."""
    K = np.array(calib["K"], np.float64)
    W0, H0 = calib["image_size"]
    H, W = shape[:2]
    sx, sy = W / W0, H / H0
    if abs(sx - sy) > 0.01:
        raise ValueError("영상 종횡비가 캘리브레이션과 다름 (크롭된 영상은 사용 불가)")
    K = K.copy()
    K[0, :] *= sx
    K[1, :] *= sy
    return K


def undistort(img: np.ndarray, calib: dict) -> np.ndarray:
    """렌즈 왜곡 보정 (K 는 유지 → 보정 후 영상은 같은 K 의 핀홀 카메라)."""
    K = scaled_K(calib, img.shape)
    return cv2.undistort(img, K, np.array(calib["dist"], np.float64), None, K)


def intrinsics_for(calib: dict, shape):
    K = scaled_K(calib, shape)
    f_px = 0.5 * (K[0, 0] + K[1, 1])
    return f_px, (K[0, 2], K[1, 2])


def main(argv=None):
    """python -m pipeflow.calibration "calib/*.jpg" --pattern 9 6 --square 25 --out camera.json"""
    import argparse
    import glob
    ap = argparse.ArgumentParser(description="체스보드 카메라 캘리브레이션")
    ap.add_argument("images", nargs="+", help="영상 경로 또는 glob 패턴")
    ap.add_argument("--pattern", type=int, nargs=2, default=[9, 6], help="내부 코너 수 (가로 세로)")
    ap.add_argument("--square", type=float, default=25.0, help="칸 크기 [mm]")
    ap.add_argument("--out", default="camera.json")
    a = ap.parse_args(argv)
    paths = []
    for p in a.images:
        paths += sorted(glob.glob(p)) or [p]
    cal = calibrate_chessboard(paths, tuple(a.pattern), a.square)
    save_calibration(cal, a.out)
    print(f"저장: {a.out}  ({cal['n_views']}/{len(paths)}장 사용, RMS {cal['rms']:.3f}px, "
          f"f={cal['K'][0][0]:.1f}px, 화각 {cal['fov_deg'][0]:.1f}°, "
          f"가장자리 최대 왜곡 {cal['max_edge_distortion_px']:.1f}px)")
    worst = sorted(zip(cal["per_view_rms"], [paths[i] for i in cal["used"]]), reverse=True)[:3]
    print("재투영 오차 큰 영상:", ", ".join(f"{p} ({e:.2f}px)" for e, p in worst))


if __name__ == "__main__":
    main()
