"""
영상 처리 기본 연산 (numpy 만 사용 — 안드로이드 Chaquopy 에서 그대로 동작)
 gaussian blur / sobel / canny / 연결요소 / 쌍선형 샘플링 / 리사이즈
"""
from __future__ import annotations

from collections import deque

import numpy as np


def gaussian_kernel(sigma: float, ksize: int | None = None) -> np.ndarray:
    r = (ksize // 2) if ksize else max(1, int(3 * sigma + 0.5))
    x = np.arange(-r, r + 1, dtype=np.float64)
    k = np.exp(-x * x / (2 * sigma * sigma))
    return k / k.sum()


def conv_sep(img: np.ndarray, k: np.ndarray) -> np.ndarray:
    """분리형 컨볼루션 (가장자리 복제 패딩)."""
    r = len(k) // 2
    H, W = img.shape
    p = np.pad(img.astype(np.float64), ((0, 0), (r, r)), mode="edge")
    tmp = np.zeros((H, W))
    for i, kv in enumerate(k):
        tmp += kv * p[:, i:i + W]
    p = np.pad(tmp, ((r, r), (0, 0)), mode="edge")
    out = np.zeros((H, W))
    for i, kv in enumerate(k):
        out += kv * p[i:i + H, :]
    return out


def gaussian_blur(img: np.ndarray, sigma: float, ksize: int | None = None) -> np.ndarray:
    return conv_sep(img, gaussian_kernel(sigma, ksize))


def sobel(img: np.ndarray, normalize: bool = True):
    """3x3 Sobel. normalize=True 면 /8 (방향·상대크기용)."""
    p = np.pad(img.astype(np.float64), 1, mode="edge")
    gx = (p[:-2, 2:] + 2 * p[1:-1, 2:] + p[2:, 2:]) - (p[:-2, :-2] + 2 * p[1:-1, :-2] + p[2:, :-2])
    gy = (p[2:, :-2] + 2 * p[2:, 1:-1] + p[2:, 2:]) - (p[:-2, :-2] + 2 * p[:-2, 1:-1] + p[:-2, 2:])
    if normalize:
        gx, gy = gx / 8, gy / 8
    return gx, gy


_N8 = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]


def label_components(mask: np.ndarray, min_size: int = 1):
    """8-연결 요소 라벨링 (엣지 픽셀만 순회하는 BFS). 반환: labels (H,W) int, list[(ys,xs)]"""
    H, W = mask.shape
    m = np.zeros((H + 2, W + 2), dtype=bool)
    m[1:-1, 1:-1] = mask
    Wp = W + 2
    flat = m.ravel()
    lab = np.full(flat.shape, -1, dtype=np.int32)
    offs = [dy * Wp + dx for dy, dx in _N8]
    comps = []
    idxs = np.flatnonzero(flat)
    cur = 0
    for start in idxs.tolist():
        if lab[start] >= 0:
            continue
        lab[start] = cur
        q = deque([start])
        members = [start]
        while q:
            p = q.popleft()
            for o in offs:
                n = p + o
                if flat[n] and lab[n] < 0:
                    lab[n] = cur
                    q.append(n)
                    members.append(n)
        mem = np.array(members)
        comps.append(mem)
        cur += 1
    labels = lab.reshape(H + 2, W + 2)[1:-1, 1:-1]
    out = []
    for mem in comps:
        if len(mem) >= min_size:
            ys, xs = mem // Wp - 1, mem % Wp - 1
            out.append((ys, xs))
    return labels, out


def canny(img: np.ndarray, lo: float, hi: float) -> np.ndarray:
    """OpenCV Canny 와 같은 규약: 3x3 Sobel(비정규화), L1 크기, 4방향 NMS, 히스테리시스(8연결)."""
    gx, gy = sobel(img, normalize=False)
    mag = np.abs(gx) + np.abs(gy)
    H, W = img.shape
    ang = (np.rad2deg(np.arctan2(gy, gx)) + 180.0) % 180.0
    q = np.zeros_like(ang, dtype=np.int8)               # 0:0°, 1:45°, 2:90°, 3:135°
    q[(ang >= 22.5) & (ang < 67.5)] = 1
    q[(ang >= 67.5) & (ang < 112.5)] = 2
    q[(ang >= 112.5) & (ang < 157.5)] = 3
    p = np.pad(mag, 1)
    c = p[1:-1, 1:-1]
    # 그래디언트 방향 양쪽 이웃 (영상 y 축이 아래 방향)
    nms2 = np.zeros(mag.shape, dtype=bool)
    nb2 = {
        0: (p[1:-1, :-2], p[1:-1, 2:]),
        1: (p[:-2, :-2], p[2:, 2:]),
        2: (p[:-2, 1:-1], p[2:, 1:-1]),
        3: (p[:-2, 2:], p[2:, :-2]),
    }
    for k, (a, b) in nb2.items():
        sel = q == k
        nms2 |= sel & (c > a) & (c >= b)
    nms = nms2
    weak = nms & (mag > lo)
    strong = nms & (mag > hi)
    labels, _ = label_components(weak)
    keep_labels = np.unique(labels[strong])
    keep_labels = keep_labels[keep_labels >= 0]
    edges = np.isin(labels, keep_labels) & weak
    edges[0, :] = edges[-1, :] = False
    edges[:, 0] = edges[:, -1] = False
    return edges


def bilinear(img: np.ndarray, x: np.ndarray, y: np.ndarray, fill: float = 0.0) -> np.ndarray:
    """img (H,W) 에서 좌표 (x,y) 쌍선형 샘플 (밖은 fill)."""
    H, W = img.shape
    x = np.asarray(x, np.float64)
    y = np.asarray(y, np.float64)
    inside = (x >= 0) & (x <= W - 1) & (y >= 0) & (y <= H - 1)
    xc = np.clip(x, 0, W - 1.000001)
    yc = np.clip(y, 0, H - 1.000001)
    x0 = np.floor(xc).astype(np.int64)
    y0 = np.floor(yc).astype(np.int64)
    fx, fy = xc - x0, yc - y0
    I = img.astype(np.float64, copy=False)
    v = (I[y0, x0] * (1 - fx) * (1 - fy) + I[y0, x0 + 1] * fx * (1 - fy)
         + I[y0 + 1, x0] * (1 - fx) * fy + I[y0 + 1, x0 + 1] * fx * fy)
    return np.where(inside, v, fill)


def _area_matrix(n_in: int, n_out: int) -> np.ndarray:
    """면적 평균 리샘플 가중 행렬 (n_out, n_in)."""
    scale = n_in / n_out
    M = np.zeros((n_out, n_in))
    for i in range(n_out):
        a, b = i * scale, (i + 1) * scale
        j0, j1 = int(np.floor(a)), min(int(np.ceil(b)), n_in)
        for j in range(j0, j1):
            M[i, j] = max(0.0, min(b, j + 1) - max(a, j))
        M[i] /= M[i].sum()
    return M


def resize_area(img: np.ndarray, scale: float) -> np.ndarray:
    """면적 평균 축소 (OpenCV INTER_AREA 와 같은 개념, numpy 만 사용)."""
    H, W = img.shape[:2]
    nh, nw = max(1, int(round(H * scale))), max(1, int(round(W * scale)))
    return _area_matrix(H, nh) @ img.astype(np.float64) @ _area_matrix(W, nw).T


def to_gray(img: np.ndarray) -> np.ndarray:
    if img.ndim == 2:
        return img.astype(np.float64)
    # 입력은 RGB 가정 (Pillow). BGR 이면 호출 측에서 뒤집을 것
    return img[..., 0] * 0.299 + img[..., 1] * 0.587 + img[..., 2] * 0.114


def load_gray(path_or_array) -> np.ndarray:
    if isinstance(path_or_array, np.ndarray):
        return to_gray(path_or_array)
    from PIL import Image
    return np.asarray(Image.open(str(path_or_array)).convert("L")).astype(np.float64)
