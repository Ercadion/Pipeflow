"""
측정 폴더 읽기 (개발자 PC 도구 공용) — 앱 저장 형식이 바뀌어도 여기 한 곳에서 맞춤

앱 v0.3.6~ (저장 전 확인 단계에서만 고침, 저장 후 결과 불변)
  result.json            자동 분석 결과 (촬영 직후 1회)
  result_corrected.json  저장 전 확인 단계에서 사람이 고친 결과 (있을 때만) — correction: 무엇을 고쳤는지
  annotations.json       실측값·메모 이력 {entries: [{time_ms, app_version, app_build, field, value}]} (value null = 지움)
  record.json            저장 정보 + 파일별 sha256
  .review_pending        저장 전 (앱에서 아직 저장 안 함 — 내보내기에는 안 들어감)
예전 v0.3.4~0.3.5: result_auto.json(자동) + result.json(마지막으로 고친 결과) + labels.json(truth_*, note, 피드백)
그 이전: result.json + labels.json (수정 흔적이 없으면 result.json 을 자동 결과로 봄)
"""
from __future__ import annotations

import hashlib
import json
import os

ANNOTATION_FIELDS = ("truth_depth_mm", "truth_v_mean_mps", "truth_Q_Lps", "note")


def load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def waterline(r):
    wl = ((r or {}).get("overlay") or {}).get("waterline")
    return [wl[0][0], wl[0][1], wl[1][0], wl[1][1]] if wl else None


def differs(a, b):
    """수면선·테두리가 0.5 px 넘게 다르면 True"""
    if not a or not b:
        return False
    wa, wb = waterline(a), waterline(b)
    if wa and wb and any(abs(x - y) > 0.5 for x, y in zip(wa, wb)):
        return True
    ea = (a.get("overlay") or {}).get("ellipse") or {}
    eb = (b.get("overlay") or {}).get("ellipse") or {}
    return bool(ea and eb and any(abs(ea.get(k, 0) - eb.get(k, 0)) > 0.5 for k in ("cx", "cy", "a", "b")))


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""):
            h.update(b)
    return h.hexdigest()


def read_session(d):
    """
    → dict(meta, auto, corrected, correction, ann, record, pending, format, labels, integrity, auto_lost)
      auto: 사람이 고치기 전 자동 결과 (없으면 None)   corrected: 사람이 고친 결과 (없으면 None)
      ann: 실측값·메모 현재 값    integrity: 'ok' | 'mismatch:<파일들>' | 'no_record'
    """
    j = lambda n: load_json(os.path.join(d, n))
    meta = j("meta.json") or {}
    labels = j("labels.json") or {}
    res, res_auto, res_corr = j("result.json"), j("result_auto.json"), j("result_corrected.json")
    auto_lost = False
    if res_auto is None and not os.path.exists(os.path.join(d, "labels.json")):
        fmt, auto, corr = "v0.3.6", res, res_corr        # 새 형식 (또는 고친 적 없는 예전 측정: 같은 의미)
    elif res_auto is not None:
        fmt, auto = "v0.3.4", res_auto
        corr = res if (res and res.get("ok") and differs(res_auto, res)) else None
    else:
        fmt = "legacy"
        touched = labels.get("manual_waterline") or labels.get("edits") or labels.get("legacy_auto_result_lost") \
            or ((res or {}).get("level") or {}).get("rim_source") == "manual_or_previous"
        auto, corr, auto_lost = (None, res, True) if touched else (res, None, False)
    ann = {k: labels[k] for k in ANNOTATION_FIELDS if labels.get(k) not in (None, "")}
    for e in (j("annotations.json") or {}).get("entries", []):
        k = e.get("field")
        if e.get("value") is None:
            ann.pop(k, None)
        else:
            ann[k] = e["value"]
    rec = j("record.json")
    if rec is None:
        integrity = "no_record"
    else:
        bad = [n for n, h in (rec.get("sha256") or {}).items()
               if not os.path.exists(os.path.join(d, n)) or _sha256(os.path.join(d, n)) != h]
        integrity = "ok" if not bad else "mismatch:" + "|".join(bad)
    return dict(meta=meta, auto=auto, corrected=corr, correction=(corr or {}).get("correction"), ann=ann,
                record=rec, pending=os.path.exists(os.path.join(d, ".review_pending")), format=fmt,
                labels=labels, integrity=integrity, auto_lost=auto_lost)
