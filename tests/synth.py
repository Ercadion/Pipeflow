"""합성(렌더링) 영상으로 알고리즘 검증용 장면 생성."""
import math
import numpy as np
import torch
import torch.nn.functional as F

DT = torch.float64


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return torch.tensor([[1, 0, 0], [0, c, -s], [0, s, c]], dtype=DT)


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return torch.tensor([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=DT)


class Scene:
    """세계좌표: X 오른쪽, Y 아래(중력), Z 관 축(관 안쪽). 끝단 중심 = 원점.
    카메라: 관 축에서 높이 cam_h(위), 끝단 앞 cam_z 거리, 끝단 중심을 바라봄 + roll/yaw."""

    def __init__(self, D=100.0, wall=4.0, depth=40.0, cam_h=60.0, cam_z=180.0, cam_x=0.0,
                 roll_deg=0.0, f=900.0, W=960, H=960, L=600.0, seed=0, pp=None):
        self.D, self.wall, self.h, self.L = D, wall, depth, L
        self.r_in, self.r_out = D / 2, D / 2 + wall
        self.f, self.W, self.H = f, W, H
        self.pp = pp or (W / 2, H / 2)
        cam = torch.tensor([cam_x, -cam_h, -cam_z], dtype=DT)
        fwd = -cam / cam.norm()                      # 끝단 중심을 바라봄
        down = torch.tensor([0, 1.0, 0], dtype=DT)
        right = torch.linalg.cross(down, fwd); right /= right.norm()
        dn = torch.linalg.cross(fwd, right)
        Rcw = torch.stack([right, dn, fwd])            # world->cam 회전 (행 = 카메라 축)
        Rcw = rot_z(math.radians(roll_deg)) @ Rcw
        self.R, self.cam = Rcw, cam
        self.n = Rcw @ torch.tensor([0, 0, 1.0], dtype=DT)
        self.g = Rcw @ torch.tensor([0, 1.0, 0], dtype=DT)
        self.C = Rcw @ (-cam)
        self.K = torch.tensor([[f, 0, self.pp[0]], [0, f, self.pp[1]], [0, 0, 1]], dtype=DT)
        g = torch.Generator().manual_seed(seed)
        # 수면 무늬 텍스처 (s,l) 1 px = 0.5 mm
        self.tex_res = 0.5
        tex = torch.rand(1, 1, int(3 * L / self.tex_res), int(D / self.tex_res) + 4, generator=g, dtype=DT)
        k = torch.exp(-torch.arange(-6, 7, dtype=DT) ** 2 / 8); k /= k.sum()
        tex = F.conv2d(F.pad(tex, (6, 6, 6, 6), mode="circular"), k[None, None, None, :])
        tex = F.conv2d(tex, k[None, None, :, None])
        tex = (tex - tex.mean()) / tex.std()
        self.tex = tex

    def project_world(self, Xw):
        Xc = (Xw - self.cam) @ self.R.T
        x = Xc @ self.K.T
        return x[:, :2] / x[:, 2:3]

    def outer_circle_pts(self, n=200):
        t = torch.linspace(0, 2 * math.pi, n, dtype=DT)
        Xw = torch.stack([self.r_out * torch.cos(t), self.r_out * torch.sin(t), torch.zeros_like(t)], 1)
        return self.project_world(Xw)

    def inner_circle_pts(self, n=200):
        t = torch.linspace(0, 2 * math.pi, n, dtype=DT)
        Xw = torch.stack([self.r_in * torch.cos(t), self.r_in * torch.sin(t), torch.zeros_like(t)], 1)
        return self.project_world(Xw)

    def waterline_pts(self):
        d = self.r_in - self.h
        half = math.sqrt(self.r_in**2 - d**2)
        Xw = torch.tensor([[-0.8 * half, d, 0], [0.8 * half, d, 0]], dtype=DT)
        return self.project_world(Xw)

    def _static_field(self):
        if not hasattr(self, "_static"):
            g = torch.Generator().manual_seed(1234)
            n = torch.rand(1, 1, self.H, self.W, generator=g, dtype=DT)
            k = torch.exp(-torch.arange(-6, 7, dtype=DT) ** 2 / 6); k /= k.sum()
            n = F.conv2d(F.pad(n, (6, 6, 6, 6), mode="replicate"), k[None, None, None, :])
            n = F.conv2d(n, k[None, None, :, None])[0, 0]
            self._static = (n - n.mean()) / n.std()
        return self._static

    def render(self, t=0.0, v=0.0, static=0.0, noise=0.0, seed=None):
        """v: 유속 [mm/s] (+Z), t: 시간 [s]. 회색조 uint8 BGR 영상 반환.
        static: 움직이지 않는 무늬(반사광/랩 주름 모사) 진폭, noise: 프레임별 센서 잡음 표준편차."""
        W, H = self.W, self.H
        u, vv = torch.meshgrid(torch.arange(W, dtype=DT), torch.arange(H, dtype=DT), indexing="xy")
        rays = torch.stack([(u - self.pp[0]) / self.f, (vv - self.pp[1]) / self.f, torch.ones_like(u)], -1)
        rw = rays @ self.R                       # 세계좌표 방향
        o = self.cam
        t0 = -o[2] / rw[..., 2]                  # 끝단 평면 Z=0
        Y = o + t0[..., None] * rw
        rho = torch.sqrt(Y[..., 0] ** 2 + Y[..., 1] ** 2)
        img = torch.full((H, W), 60.0, dtype=DT)
        # 배경 체커
        chk = ((torch.floor(u / 40) + torch.floor(vv / 40)) % 2) * 25
        img = img + chk
        ring = (rho > self.r_in) & (rho <= self.r_out)
        img[ring] = 235.0
        inside = rho <= self.r_in
        d = self.r_in - self.h
        water = inside & (Y[..., 1] > d)
        img[water] = 150.0 + 6 * torch.sin(Y[..., 0][water] * 0.3)
        air = inside & ~water
        # 수면 평면 Y = d 와 교차
        ts = (d - o[1]) / rw[..., 1]
        X = o + ts[..., None] * rw
        hit = air & (ts > t0) & (X[..., 2] > 0) & (X[..., 2] < self.L) & (X[..., 0].abs() < self.r_in)
        img[air] = 200.0
        sidx = ((X[..., 2] - v * t) / self.tex_res) % self.tex.shape[2]
        lidx = (X[..., 0] + self.r_in) / self.tex_res + 2
        grid = torch.stack([lidx / (self.tex.shape[3] - 1) * 2 - 1, sidx / (self.tex.shape[2] - 1) * 2 - 1], -1)
        val = F.grid_sample(self.tex.float(), grid[None].float(), align_corners=True, padding_mode="border")[0, 0].double()
        img[hit] = 110.0 + 35 * val[hit]
        if static:
            img[hit] += static * self._static_field()[hit]
        if noise:
            gg = torch.Generator().manual_seed(int(seed if seed is not None else t * 1e6) % (2**31))
            img = img + noise * torch.randn(img.shape, generator=gg, dtype=DT)
        img = img.clamp(0, 255).byte().numpy()
        return np.repeat(img[..., None], 3, -1)
