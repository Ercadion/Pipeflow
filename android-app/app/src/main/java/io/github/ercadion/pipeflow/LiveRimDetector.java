package io.github.ercadion.pipeflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * 실시간 관 테두리(타원) 검출기 — 순수 Java (외부 라이브러리 없음, 데스크톱 javac 로 단독 시험 가능)
 *
 * 입력: 저해상도 회색조 영상 (예: 320×240, 센서 방향 그대로)
 * 처리:
 *   1) 가우시안 평활 → Sobel → 비최대 억제(NMS) → 엣지 점
 *   2) 엣지 연결요소(곡선 조각) → 조각 1~3개 조합에 Fitzgibbon 타원 피팅 = 후보
 *   3) 후보 점수 = 엣지 inlier(거리·기울기 방향 일치)가 덮는 둘레 각도 비율
 *   4) 이전 프레임 타원이 있으면 그 주변만 다시 피팅(추적) — 빠르고 안정적
 * 출력: 타원(cx, cy, a, b, phi), 둘레 덮임 비율, 테두리 안쪽 밝기/선명도
 */
public final class LiveRimDetector {

    /** 타원 (영상 좌표, phi: 장축이 x 축과 이루는 각 [rad]) */
    public static final class Ellipse {
        public double cx, cy, a, b, phi;

        public Ellipse(double cx, double cy, double a, double b, double phi) {
            this.cx = cx; this.cy = cy; this.a = a; this.b = b; this.phi = phi;
        }

        public Ellipse scaled(double s) { return new Ellipse(cx * s, cy * s, a * s, b * s, phi); }

        /** 둘레 위 점 (매개변수 t) */
        public double[] point(double t) {
            double c = Math.cos(phi), s = Math.sin(phi);
            double x = a * Math.cos(t), y = b * Math.sin(t);
            return new double[]{cx + c * x - s * y, cy + s * x + c * y};
        }
    }

    public static final class Result {
        public boolean found;
        public Ellipse ellipse;
        public double coverage;      // 화면 안 둘레 중 엣지로 확인된 비율 (0~1)
        public double brightness;    // 테두리 안쪽 평균 밝기 (0~255)
        public double sharpness;     // 테두리 안쪽 평균 기울기 크기
        public int edgePoints;
        public boolean tracked;      // 이전 타원 추적으로 찾았는지
        public double millis;
        public double distinct;      // 덮임 - 주변(±15%) 덮임: 잡음 많은 곳의 가짜 타원 걸러냄
        public Ellipse inner;        // 관 내경 테두리 (끝단 면의 안쪽 동심 테두리, 없으면 ellipse 와 같음)
        public int innerSource;      // 0: 테두리 하나뿐(그대로 내경), 1: 검출된 것이 이미 안쪽, 2: 안쪽 동심 테두리 찾음
    }

    // 조정 가능한 값
    public double minRatio = 0.20;   // b/a 하한 (위에서 75° 로 내려다봐도 cos75°=0.26)
    public double minMinorFrac = 0.04; // 단반축 ≥ 짧은 변 × 이 값 (직선 띠가 납작한 타원으로 잡히는 것 방지)
    public double foundCov = 0.5;    // 포착 판정 둘레 덮임 비율
    public double quadFrac = 0.3;    // 4분면 각각 이 비율 이상 확인되어야 (최대 1개 예외)
    public double minSizeFrac = 0.18; // 장반축 ≥ 짧은 변 × 이 값
    static boolean DEBUG = Boolean.getBoolean("rimdebug");
    public double minDistinct = 0.2; // 포착 판정: 주변 대비 덮임 차이
    public double edgeFrac = 0.20; // 기울기 상위 비율을 엣지 후보로
    public int bins = 72;
    public int globalEvery = 8;      // 추적 중에도 N 프레임마다 전역 탐색으로 더 나은 타원 확인
    private int calls = 0;

    private final Random rng = new Random(1);

    // 사용자 지정 검사 범위 (화면 터치) — 입력 영상 좌표
    private boolean roiOn;
    private double roiX, roiY, roiR;

    /** 터치한 점(x,y)과 반경 r 의 원을 주 검사 범위로: 이 안의 엣지로 후보를 만들고, 터치 점을 품는 타원만 인정 */
    public void setRoi(double x, double y, double r) { roiOn = true; roiX = x; roiY = y; roiR = r; }
    public void clearRoi() { roiOn = false; }
    public boolean hasRoi() { return roiOn; }

    /** 터치 점이 타원(1.25배) 안에 있는지 */
    private boolean roiOk(Ellipse e) {
        if (!roiOn) return true;
        double c = Math.cos(e.phi), s = Math.sin(e.phi);
        double dx = roiX - e.cx, dy = roiY - e.cy;
        double u = (c * dx + s * dy) / e.a, v = (-s * dx + c * dy) / e.b;
        return u * u + v * v <= 1.25 * 1.25;
    }

    /** 조각에 검사 범위 안 점이 있는지 */
    private boolean segInRoi(int seg) {
        if (!roiOn) return true;
        int st = segStart.get(seg), len = segLen.get(seg);
        int step = Math.max(1, len / 12);
        double r2 = roiR * roiR;
        for (int i = 0; i < len; i += step) {
            double dx = ex[st + i] - roiX, dy = ey[st + i] - roiY;
            if (dx * dx + dy * dy <= r2) return true;
        }
        return false;
    }

    // 작업 버퍼 (재사용)
    private int bw, bh;
    private float[] blur, gx, gy, mag;
    private int[] label;

    // 엣지 점
    private float[] ex = new float[0], ey = new float[0], enx = new float[0], eny = new float[0];
    private int ne;

    // ------------------------------------------------------------------
    public Result detect(byte[] gray, int w, int h, Ellipse prior) {
        long t0 = System.nanoTime();
        Result r = new Result();
        rng.setSeed(1);   // 같은 영상이면 매번 같은 결과 (프레임마다 후보가 바뀌는 것 방지)
        alloc(w, h);
        preprocess(gray, w, h);
        extractEdges(w, h);
        r.edgePoints = ne;
        if (ne < 30) { r.millis = (System.nanoTime() - t0) / 1e6; return r; }

        // 점수 계산용 점 (최대 2000)
        int[] scoreIdx = subsample(ne, 2000);

        Cand best = null;
        // 1) 추적
        if (prior != null) {
            Cand c = fitNear(prior, w, h, scoreIdx);
            if (c != null && c.cov >= 0.5 && roiOk(c.e)) { best = c; r.tracked = true; }
        }
        // 2) 전역 탐색 (추적 실패 시 + 주기적으로)
        calls++;
        if (best == null || calls % globalEvery == 0) {
            Cand g = globalSearch(w, h, scoreIdx);
            if (g != null) {
                // 전역 후보를 추적과 같은 방식(주변 inlier 재피팅)으로 다듬음 → 첫 포착부터 정확
                Cand gp = fitNear(g.e, w, h, scoreIdx);
                if (gp != null && gp.cov > g.cov) g = gp;
            }
            if (g != null && (best == null || g.occ > best.occ * 1.1)) { best = g; r.tracked = false; }
        }
        if (best != null) {
            Cand ref = refine(best.e, w, h, scoreIdx);
            if (ref != null && ref.cov >= best.cov * 0.95) best = ref;
            r.distinct = best.cov - clutterCov(best.e, w, h, scoreIdx);
            r.found = best.cov >= foundCov && r.distinct >= minDistinct && roiOk(best.e);
            r.ellipse = best.e;
            r.coverage = best.cov;
            interiorStats(best.e, w, h, r);
            if (r.found) selectInner(best.e, w, h, scoreIdx, r);
        }
        r.millis = (System.nanoTime() - t0) / 1e6;
        return r;
    }

    // ------------------------------------------------------------------
    private void alloc(int w, int h) {
        if (w == bw && h == bh) return;
        bw = w; bh = h;
        int n = w * h;
        blur = new float[n]; gx = new float[n]; gy = new float[n]; mag = new float[n];
        label = new int[n];
    }

    /** 5탭 가우시안 [1 4 6 4 1]/16 분리형 + Sobel */
    private void preprocess(byte[] g, int w, int h) {
        float[] tmp = new float[w * h];
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 2), x1 = Math.max(0, x - 1), x3 = Math.min(w - 1, x + 1), x4 = Math.min(w - 1, x + 2);
                tmp[o + x] = ((g[o + x0] & 0xFF) + 4 * (g[o + x1] & 0xFF) + 6 * (g[o + x] & 0xFF)
                        + 4 * (g[o + x3] & 0xFF) + (g[o + x4] & 0xFF)) / 16f;
            }
        }
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 2), y1 = Math.max(0, y - 1), y3 = Math.min(h - 1, y + 1), y4 = Math.min(h - 1, y + 2);
            for (int x = 0; x < w; x++) {
                blur[y * w + x] = (tmp[y0 * w + x] + 4 * tmp[y1 * w + x] + 6 * tmp[y * w + x]
                        + 4 * tmp[y3 * w + x] + tmp[y4 * w + x]) / 16f;
            }
        }
        Arrays.fill(gx, 0); Arrays.fill(gy, 0); Arrays.fill(mag, 0);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float a = blur[i - w - 1], b = blur[i - w], c = blur[i - w + 1];
                float d = blur[i - 1], f = blur[i + 1];
                float g7 = blur[i + w - 1], g8 = blur[i + w], g9 = blur[i + w + 1];
                float sx = (c + 2 * f + g9) - (a + 2 * d + g7);
                float sy = (g7 + 2 * g8 + g9) - (a + 2 * b + c);
                gx[i] = sx; gy[i] = sy;
                mag[i] = (float) Math.sqrt(sx * sx + sy * sy);
            }
        }
    }

    /** 적응 임계값 + NMS → 엣지 점, 연결요소 라벨 */
    private void extractEdges(int w, int h) {
        // 임계값: 기울기 크기 상위 12% (최소 12)
        int[] hist = new int[1024];
        int cnt = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int v = Math.min(1023, (int) mag[y * w + x]); hist[v]++; cnt++;
        }
        int target = (int) (cnt * edgeFrac), acc = 0, thr = 1023;
        for (int v = 1023; v >= 0; v--) { acc += hist[v]; if (acc >= target) { thr = v; break; } }
        float t = Math.max(12f, thr);

        Arrays.fill(label, -1);
        boolean[] edge = new boolean[w * h];
        int n = 0;
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                int i = y * w + x;
                float m = mag[i];
                if (m < t) continue;
                float ax = Math.abs(gx[i]), ay = Math.abs(gy[i]);
                float m1, m2;
                if (ax > 2.414f * ay) { m1 = mag[i - 1]; m2 = mag[i + 1]; }
                else if (ay > 2.414f * ax) { m1 = mag[i - w]; m2 = mag[i + w]; }
                else if (gx[i] * gy[i] > 0) { m1 = mag[i - w - 1]; m2 = mag[i + w + 1]; }
                else { m1 = mag[i - w + 1]; m2 = mag[i + w - 1]; }
                if (m >= m1 && m >= m2) { edge[i] = true; n++; }
            }
        }
        if (ex.length < n) { ex = new float[n]; ey = new float[n]; enx = new float[n]; eny = new float[n]; }
        ne = 0;
        // 연결요소 (BFS) — 엣지 점을 조각 순서로 저장
        int[] queue = new int[n + 1];
        segStart.clear(); segLen.clear();
        int seg = 0;
        for (int i0 = 0; i0 < w * h; i0++) {
            if (!edge[i0] || label[i0] >= 0) continue;
            int qh = 0, qt = 0;
            queue[qt++] = i0; label[i0] = seg;
            int start = ne;
            while (qh < qt) {
                int i = queue[qh++];
                int x = i % w, y = i / w;
                float m = Math.max(mag[i], 1e-6f);
                ex[ne] = x; ey[ne] = y; enx[ne] = gx[i] / m; eny[ne] = gy[i] / m; ne++;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int j = i + dy * w + dx;
                    if (edge[j] && label[j] < 0) {
                        // 기울기 방향이 급변하면(모서리, 직선과 곡선의 만남) 끊음
                        float mj = Math.max(mag[j], 1e-6f);
                        float dot = (gx[i] * gx[j] + gy[i] * gy[j]) / (m * mj);
                        if (dot > 0.82f) { label[j] = seg; queue[qt++] = j; }
                    }
                }
            }
            segStart.add(start); segLen.add(ne - start);
            seg++;
        }
    }

    private final IntList segStart = new IntList(), segLen = new IntList();

    private int[] subsample(int n, int max) {
        if (n <= max) { int[] r = new int[n]; for (int i = 0; i < n; i++) r[i] = i; return r; }
        int[] r = new int[max];
        double step = (double) n / max;
        for (int i = 0; i < max; i++) r[i] = (int) (i * step);
        return r;
    }

    // ------------------------------------------------------------------
    private static final class Cand {
        Ellipse e; double cov; int occ, vis; double adj;
        Cand(Ellipse e, double cov, int occ) { this.e = e; this.cov = cov; this.occ = occ; }
    }

    private Cand globalSearch(int w, int h, int[] scoreIdx) {
        // 긴 조각 상위 40개
        // (검사 범위가 있으면 그 안에 걸친 조각만)
        int nAll = segLen.size(), ns = 0;
        Integer[] order = new Integer[nAll];
        for (int i = 0; i < nAll; i++) if (segInRoi(i)) order[ns++] = i;
        order = Arrays.copyOf(order, ns);
        Arrays.sort(order, (p, q) -> segLen.get(q) - segLen.get(p));
        int top = 0;
        while (top < ns && top < 40 && segLen.get(order[top]) >= 15) top++;
        if (top == 0) return null;

        ArrayList<Cand> cands = new ArrayList<>();
        double[] buf = new double[2 * 600];
        // 단일 조각
        for (int k = 0; k < top; k++) {
            int s = order[k];
            if (segLen.get(s) < 25) continue;
            Cand c = fitSegments(new int[]{s}, buf, w, h, scoreIdx);
            if (c != null) cands.add(c);
        }
        // 2~3 조각 조합 (긴 조각일수록 자주 뽑힘)
        for (int it = 0; it < 260; it++) {
            int k = 2 + rng.nextInt(2);
            int[] ss = new int[k];
            for (int j = 0; j < k; j++) ss[j] = order[(int) (top * Math.pow(rng.nextDouble(), 1.6))];
            Cand c = fitSegments(ss, buf, w, h, scoreIdx);
            if (c != null) cands.add(c);
        }
        // 평행 접선 쌍의 중점 투표로 찾은 중심 → 타원 후보 (조각이 직선과 붙어 있어도 찾음)
        for (Ellipse seed : centerVoteSeeds(w, h)) {
            Cand c = refine(seed, w, h, scoreIdx);
            if (c != null) cands.add(c);
            Cand c2 = fitNear(seed, w, h, scoreIdx);
            if (c2 != null) cands.add(c2);
        }
        if (roiOn) cands.removeIf(c -> !roiOk(c.e));
        if (DEBUG) System.out.println("cands after roi: " + cands.size() + " segs " + ns);
        if (cands.isEmpty()) return null;
        cands.sort((p, q) -> Integer.compare(q.occ, p.occ));
        // 상위 후보는 '주변 잡음 대비 선명도'로 다시 정렬: 키보드·글자처럼 엣지가 빽빽한 곳은
        // 어떤 타원을 그려도 덮임이 높으므로, 타원을 15% 키우고/줄였을 때의 덮임을 빼서 비교
        int K = Math.min(10, cands.size());
        for (int i = 0; i < K; i++) { Cand c = cands.get(i); c.adj = (c.cov - 0.8 * clutterCov(c.e, w, h, scoreIdx)) * c.vis; }
        for (int i = K; i < cands.size(); i++) cands.get(i).adj = -1e9;
        cands.sort((p, q) -> Double.compare(q.adj, p.adj));
        Cand best = cands.get(0);
        // 동심 후보(관 벽 두께의 안/바깥 테두리) 중 가장 바깥 것
        Cand pick = best;
        for (Cand c : cands) {
            if (c.adj < 0.85 * best.adj) break;
            if (Math.hypot(c.e.cx - best.e.cx, c.e.cy - best.e.cy) < 0.08 * best.e.a
                    && Math.abs(c.e.b / c.e.a - best.e.b / best.e.a) < 0.08 && c.e.a > pick.e.a) pick = c;
        }
        return pick;
    }

    // ------------------------------------------------------------------
    // 중심 투표: 타원 위 두 점의 접선이 평행하면 두 점의 중점 = 타원 중심.
    // 같은 중심에 여러 방향(접선 각도)이 모일수록 타원. 직선 띠·평행선은 한 방향에서만 투표하므로 걸러짐.
    private static final int OB = 48;   // 접선 방향 bin (0~π)
    private static final int CELL = 4;  // 투표 격자(px)

    private java.util.List<Ellipse> centerVoteSeeds(int w, int h) {
        java.util.List<Ellipse> out = new ArrayList<>();
        int n = ne, step = Math.max(1, (n + 2499) / 2500);
        // 방향 bin 별 점 목록
        IntList[] byBin = new IntList[OB];
        for (int b = 0; b < OB; b++) byBin[b] = new IntList();
        for (int i = 0; i < n; i += step) {
            double th = Math.atan2(eny[i], enx[i]);   // 법선 방향
            if (th < 0) th += Math.PI;
            if (th >= Math.PI) th -= Math.PI;
            byBin[Math.min(OB - 1, (int) (th / Math.PI * OB))].add(i);
        }
        int gw = w / CELL + 1, gh = h / CELL + 1;
        long[] mask = new long[gw * gh];
        int[] cnt = new int[gw * gh];
        int m = Math.min(w, h);
        double dMin = 2 * minMinorFrac * m, dMin2 = dMin * dMin;
        for (int b = 0; b < OB; b++) {
            IntList L = byBin[b];
            double th = (b + 0.5) * Math.PI / OB;
            double tx = -Math.sin(th), ty = Math.cos(th);   // 접선 방향
            for (int u = 0; u < L.size(); u++) {
                int i = L.get(u);
                for (int v = u + 1; v < L.size(); v++) {
                    int j = L.get(v);
                    double dx = ex[j] - ex[i], dy = ey[j] - ey[i];
                    double d2 = dx * dx + dy * dy;
                    if (d2 < dMin2) continue;
                    // 현이 접선과 거의 평행 = 같은 직선 위 → 제외
                    if (Math.abs(dx * tx + dy * ty) > 0.9 * Math.sqrt(d2)) continue;
                    int cx = (int) ((ex[i] + ex[j]) * 0.5 / CELL), cy = (int) ((ey[i] + ey[j]) * 0.5 / CELL);
                    int k = cy * gw + cx;
                    mask[k] |= 1L << b; cnt[k]++;
                }
            }
        }
        // 3×3 이웃 방향 수
        int[] dirs = new int[gw * gh];
        for (int y = 1; y < gh - 1; y++) for (int x = 1; x < gw - 1; x++) {
            long mk = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) mk |= mask[(y + dy) * gw + x + dx];
            dirs[y * gw + x] = Long.bitCount(mk);
            if (roiOn) {   // 중심이 검사 범위 밖이면 제외
                double ddx = (x + 0.5) * CELL - roiX, ddy = (y + 0.5) * CELL - roiY;
                if (ddx * ddx + ddy * ddy > roiR * roiR) dirs[y * gw + x] = 0;
            }
        }
        // 상위 봉우리 3개 (서로 떨어진)
        int[] peaks = new int[3]; int np = 0;
        boolean[] used = new boolean[gw * gh];
        for (int p = 0; p < 3; p++) {
            int best = -1, bv = 0;
            for (int k = 0; k < dirs.length; k++) if (!used[k] && dirs[k] > bv) { bv = dirs[k]; best = k; }
            if (best < 0 || bv < OB / 4) break;
            peaks[np++] = best;
            int bx = best % gw, by = best / gw, R = Math.max(3, m / (CELL * 8));
            for (int y = Math.max(0, by - R); y <= Math.min(gh - 1, by + R); y++)
                for (int x = Math.max(0, bx - R); x <= Math.min(gw - 1, bx + R); x++) used[y * gw + x] = true;
        }
        // 각 봉우리: 투표한 점 쌍(중심 대칭)에서 RANSAC — 쌍 3개(서로 다른 방향)로 중심 고정 타원 결정,
        // 같은 타원에 맞는 쌍이 많은 방향에 걸쳐 있을수록 좋음
        for (int p = 0; p < np; p++) {
            double pcx = (peaks[p] % gw + 0.5) * CELL, pcy = (peaks[p] / gw + 0.5) * CELL;
            double rad = 1.6 * CELL;
            IntList PI = new IntList(), PJ = new IntList(), PB = new IntList();
            for (int b = 0; b < OB; b++) {
                IntList L = byBin[b];
                double th = (b + 0.5) * Math.PI / OB;
                double tx = -Math.sin(th), ty = Math.cos(th);
                for (int u = 0; u < L.size(); u++) {
                    int i = L.get(u);
                    for (int v = u + 1; v < L.size(); v++) {
                        int j = L.get(v);
                        double mx = (ex[i] + ex[j]) * 0.5 - pcx, my = (ey[i] + ey[j]) * 0.5 - pcy;
                        if (mx * mx + my * my > rad * rad) continue;
                        double dx = ex[j] - ex[i], dy = ey[j] - ey[i];
                        double d2 = dx * dx + dy * dy;
                        if (d2 < dMin2 || Math.abs(dx * tx + dy * ty) > 0.9 * Math.sqrt(d2)) continue;
                        PI.add(i); PJ.add(j); PB.add(b);
                    }
                }
            }
            int np2 = PI.size();
            if (np2 < 12) continue;
            double[] hx = new double[np2], hy = new double[np2];
            for (int k = 0; k < np2; k++) { hx[k] = (ex[PJ.get(k)] - ex[PI.get(k)]) * 0.5; hy[k] = (ey[PJ.get(k)] - ey[PI.get(k)]) * 0.5; }
            double[] bestQ = null; int bestS = 0;
            for (int it = 0; it < 150; it++) {
                int k1 = rng.nextInt(np2), k2 = rng.nextInt(np2), k3 = rng.nextInt(np2);
                int b1 = PB.get(k1), b2 = PB.get(k2), b3 = PB.get(k3);
                if (angDiff(b1, b2) < OB / 8 || angDiff(b1, b3) < OB / 8 || angDiff(b2, b3) < OB / 8) continue;
                double[][] M = {{hx[k1] * hx[k1], hx[k1] * hy[k1], hy[k1] * hy[k1]},
                                {hx[k2] * hx[k2], hx[k2] * hy[k2], hy[k2] * hy[k2]},
                                {hx[k3] * hx[k3], hx[k3] * hy[k3], hy[k3] * hy[k3]}};
                double[][] Mi = inv3(M);
                if (Mi == null) continue;
                double A = Mi[0][0] + Mi[0][1] + Mi[0][2], B = Mi[1][0] + Mi[1][1] + Mi[1][2], C = Mi[2][0] + Mi[2][1] + Mi[2][2];
                if (A <= 0 || 4 * A * C - B * B <= 0) continue;
                // 합의: 서로 다른 방향 bin 수
                long mk = 0;
                for (int k = 0; k < np2; k++) {
                    double q = A * hx[k] * hx[k] + B * hx[k] * hy[k] + C * hy[k] * hy[k];
                    if (Math.abs(q - 1) < 0.08) mk |= 1L << PB.get(k);
                }
                int sc = Long.bitCount(mk);
                if (sc > bestS) { bestS = sc; bestQ = new double[]{A, B, C}; }
            }
            if (bestQ == null || bestS < OB / 4) continue;
            // 합의 쌍의 점으로 일반 타원 피팅
            DoubleList pts = new DoubleList();
            for (int k = 0; k < np2; k++) {
                double q = bestQ[0] * hx[k] * hx[k] + bestQ[1] * hx[k] * hy[k] + bestQ[2] * hy[k] * hy[k];
                if (Math.abs(q - 1) < 0.08) {
                    pts.add(ex[PI.get(k)]); pts.add(ey[PI.get(k)]); pts.add(ex[PJ.get(k)]); pts.add(ey[PJ.get(k)]);
                    if (pts.size() > 1600) break;
                }
            }
            double[] arr = pts.toArray();
            Ellipse e = fitEllipse(arr, arr.length / 2);
            if (e != null && plausible(e, w, h)) out.add(e);
        }
        return out;
    }

    private static int angDiff(int a, int b) { int d = Math.abs(a - b) % OB; return Math.min(d, OB - d); }

    private Cand fitSegments(int[] ss, double[] buf, int w, int h, int[] scoreIdx) {
        int total = 0;
        for (int s : ss) total += segLen.get(s);
        if (total < 20) return null;
        int maxPts = buf.length / 2;
        int stride = Math.max(1, (total + maxPts - 1) / maxPts);
        int n = 0;
        for (int s : ss) {
            int st = segStart.get(s), len = segLen.get(s);
            for (int i = 0; i < len && n < maxPts; i += stride) {
                buf[2 * n] = ex[st + i]; buf[2 * n + 1] = ey[st + i]; n++;
            }
        }
        Ellipse e = fitEllipse(buf, n);
        if (e == null || !plausible(e, w, h)) return null;
        return score(e, w, h, scoreIdx, tolFor(e));
    }

    private Cand fitNear(Ellipse prior, int w, int h, int[] scoreIdx) {
        double tol = Math.max(3.0, 0.06 * prior.a);
        double[] pts = selectInliers(prior, tol, 0.8);
        if (pts == null) return null;
        Ellipse e = fitEllipse(pts, pts.length / 2);
        if (e == null || !plausible(e, w, h)) return null;
        // 한 번 더 좁은 띠로
        double[] pts2 = selectInliers(e, Math.max(2.0, 0.025 * e.a), 0.85);
        if (pts2 != null) {
            Ellipse e2 = fitEllipse(pts2, pts2.length / 2);
            if (e2 != null && plausible(e2, w, h)) e = e2;
        }
        return score(e, w, h, scoreIdx, tolFor(e));
    }

    private Cand refine(Ellipse e0, int w, int h, int[] scoreIdx) {
        Ellipse e = e0;
        for (int it = 0; it < 2; it++) {
            double[] pts = selectInliers(e, tolFor(e) * 1.5, 0.85);
            if (pts == null) return null;
            Ellipse e2 = fitEllipse(pts, pts.length / 2);
            if (e2 == null || !plausible(e2, w, h)) return null;
            e = e2;
        }
        return score(e, w, h, scoreIdx, tolFor(e));
    }

    private double tolFor(Ellipse e) { return Math.max(1.5, 0.012 * e.a); }

    private boolean plausible(Ellipse e, int w, int h) {
        int m = Math.min(w, h);
        if (!(e.a > minSizeFrac * m && e.a < 1.1 * Math.max(w, h))) return false;
        if (e.b / e.a < minRatio) return false;
        if (e.b < minMinorFrac * m) return false;
        return e.cx > 0.05 * w && e.cx < 0.95 * w && e.cy > 0.05 * h && e.cy < 0.95 * h;
    }

    /** 타원 근처 + 기울기 방향이 법선과 일치하는 엣지 점 */
    private double[] selectInliers(Ellipse e, double tol, double cosMin) {
        double[] co = conic(e);
        DoubleList out = new DoubleList();
        for (int i = 0; i < ne; i++) {
            double[] dn = sampson(co, ex[i], ey[i]);
            if (dn[0] < tol && Math.abs(dn[1] * enx[i] + dn[2] * eny[i]) > cosMin) { out.add(ex[i]); out.add(ey[i]); }
        }
        if (out.size() < 40) return null;
        // 최대 800점
        if (out.size() > 1600) {
            double[] all = out.toArray();
            int n = all.length / 2, step = (n + 799) / 800;
            DoubleList s = new DoubleList();
            for (int i = 0; i < n; i += step) { s.add(all[2 * i]); s.add(all[2 * i + 1]); }
            return s.toArray();
        }
        return out.toArray();
    }

    /** 점수: inlier 가 덮는 각도 bin 수 / 화면 안에 있는 bin 수 (+ 4분면 분포 검사) */
    private Cand score(Ellipse e, int w, int h, int[] idx, double tol) {
        int[] qVis = new int[4], qOcc = new int[4];
        int[] ov = occVis(e, w, h, idx, tol, qVis, qOcc);
        int nOcc = ov[0], nVis = ov[1];
        if (nVis < bins / 3) return null;
        // 각도 분포: 직선/평행선 두 줄이 만든 납작한 가짜 타원은 장축 양 끝이 비어 있음
        int bad = 0;
        for (int q = 0; q < 4; q++) if (qVis[q] > 2 && qOcc[q] < quadFrac * qVis[q]) bad++;
        if (bad > 1) return null;
        Cand c = new Cand(e, (double) nOcc / nVis, nOcc);
        c.vis = nVis;
        return c;
    }

    /** {덮인 bin 수, 화면 안 bin 수}; qVis/qOcc 가 주어지면 4분면별 집계 */
    private int[] occVis(Ellipse e, int w, int h, int[] idx, double tol, int[] qVis, int[] qOcc) {
        double[] co = conic(e);
        boolean[] occ = new boolean[bins];
        double c = Math.cos(e.phi), s = Math.sin(e.phi);
        for (int k : idx) {
            double[] dn = sampson(co, ex[k], ey[k]);
            if (dn[0] >= tol || Math.abs(dn[1] * enx[k] + dn[2] * eny[k]) < 0.85) continue;
            double dx = ex[k] - e.cx, dy = ey[k] - e.cy;
            double u = (c * dx + s * dy) / e.a, v = (-s * dx + c * dy) / e.b;
            double t = Math.atan2(v, u);
            int b = (int) ((t + Math.PI) / (2 * Math.PI) * bins);
            occ[Math.min(bins - 1, Math.max(0, b))] = true;
        }
        int nOcc = 0, nVis = 0;
        for (int b = 0; b < bins; b++) {
            double t = -Math.PI + (b + 0.5) * 2 * Math.PI / bins;
            double[] p = e.point(t);
            boolean vis = p[0] >= 2 && p[0] < w - 2 && p[1] >= 2 && p[1] < h - 2;
            int q = ((int) Math.floor((t + Math.PI / 4) / (Math.PI / 2)) % 4 + 4) % 4;   // 0: 장축 끝, 1·3: 단축 끝
            if (vis) { nVis++; if (qVis != null) qVis[q]++; }
            if (occ[b] && vis) { nOcc++; if (qOcc != null) qOcc[q]++; }
        }
        return new int[]{nOcc, nVis};
    }

    /**
     * 관 내경 선택 (관 두께 입력 없이): 끝단 면이 보이면 바깥·안쪽 동심 테두리 두 개 → 안쪽 사용.
     * 타원을 0.72~1.38배로 바꿔가며 둘레 덮임 비율을 보고, 검출 타원 바깥에 테두리가 있으면 검출 타원이 안쪽,
     * 안쪽에 있으면 그것을 (다른 테두리를 넘지 않는 좁은 띠로) 재피팅. 둘 다 없으면 검출 타원 = 내경.
     */
    private void selectInner(Ellipse e0, int w, int h, int[] idx, Result r) {
        r.inner = e0; r.innerSource = 0;
        int n = 67;                     // 0.72 ~ 1.38, 0.01 간격
        double[] sc = new double[n], prof = new double[n], sh = new double[n];
        double tol = Math.max(1.2, 0.01 * e0.a);
        double step = 1.5 * tol;
        // 투시 때문에 동심원의 상(像)은 중심이 단축 방향으로 조금 어긋남 → 단축 방향 이동도 탐색
        double ux = -Math.sin(e0.phi), uy = Math.cos(e0.phi);
        int[] sub = idx;
        for (int i = 0; i < n; i++) {
            sc[i] = 0.72 + 0.01 * i;
            double maxShift = 0.3 * Math.abs(1 - sc[i]) * e0.a;
            int ns = (int) Math.floor(maxShift / step);
            double bestC = 0, bestS = 0;
            for (int k = -ns; k <= ns; k++) {
                double d = step * k;
                Ellipse q = new Ellipse(e0.cx + ux * d, e0.cy + uy * d, e0.a * sc[i], e0.b * sc[i], e0.phi);
                int[] ov = occVis(q, w, h, sub, tol, null, null);
                double c = ov[1] >= bins / 3 ? (double) ov[0] / ov[1] : 0;
                if (c > bestC) { bestC = c; bestS = d; }
            }
            prof[i] = bestC; sh[i] = bestS;
        }
        if (DEBUG) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i += 2) b.append(String.format("%.2f:%.2f/%.0f ", sc[i], prof[i], sh[i])); System.out.println(b); }
        double minCov = 0.5;
        int i0 = -1;
        for (int i = 1; i < n - 1; i++)
            if (Math.abs(sc[i] - 1) <= 0.025 && isPeak(prof, i, minCov) && (i0 < 0 || prof[i] > prof[i0])) i0 = i;
        if (i0 < 0) i0 = 28;            // 1.00
        // 바깥(up)·안쪽(dn) 후보 중 덮임이 가장 큰 봉우리
        int up = -1, dn = -1;
        for (int i = 1; i < n - 1; i++) {
            if (!isPeak(prof, i, minCov)) continue;
            if (sc[i] >= sc[i0] + 0.03 && (up < 0 || prof[i] > prof[up])) up = i;
            if (sc[i] <= sc[i0] - 0.03 && (dn < 0 || prof[i] > prof[dn] || (prof[i] == prof[dn] && sc[i] > sc[dn]))) dn = i;
        }
        // 관 벽은 보통 얇음 → 안쪽 후보가 충분하면 우선. 바깥 후보는 확실할 때만(주변 잡음이 바깥에 봉우리를 만들기 쉬움)
        int pick; double gapF;
        if (dn >= 0 && prof[dn] >= 0.55 && (up < 0 || prof[dn] >= prof[up] - 0.05)) {
            pick = dn; gapF = sc[i0] - sc[dn]; r.innerSource = 2;
        } else if (up >= 0 && prof[up] >= 0.7) {
            pick = i0; gapF = sc[up] - sc[i0]; r.innerSource = 1;
        } else return;
        Ellipse e = new Ellipse(e0.cx + ux * sh[pick], e0.cy + uy * sh[pick], e0.a * sc[pick], e0.b * sc[pick], e0.phi);
        double gap = gapF * e0.a;
        double[] tols = {Math.min(0.03 * e.a, 0.4 * gap), Math.min(0.015 * e.a, 0.3 * gap)};
        for (double t : tols) {
            double[] pts = selectInliers(e, Math.max(t, 0.8), 0.85);
            if (pts == null) break;
            Ellipse e2 = fitEllipse(pts, pts.length / 2);
            if (e2 == null || !plausible(e2, w, h)) break;
            e = e2;
        }
        r.inner = e;
    }

    private static int[] subsampleOf(int[] idx, int m) {
        int[] r = new int[m];
        double st = (double) idx.length / m;
        for (int i = 0; i < m; i++) r[i] = idx[(int) (i * st)];
        return r;
    }

    private static boolean isPeak(double[] p, int i, double min) {
        return p[i] >= min && p[i] >= p[i - 1] && p[i] >= p[i + 1];
    }

    /** 타원을 0.85배·1.15배 했을 때의 평균 덮임 비율 (주변 잡음 수준) */
    private double clutterCov(Ellipse e, int w, int h, int[] idx) {
        double sum = 0;
        for (double f : new double[]{0.85, 1.15}) {
            int[] ov = occVis(new Ellipse(e.cx, e.cy, e.a * f, e.b * f, e.phi), w, h, idx, tolFor(e), null, null);
            sum += ov[1] > 0 ? (double) ov[0] / ov[1] : 0;
        }
        return sum / 2;
    }

    private void interiorStats(Ellipse e, int w, int h, Result r) {
        double sum = 0, sg = 0; int n = 0;
        int x0 = (int) Math.max(1, e.cx - e.a), x1 = (int) Math.min(w - 2, e.cx + e.a);
        int y0 = (int) Math.max(1, e.cy - e.a), y1 = (int) Math.min(h - 2, e.cy + e.a);
        double c = Math.cos(e.phi), s = Math.sin(e.phi);
        for (int y = y0; y <= y1; y += 2) for (int x = x0; x <= x1; x += 2) {
            double dx = x - e.cx, dy = y - e.cy;
            double u = (c * dx + s * dy) / (0.85 * e.a), v = (-s * dx + c * dy) / (0.85 * e.b);
            if (u * u + v * v > 1) continue;
            sum += blur[y * w + x]; sg += mag[y * w + x]; n++;
        }
        if (n > 0) { r.brightness = sum / n; r.sharpness = sg / n; }
    }

    // ------------------------------------------------------------------
    // 기하: conic, Sampson 거리, Fitzgibbon 타원 피팅 (Halir & Flusser)
    // ------------------------------------------------------------------
    /** [A,B,C,D,E,F]: Ax²+Bxy+Cy²+Dx+Ey+F=0 (x²/a²+y²/b²-1 정규화) */
    static double[] conic(Ellipse e) {
        double c = Math.cos(e.phi), s = Math.sin(e.phi);
        double ia = 1 / (e.a * e.a), ib = 1 / (e.b * e.b);
        double A = c * c * ia + s * s * ib, B = 2 * c * s * (ia - ib), C = s * s * ia + c * c * ib;
        double D = -2 * A * e.cx - B * e.cy, E = -B * e.cx - 2 * C * e.cy;
        double F = A * e.cx * e.cx + B * e.cx * e.cy + C * e.cy * e.cy - 1;
        return new double[]{A, B, C, D, E, F};
    }

    /** {거리, 법선x, 법선y} */
    static double[] sampson(double[] q, double x, double y) {
        double f = q[0] * x * x + q[1] * x * y + q[2] * y * y + q[3] * x + q[4] * y + q[5];
        double fx = 2 * q[0] * x + q[1] * y + q[3], fy = q[1] * x + 2 * q[2] * y + q[4];
        double g = Math.max(Math.sqrt(fx * fx + fy * fy), 1e-12);
        return new double[]{Math.abs(f) / g, fx / g, fy / g};
    }

    /** pts = [x0,y0,x1,y1,...], n 점 */
    static Ellipse fitEllipse(double[] pts, int n) {
        if (n < 6) return null;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += pts[2 * i]; my += pts[2 * i + 1]; }
        mx /= n; my /= n;
        double sc = 0;
        for (int i = 0; i < n; i++) sc += Math.abs(pts[2 * i] - mx) + Math.abs(pts[2 * i + 1] - my);
        sc = Math.max(sc / (2 * n), 1e-9);
        double[][] S1 = new double[3][3], S2 = new double[3][3], S3 = new double[3][3];
        for (int i = 0; i < n; i++) {
            double x = (pts[2 * i] - mx) / sc, y = (pts[2 * i + 1] - my) / sc;
            double[] d1 = {x * x, x * y, y * y}, d2 = {x, y, 1};
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
                S1[r][c] += d1[r] * d1[c]; S2[r][c] += d1[r] * d2[c]; S3[r][c] += d2[r] * d2[c];
            }
        }
        double[][] S3i = inv3(S3);
        if (S3i == null) return null;
        double[][] T = new double[3][3];      // T = -S3⁻¹ S2ᵀ
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            double v = 0;
            for (int k = 0; k < 3; k++) v += S3i[r][k] * S2[c][k];
            T[r][c] = -v;
        }
        double[][] M = new double[3][3];      // S1 + S2 T
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            double v = S1[r][c];
            for (int k = 0; k < 3; k++) v += S2[r][k] * T[k][c];
            M[r][c] = v;
        }
        double[][] Mp = new double[3][3];     // C1⁻¹ M
        for (int c = 0; c < 3; c++) { Mp[0][c] = M[2][c] / 2; Mp[1][c] = -M[1][c]; Mp[2][c] = M[0][c] / 2; }
        double[] a1 = null;
        for (double lam : realEigenvalues(Mp)) {
            double[] v = nullVector(Mp, lam);
            if (v == null) continue;
            if (4 * v[0] * v[2] - v[1] * v[1] > 0) { a1 = v; break; }
        }
        if (a1 == null) return null;
        double A = a1[0], B = a1[1], C = a1[2];
        double D = T[0][0] * A + T[0][1] * B + T[0][2] * C;
        double E = T[1][0] * A + T[1][1] * B + T[1][2] * C;
        double F = T[2][0] * A + T[2][1] * B + T[2][2] * C;
        // 정규화 해제
        double A2 = A / (sc * sc), B2 = B / (sc * sc), C2 = C / (sc * sc);
        double D2 = D / sc - 2 * A2 * mx - B2 * my;
        double E2 = E / sc - 2 * C2 * my - B2 * mx;
        double F2 = A2 * mx * mx + B2 * mx * my + C2 * my * my - D / sc * mx - E / sc * my + F;
        return conicToEllipse(A2, B2, C2, D2, E2, F2);
    }

    static Ellipse conicToEllipse(double A, double B, double C, double D, double E, double F) {
        double den = B * B - 4 * A * C;
        if (den >= 0) return null;
        double cx = (2 * C * D - B * E) / den, cy = (2 * A * E - B * D) / den;
        double F0 = A * cx * cx + B * cx * cy + C * cy * cy + D * cx + E * cy + F;
        if (F0 == 0) return null;
        double a11 = A / -F0, a12 = B / 2 / -F0, a22 = C / -F0;
        double tr = a11 + a22, dif = Math.sqrt((a11 - a22) * (a11 - a22) + 4 * a12 * a12);
        double l1 = (tr - dif) / 2, l2 = (tr + dif) / 2;   // l1 ≤ l2
        if (l1 <= 0 || l2 <= 0) return null;
        double a = 1 / Math.sqrt(l1), b = 1 / Math.sqrt(l2);
        // l1 의 고유벡터 = 장축 방향
        double vx, vy;
        if (Math.abs(a12) > 1e-15) { vx = l1 - a22; vy = a12; }
        else if (a11 <= a22) { vx = 1; vy = 0; } else { vx = 0; vy = 1; }
        double phi = Math.atan2(vy, vx);
        if (phi > Math.PI / 2) phi -= Math.PI;
        if (phi <= -Math.PI / 2) phi += Math.PI;
        if (!(a > 0 && b > 0) || Double.isNaN(a) || Double.isNaN(b)) return null;
        return new Ellipse(cx, cy, a, b, phi);
    }

    static double[][] inv3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-18) return null;
        double[][] r = new double[3][3];
        r[0][0] = A / det; r[0][1] = -(b * i - c * h) / det; r[0][2] = (b * f - c * e) / det;
        r[1][0] = B / det; r[1][1] = (a * i - c * g) / det; r[1][2] = -(a * f - c * d) / det;
        r[2][0] = C / det; r[2][1] = -(a * h - b * g) / det; r[2][2] = (a * e - b * d) / det;
        return r;
    }

    /** 3×3 실수 고유값 (특성다항식 3차 방정식) */
    static double[] realEigenvalues(double[][] m) {
        double tr = m[0][0] + m[1][1] + m[2][2];
        double c1 = m[0][0] * m[1][1] - m[0][1] * m[1][0] + m[0][0] * m[2][2] - m[0][2] * m[2][0]
                + m[1][1] * m[2][2] - m[1][2] * m[2][1];
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        // λ³ - tr λ² + c1 λ - det = 0  →  λ = t + tr/3
        double a = -tr, b = c1, c = -det;
        double p = b - a * a / 3, q = 2 * a * a * a / 27 - a * b / 3 + c;
        double sh = -a / 3;
        double disc = q * q / 4 + p * p * p / 27;
        if (disc > 0) {
            double sq = Math.sqrt(disc);
            return new double[]{Math.cbrt(-q / 2 + sq) + Math.cbrt(-q / 2 - sq) + sh};
        }
        double r = Math.sqrt(Math.max(-p / 3, 0));
        if (r < 1e-300) return new double[]{sh};
        double phi = Math.acos(Math.max(-1, Math.min(1, -q / (2 * r * r * r))));
        return new double[]{2 * r * Math.cos(phi / 3) + sh, 2 * r * Math.cos((phi + 2 * Math.PI) / 3) + sh,
                2 * r * Math.cos((phi + 4 * Math.PI) / 3) + sh};
    }

    /** (m - λI) 의 영공간 벡터 = 두 행의 외적 중 가장 큰 것 */
    static double[] nullVector(double[][] m, double lam) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = m[i][j] - (i == j ? lam : 0);
        double[] best = null; double bn = 0;
        int[][] pairs = {{0, 1}, {0, 2}, {1, 2}};
        for (int[] pr : pairs) {
            double[] u = r[pr[0]], v = r[pr[1]];
            double[] x = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
            double nn = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
            if (nn > bn) { bn = nn; best = x; }
        }
        if (best == null || bn < 1e-300) return null;
        return new double[]{best[0] / bn, best[1] / bn, best[2] / bn};
    }

    // ------------------------------------------------------------------
    static final class IntList {
        int[] a = new int[64]; int n;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        int get(int i) { return a[i]; }
        int size() { return n; }
        void clear() { n = 0; }
    }

    static final class DoubleList {
        double[] a = new double[256]; int n;
        void add(double v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        int size() { return n; }
        double[] toArray() { return Arrays.copyOf(a, n); }
    }
}
