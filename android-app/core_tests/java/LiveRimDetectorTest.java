import io.github.ercadion.pipeflow.LiveRimDetector;
import io.github.ercadion.pipeflow.LiveRimDetector.Ellipse;
import io.github.ercadion.pipeflow.LiveRimDetector.Result;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 데스크톱 시험: java LiveRimDetectorTest out_dir img1.jpg img2.jpg ... */
public class LiveRimDetectorTest {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]); outDir.mkdirs();
        LiveRimDetector det = new LiveRimDetector(); det.edgeFrac = Double.parseDouble(System.getProperty("ef", "0.20"));
        for (int k = 1; k < args.length; k++) {
            BufferedImage src = ImageIO.read(new File(args[k]));
            int W = src.getWidth(), H = src.getHeight();
            double s = 320.0 / Math.max(W, H);
            int w = (int) Math.round(W * s), h = (int) Math.round(H * s);
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g0 = small.createGraphics();
            g0.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g0.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g0.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
            g0.dispose();
            byte[] gray = new byte[w * h];
            small.getRaster().getDataElements(0, 0, w, h, gray);

            // 워밍업 + 전역 탐색 시간
            Result r = null;
            for (int i = 0; i < 3; i++) r = det.detect(gray, w, h, null);
            Result rt = det.detect(gray, w, h, r.found ? r.ellipse : null);   // 추적 모드
            String name = new File(args[k]).getName();
            if (!r.found) {
                System.out.printf("%s: NOT FOUND (edges=%d, cov=%.2f dist=%.2f) %.1fms%n", name, r.edgePoints, r.coverage, r.distinct, r.millis);
            } else {
                Ellipse e = r.ellipse.scaled(1 / s);
                System.out.printf("%s: cx=%.1f cy=%.1f a=%.1f b=%.1f phi=%.1f ratio=%.3f cov=%.2f dist=%.2f bright=%.0f sharp=%.1f | global %.1fms, tracked %.1fms (cov %.2f, tracked=%b)%n",
                        name, e.cx, e.cy, e.a, e.b, Math.toDegrees(e.phi), e.b / e.a, r.coverage, r.distinct, r.brightness, r.sharpness,
                        r.millis, rt.millis, rt.coverage, rt.tracked);
                Ellipse in = r.inner.scaled(1 / s);
                System.out.printf("    inner(src=%d): cx=%.1f cy=%.1f a=%.1f b=%.1f phi=%.1f  (inner/outer a = %.3f)%n",
                        r.innerSource, in.cx, in.cy, in.a, in.b, Math.toDegrees(in.phi), in.a / e.a);
            }
            // 오버레이
            BufferedImage vis = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = vis.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.setStroke(new BasicStroke(Math.max(3, W / 250f)));
            if (r.found) {
                Ellipse e = r.ellipse.scaled(1 / s);
                g.setColor(Color.GREEN); drawEll(g, e);
                g.setColor(Color.RED); drawEll(g, r.inner.scaled(1 / s));
            }
            g.dispose();
            BufferedImage out = new BufferedImage(W / 3, H / 3, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = out.createGraphics();
            g2.drawImage(vis.getScaledInstance(W / 3, H / 3, Image.SCALE_SMOOTH), 0, 0, null);
            g2.dispose();
            ImageIO.write(out, "jpg", new File(outDir, name.replaceAll("\\.\\w+$", "") + "_live.jpg"));
        }
    }

    static void drawEll(Graphics2D g, Ellipse e) {
        int n = 120; int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) { double[] p = e.point(2 * Math.PI * i / n); xs[i] = (int) p[0]; ys[i] = (int) p[1]; }
        g.drawPolygon(xs, ys, n);
    }
}
