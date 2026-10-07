"""
엔진 버전 비교 (개발자용, tools/reeval.py 가 사용 — 앱에는 들어가지 않음)

흐름
  1) 최근 N개 측정을 시험할 엔진으로 '순수 자동' 재분석 (사람이 고친 수면선·테두리는 쓰지 않음)
     → 측정 폴더에 eval_<버전>.json 저장 (result.json·result_auto.json 은 건드리지 않음)
  2) version_report(): 같은 측정의 기준 결과와 비교
     - 기준: baseline 을 주면 eval_<baseline>.json, 아니면 다른 버전의 eval_*.json 중 가장 최근 것,
       그것도 없으면 result_auto.json(촬영 당시 앱의 자동 결과)
     - 참값: labels.truth_* (실측 입력) > 사람이 고친 수면선으로 계산한 최종 결과 > '처음 자동 결과 맞음' 표시된 자동 결과
     - 참값이 있으면 오차가 줄었는지(개선/악화), 없으면 결과가 바뀌었는지만 표시
"""
from __future__ import annotations

import glob
import json
import os
import re

DEPTH_TOL_MM = 0.5      # 이보다 작은 오차 변화는 '같음'
CHANGE_DEPTH_MM = 1.0   # 참값 없을 때 '바뀜' 기준
CHANGE_V_REL = 0.05


def safe_version(v: str) -> str:
    return re.sub(r"[^\w.\-]", "_", str(v))


def eval_name(version: str) -> str:
    return f"eval_{safe_version(version)}.json"


def _load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def find_sessions(root: str):
    """root 아래 모든 측정 폴더(meta.json 있는 폴더)의 상대 경로.
    앱 저장 구조 <날짜>/<시각_v버전>/ 과 예전 형식 <날짜_시각>/ 모두"""
    out = []
    for dp, dns, fns in os.walk(root):
        if "meta.json" in fns:
            out.append(os.path.relpath(dp, root).replace(os.sep, "/"))
            dns[:] = []
    return out


def session_id(rel: str) -> str:
    """측정 이름: 20261007/192009_v0.3.5 → 20261007_192009_v0.3.5"""
    return rel.replace("/", "_")


def recent_sessions(root: str, n: int):
    """측정 이름(날짜_시각…) 내림차순 최근 n개 — 반환은 root 기준 상대 경로"""
    ds = sorted(find_sessions(root), key=session_id, reverse=True)
    return ds[:n] if n and n > 0 else ds


def auto_params(session_dir: str, version: str) -> dict:
    """재분석용 입력: 촬영 당시 설정 그대로, 사람 수정(수면선·테두리) 없이, 실시간 포착 타원은 사용(촬영의 일부)"""
    meta = _load(os.path.join(session_dir, "meta.json")) or {}
    p = dict(meta.get("params") or {})
    for k in ("waterline_pts", "ellipse", "use_live_ellipse"):
        p.pop(k, None)
    p["result_name"] = eval_name(version)
    p["app_version"] = version
    return p


def needs_eval(session_dir: str, version: str) -> bool:
    return _load(os.path.join(session_dir, eval_name(version))) is None


def _summary(r):
    if not r or not r.get("ok"):
        return None
    lv = r.get("level") or {}
    st = r.get("velocity_stiv") or {}
    vf = r.get("velocity_formula") or {}
    return dict(depth=lv.get("depth_mm"), v_mean=st.get("v_mean_mps"), v_surface=st.get("v_surface_mps"),
                Q=st.get("Q_Lps"), Q_formula=vf.get("Q_Lps"), conf=lv.get("waterline_confidence"),
                rim_source=lv.get("rim_source"), inner_source=lv.get("inner_source"))


def _prev_result(d, version, meta, baseline=None):
    """비교 기준 자동 결과와 그 버전"""
    if baseline:
        r = _load(os.path.join(d, eval_name(baseline)))
        return (r, str(baseline), "eval") if r is not None else (None, None, None)
    cur = eval_name(version)
    evs = []
    for f in glob.glob(os.path.join(d, "eval_*.json")):
        if os.path.basename(f) == cur:
            continue
        r = _load(f)
        if r is not None:
            evs.append((os.path.getmtime(f), r))
    if evs:
        evs.sort(key=lambda t: t[0])
        r = evs[-1][1]
        return r, str(r.get("version", "?")), "eval"
    r = _load(os.path.join(d, "result_auto.json"))
    if r is not None:
        return r, str(r.get("version") or meta.get("app_version", "?")), "result_auto"
    lab = _load(os.path.join(d, "labels.json")) or {}
    r = _load(os.path.join(d, "result.json"))
    if r is not None and not lab.get("manual_waterline") and not lab.get("edits") \
            and (r.get("level") or {}).get("rim_source") != "manual_or_previous":
        return r, str(r.get("version") or meta.get("app_version", "?")), "result_legacy"
    return None, None, None


def _num(x):
    try:
        v = float(x)
        return v if v == v else None
    except (TypeError, ValueError):
        return None


def _truth(d, lab):
    """참값: (수심, 출처), 평균유속, 유량"""
    t_depth, src = _num(lab.get("truth_depth_mm")), "measured"
    if t_depth is None and lab.get("manual_waterline") and lab.get("final_feedback") != "wrong":
        fin = _summary(_load(os.path.join(d, "result.json")))
        if fin and fin["depth"] is not None:
            t_depth, src = fin["depth"], "corrected"
    if t_depth is None and lab.get("auto_feedback") == "correct":
        a = _summary(_load(os.path.join(d, "result_auto.json")))
        if a and a["depth"] is not None:
            t_depth, src = a["depth"], "auto_confirmed"
    return (t_depth, src if t_depth is not None else None,
            _num(lab.get("truth_v_mean_mps")), _num(lab.get("truth_Q_Lps")))


def _abs(a, b):
    return None if a is None or b is None else abs(a - b)


def _mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def version_report(root: str, version: str, names=None, recent: int = 20, baseline=None) -> dict:
    names = names if names else recent_sessions(root, recent)
    rows = []
    for name in names:
        d = os.path.join(root, name)
        meta = _load(os.path.join(d, "meta.json")) or {}
        lab = _load(os.path.join(d, "labels.json")) or {}
        new_r = _load(os.path.join(d, eval_name(version)))
        row = dict(session=session_id(name), path=name, captured_version=meta.get("app_version"),
                   captured_build=meta.get("app_build"))
        if new_r is None:
            row["status"] = "not_evaluated"
            rows.append(row)
            continue
        new = _summary(new_r)
        prev_r, prev_ver, prev_src = _prev_result(d, version, meta, baseline)
        prev = _summary(prev_r)
        t_depth, t_src, t_v, t_q = _truth(d, lab)
        row.update(prev_version=prev_ver, prev_source=prev_src, truth_source=t_src, truth_depth_mm=t_depth,
                   truth_v_mean_mps=t_v, truth_Q_Lps=t_q,
                   new_ok=new is not None, prev_ok=prev is not None,
                   new_error=None if new else new_r.get("error"))
        for k in ("depth", "v_mean", "Q", "conf"):
            row[f"prev_{k}"] = prev[k] if prev else None
            row[f"new_{k}"] = new[k] if new else None
        row["new_inner_source"] = new["inner_source"] if new else None
        row["err_depth_prev"] = _abs(row["prev_depth"], t_depth)
        row["err_depth_new"] = _abs(row["new_depth"], t_depth)
        row["err_v_prev"] = _abs(row["prev_v_mean"], t_v)
        row["err_v_new"] = _abs(row["new_v_mean"], t_v)
        # 판정
        if not new:
            st = "new_failed" if prev else "both_failed"
        elif not prev:
            st = "fixed" if prev_r is not None else "no_previous"
        elif row["err_depth_new"] is not None and row["err_depth_prev"] is not None:
            dd = row["err_depth_new"] - row["err_depth_prev"]
            st = "improved" if dd < -DEPTH_TOL_MM else ("worse" if dd > DEPTH_TOL_MM else "same")
        else:
            ch = _abs(row["new_depth"], row["prev_depth"])
            dv = _abs(row["new_v_mean"], row["prev_v_mean"])
            v_ref = max(abs(row["prev_v_mean"] or 0), 0.2)
            changed = (ch is not None and ch > CHANGE_DEPTH_MM) or (dv is not None and dv > max(0.01, CHANGE_V_REL * v_ref))
            st = "changed" if changed else "same"
        row["status"] = st
        row["depth_change_mm"] = None if row["new_depth"] is None or row["prev_depth"] is None \
            else row["new_depth"] - row["prev_depth"]
        rows.append(row)

    ev = [r for r in rows if r["status"] != "not_evaluated"]
    tr = [r for r in ev if r.get("err_depth_new") is not None and r.get("err_depth_prev") is not None]
    trv = [r for r in ev if r.get("err_v_new") is not None and r.get("err_v_prev") is not None]
    cnt = {}
    for r in ev:
        cnt[r["status"]] = cnt.get(r["status"], 0) + 1
    prev_vers = {}
    for r in ev:
        if r.get("prev_version"):
            prev_vers[r["prev_version"]] = prev_vers.get(r["prev_version"], 0) + 1
    summary = dict(
        version=version, baseline=baseline, n_sessions=len(rows), n_evaluated=len(ev), counts=cnt, prev_versions=prev_vers,
        n_with_depth_truth=len(tr), mae_depth_prev=_mean([r["err_depth_prev"] for r in tr]),
        mae_depth_new=_mean([r["err_depth_new"] for r in tr]),
        n_with_v_truth=len(trv), mae_v_prev=_mean([r["err_v_prev"] for r in trv]),
        mae_v_new=_mean([r["err_v_new"] for r in trv]),
        mean_abs_depth_change_mm=_mean([abs(r["depth_change_mm"]) for r in ev if r.get("depth_change_mm") is not None]))
    worst = sorted([r for r in ev if r["status"] in ("worse", "new_failed")],
                   key=lambda r: -((r.get("err_depth_new") or 0) - (r.get("err_depth_prev") or 0)))[:5]
    best = sorted([r for r in ev if r["status"] in ("improved", "fixed")],
                  key=lambda r: (r.get("err_depth_new") or 0) - (r.get("err_depth_prev") or 0))[:5]
    return dict(summary=summary, rows=rows, worst=[r["session"] for r in worst], best=[r["session"] for r in best],
                text=_text(summary, rows, [r["session"] for r in worst], [r["session"] for r in best]))


_STATUS_KO = dict(improved="개선", worse="악화", same="같음", changed="바뀜(참값 없음)", fixed="새로 성공",
                  new_failed="시험 엔진 실패", both_failed="둘 다 실패", no_previous="이전 결과 없음",
                  not_evaluated="미분석")


def _fmt(x, f="{:.1f}"):
    return "-" if x is None else f.format(x)


def _text(s, rows, worst, best):
    L = [f"■ 엔진 {s['version']} 재분석: 최근 {s['n_sessions']}건 중 {s['n_evaluated']}건 분석",
         "   비교 기준 버전: " + (", ".join(f"v{k} {v}건" for k, v in s["prev_versions"].items()) or "-"),
         "   " + " · ".join(f"{_STATUS_KO.get(k, k)} {v}" for k, v in s["counts"].items())]
    if s["n_with_depth_truth"]:
        L.append(f"■ 수심 오차 (참값 있는 {s['n_with_depth_truth']}건, 평균 절대오차)")
        L.append(f"   기준 {_fmt(s['mae_depth_prev'], '{:.2f}')} mm → 시험 엔진 {_fmt(s['mae_depth_new'], '{:.2f}')} mm")
    else:
        L.append("■ 수심 참값이 있는 측정이 없음 — 앱 결과 화면에서 실측 수심을 입력하거나 수면선을 고친 측정이 있어야 개선 여부를 판정")
    if s["n_with_v_truth"]:
        L.append(f"■ 평균유속 오차 ({s['n_with_v_truth']}건): 기준 {_fmt(s['mae_v_prev'], '{:.3f}')} → "
                 f"시험 엔진 {_fmt(s['mae_v_new'], '{:.3f}')} m/s")
    L.append(f"■ 수심 변화량 평균 {_fmt(s['mean_abs_depth_change_mm'], '{:.2f}')} mm")
    by = {r["session"]: r for r in rows}
    if worst:
        L.append("■ 나빠진 측정 (확인 필요)")
        for n in worst:
            r = by[n]
            if r["status"] == "new_failed":
                L.append(f"   {n}: 시험 엔진 분석 실패 — {r.get('new_error') or ''}")
            else:
                L.append(f"   {n}: 수심 {_fmt(r['prev_depth'])} → {_fmt(r['new_depth'])} mm (참 {_fmt(r['truth_depth_mm'])})")
    if best:
        L.append("■ 좋아진 측정")
        for n in best:
            r = by[n]
            L.append(f"   {n}: 수심 {_fmt(r['prev_depth'])} → {_fmt(r['new_depth'])} mm (참 {_fmt(r['truth_depth_mm'])})")
    L.append("■ 측정별")
    for r in rows:
        if r["status"] == "not_evaluated":
            L.append(f"   {r['session']}: 미분석")
            continue
        L.append(f"   {r['session']} [{_STATUS_KO.get(r['status'], r['status'])}] 수심 {_fmt(r.get('prev_depth'))}→"
                 f"{_fmt(r.get('new_depth'))} mm · 평균유속 {_fmt(r.get('prev_v_mean'), '{:.3f}')}→"
                 f"{_fmt(r.get('new_v_mean'), '{:.3f}')} m/s" + (f" · 참 {_fmt(r['truth_depth_mm'])} mm({r['truth_source']})"
                                                                if r.get("truth_depth_mm") is not None else ""))
    return "\n".join(L)


def save_report(root: str, version: str, rep: dict) -> str:
    """root/reeval_<버전>.json (전체) + reeval_<버전>.csv (측정별 표)"""
    import csv
    base = os.path.join(root, f"reeval_{safe_version(version)}")
    with open(base + ".json", "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1, default=float)
    rows = rep["rows"]
    keys = []
    for r in rows:
        for k in r:
            if k not in keys:
                keys.append(k)
    with open(base + ".csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)
    return base
