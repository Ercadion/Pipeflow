package io.github.ercadion.pipeflow;

import io.github.ercadion.pipeflow.LiveRimDetector.Ellipse;
import io.github.ercadion.pipeflow.LiveRimDetector.Result;

/**
 * 프레임 간 안정화(히스테리시스) — 화면에 보이는 타원이 널뛰지 않게. 추적 대상은 관 내경(Result.inner)
 *  - 포착: 비슷한 타원이 2프레임 연속 검출되어야 표시
 *  - 유지: 비슷한 검출이면 부드럽게 따라감 (작은 떨림은 강하게, 실제 이동은 빠르게)
 *  - 교체: 전혀 다른 타원은 3프레임 연속 같은 자리에서 나와야 바꿈 (중간 모양 섞지 않음)
 *  - 해제: 6프레임 연속 확인 실패
 */
public final class LiveRimTracker {
    public final LiveRimDetector detector = new LiveRimDetector();
    public int acquireHits = 2, switchHits = 3, maxMiss = 6;

    private Ellipse cur, cand;
    private int candHits, miss;
    private double curCov;
    private Result last;

    public Result update(byte[] g, int w, int h) {
        Result r = detector.detect(g, w, h, cur != null ? cur : cand);   // 아직 표시 전이면 후보 주변부터 확인
        last = r;
        if (!r.found) {
            miss++;
            if (miss >= maxMiss) { cur = null; cand = null; candHits = 0; }
            return r;
        }
        Ellipse e = r.inner != null ? r.inner : r.ellipse;   // 표시·추적 대상 = 관 내경
        if (cur != null && similar(cur, e)) {
            double d = Math.hypot(e.cx - cur.cx, e.cy - cur.cy) / cur.a + Math.abs(e.a / cur.a - 1);
            double k = d < 0.03 ? 0.25 : 0.6;
            if (r.coverage > curCov + 0.15 && r.distinct >= 0.5) k = 1.0;   // 훨씬 잘 맞는 타원 → 그대로 채택
            cur = blend(cur, e, k);
            curCov = 0.7 * curCov + 0.3 * r.coverage;
            miss = 0; cand = null; candHits = 0;
            return r;
        }
        // 현재 타원과 다르거나 아직 없음 → 후보로 누적
        if (cand != null && similar(cand, e)) { cand = blend(cand, e, 0.5); candHits++; }
        else { cand = e; candHits = 1; }
        if (cur != null && r.coverage >= 0.8 && r.distinct >= 0.5 && r.coverage > curCov + 0.15) {
            // 주기적 전역 탐색이 훨씬 확실한 타원을 찾음 → 바로 교체
            cur = e; curCov = r.coverage; cand = null; candHits = 0; miss = 0;
            return r;
        }
        if (cur == null) {
            if (candHits >= acquireHits) { cur = cand; curCov = r.coverage; cand = null; candHits = 0; miss = 0; }
        } else {
            miss++;
            if (candHits >= switchHits) { cur = cand; curCov = r.coverage; cand = null; candHits = 0; miss = 0; }
            else if (miss >= maxMiss) { cur = null; }
        }
        return r;
    }

    /** 화면에 표시할 (안정화된) 타원, 없으면 null */
    public Ellipse current() { return cur; }
    public Result last() { return last; }
    public boolean locked() { return cur != null; }
    public void reset() { curCov = 0; cur = null; cand = null; candHits = 0; miss = 0; }

    static boolean similar(Ellipse p, Ellipse q) {
        return Math.hypot(p.cx - q.cx, p.cy - q.cy) < 0.15 * p.a
                && Math.abs(q.a / p.a - 1) < 0.15
                && Math.abs(q.b / q.a - p.b / p.a) < 0.12;
    }

    static Ellipse blend(Ellipse a, Ellipse b, double k) {
        double d = b.phi - a.phi;
        while (d > Math.PI / 2) d -= Math.PI;
        while (d < -Math.PI / 2) d += Math.PI;
        return new Ellipse(a.cx + k * (b.cx - a.cx), a.cy + k * (b.cy - a.cy),
                a.a + k * (b.a - a.a), a.b + k * (b.b - a.b), a.phi + k * d);
    }
}
