"""명령행 데모
예)
  python -m pipeflow.cli data/img3.jpg --diameter 100 --wall 4 --slope 0.005 --material glass
  python -m pipeflow.cli f0.jpg --diameter 100 --frames f0.jpg f1.jpg f2.jpg --fps 30 --f-px 1500
"""
import argparse
import json
import math
import sys

import cv2

from .calibration import load_calibration
from .geometry import f_px_from_exif
from .pipeline import (draw_overlay, measure_level, velocity_from_frames,
                       velocity_from_frames_stiv, velocity_from_level)


def main(argv=None):
    ap = argparse.ArgumentParser(description="관로 끝단 사진 -> 수위/유속")
    ap.add_argument("image")
    ap.add_argument("--diameter", type=float, required=True, help="관 내경 [mm]")
    ap.add_argument("--wall", type=float, default=0.0, help="관 두께 [mm] (검출 테두리=외경일 때)")
    ap.add_argument("--rim-is", choices=["outer", "inner"], default="outer")
    ap.add_argument("--waterline", type=float, nargs=4, metavar=("X1", "Y1", "X2", "Y2"),
                    help="수면선 수동 지정 (이미지 px)")
    ap.add_argument("--texture-sign", type=int, default=1, choices=[-1, 0, 1],
                    help="+1: 물 쪽이 더 거친 무늬, -1: 더 매끈, 0: 무관")
    ap.add_argument("--f-px", type=float, default=None, help="초점거리 [px] (완전투시 보정)")
    ap.add_argument("--exif", action="store_true", help="EXIF 로 f_px 추정 (원본 사진일 때)")
    ap.add_argument("--calib", default=None, help="캘리브레이션 JSON (python -m pipeflow.calibration 으로 생성)")
    ap.add_argument("--camera-below", action="store_true", help="카메라가 관 축보다 아래")
    # 등류 공식
    ap.add_argument("--slope", type=float, default=None, help="관 경사 S (예: 0.005 = 0.5%%)")
    ap.add_argument("--n", type=float, default=None, help="Manning 조도계수")
    ap.add_argument("--material", default="glass")
    ap.add_argument("--regime", default="auto", choices=["auto", "manning", "laminar"])
    # 프레임 추적
    ap.add_argument("--frames", nargs="+", default=None, help="연속 프레임 (첫 장 = image 와 같은 시점)")
    ap.add_argument("--fps", type=float, default=None)
    ap.add_argument("--method", choices=["stiv", "phase"], default="stiv",
                    help="프레임 유속 방법: stiv(H-STIV, 기본) | phase(프레임쌍 위상상관)")
    ap.add_argument("--lines", type=int, default=5, help="STIV 측정선 개수(폭 방향)")
    ap.add_argument("--out", default=None, help="오버레이 저장 경로")
    a = ap.parse_args(argv)

    f_px = a.f_px
    if f_px is None and a.exif:
        f_px = f_px_from_exif(a.image)
    calib = load_calibration(a.calib) if a.calib else None
    wl = None if a.waterline is None else [a.waterline[:2], a.waterline[2:]]
    lvl = measure_level(a.image, a.diameter, a.wall, rim_is=a.rim_is, waterline_pts=wl,
                        f_px=f_px, camera_above=not a.camera_below,
                        waterline_kwargs=dict(texture_sign=a.texture_sign), calib=calib)
    out = dict(level=lvl.summary())
    if lvl.extra.get("warnings"):
        out["level"]["warnings"] = lvl.extra["warnings"]
    Re = None
    if a.slope is not None:
        v = velocity_from_level(lvl, a.slope, a.n, a.material, a.regime)
        Re = v.get("Re")
        out["velocity_formula"] = dict(V_mean_mps=round(v["V"], 4), Q_Lps=round(v["Q"] * 1000, 4),
                                       regime=v["regime"], Re=round(v.get("Re", 0)),
                                       Fr=round(v.get("Fr", float("nan")), 3),
                                       hydraulic_radius_mm=round(v["section"]["Rh"] * 1000, 3),
                                       area_mm2=round(v["section"]["A"] * 1e6, 1),
                                       warnings=v.get("warnings", []))
    if a.frames:
        if not a.fps:
            sys.exit("--frames 에는 --fps 가 필요")
        if a.method == "stiv":
            v2 = velocity_from_frames_stiv(a.frames, 1 / a.fps, lvl, f_px=f_px, Re_hint=Re,
                                           n_lines=a.lines)
            out["velocity_tracking"] = dict(
                method="H-STIV", v_surface_mps=round(v2["v_surface"], 4),
                v_mean_mps=round(v2["v_mean"], 4), coef=v2["coef"], frame_stride=v2["stride"],
                lines=[dict(l_mm=l["l_mm"], v_mps=round(l["v_surface"], 4), used=l["used"],
                            agreement=round(l["agreement"], 3)) for l in v2["lines"]],
                graze_deg=round(v2["graze_deg"], 1), warnings=v2["warnings"])
        else:
            v2 = velocity_from_frames(a.frames, 1 / a.fps, lvl, f_px=f_px, Re_hint=Re)
            out["velocity_tracking"] = dict(method="phase_correlation",
                                            v_surface_mps=round(v2["v_surface"], 4),
                                            v_mean_mps=round(v2["v_mean"], 4), coef=v2["coef"],
                                            per_pair=[round(x, 4) for x in v2["per_pair"]],
                                            graze_deg=round(v2["graze_deg"], 1),
                                            warnings=v2["warnings"])
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if a.out:
        cv2.imwrite(a.out, draw_overlay(a.image, lvl))


if __name__ == "__main__":
    main()
