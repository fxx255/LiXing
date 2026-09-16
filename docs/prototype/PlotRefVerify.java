import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 对**复刻版自身**做像素级几何核验：直接扫位图找轴线/曲线的真实落点，
 * 再与「按常量手算的期望值」对比。这是唯一能证明「图上真的画对了」的手段 ——
 * 常量对不对是纸面推演，位图量出来才算数。
 *
 * 检测方法（深色背景，几种颜色的亮度区间互不重叠）：
 *   · 轴线 0xA8A8B0 亮度高 → 找「非背景且近中性灰且够亮」的像素；
 *   · 曲线 0x5AA9FF 蓝色 → R 明显小于 B。
 */
public class PlotRefVerify {

    static BufferedImage img;

    public static void main(String[] args) throws Exception {
        img = ImageIO.read(new File(args.length > 0 ? args[0] : "plot-ref-final.png"));
        int W = img.getWidth(), H = img.getHeight();
        System.out.printf("canvas %dx%d  aspect %.3f%n%n", W, H, H / (float) W);

        // ---- 期望值（与 PlotRefFinal 的布局公式一致）----
        float padLeft = W * 0.082f, padRight = W * 0.082f;
        float padTop = H * 0.085f, padBottom = H * 0.125f;
        float plotX = padLeft, plotY = padTop;
        float plotW = W - padLeft - padRight, plotH = H - padTop - padBottom;
        float insetX = plotW * 0.2368f, insetY = plotH * 0.03f;
        float drawX = plotX + insetX, drawY = plotY + insetY;
        float drawW = plotW - insetX * 2f, drawH = plotH - insetY * 2f;
        float axisY = drawY + drawH;
        float axisLeft = plotX - W * 0.012f, axisRight = plotX + plotW + W * 0.030f;

        System.out.println("== 期望（按常量手算）==");
        System.out.printf("绘图区 内缩矩形  x=[%.1f, %.1f]  y=[%.1f, %.1f]%n", drawX, drawX + drawW, drawY, drawY + drawH);
        System.out.printf("  data width  = %.2f%% of canvas    (目标 44.0%%)%n", drawW / W * 100);
        System.out.printf("  left gap    = %.2f%%             (目标 28.0%%)%n", drawX / W * 100);
        System.out.printf("  right gap   = %.2f%%             (目标 28.0%%)%n", (W - drawX - drawW) / W * 100);
        System.out.printf("  asym        = %.2f px%n", Math.abs(drawX - (W - drawX - drawW)));
        System.out.printf("x 轴 y      = %.1f  (%.2f%% of height, 目标 78.4%%)%n", axisY, axisY / H * 100);
        System.out.printf("x 轴 跨度   x=[%.1f, %.1f]  伸出 左 %.1f / 右 %.1f px%n", axisLeft, axisRight, plotX - axisLeft, axisRight - (plotX + plotW));
        System.out.println();

        // ---- 实测 ----
        int axY = findAxisYRuns();
        System.out.println("== 实测（扫位图）==");
        System.out.printf("x 轴横线 y  = %d      (期望 %.1f, 差 %.1f px)%n", axY, axisY, Math.abs(axY - axisY));

        int[] axSpan = findAxisSpan(axY);
        System.out.printf("x 轴跨度   x=[%d, %d]  (期望 [%.1f, %.1f], 差左 %.1f / 差右 %.1f)%n",
                axSpan[0], axSpan[1], axisLeft, axisRight,
                Math.abs(axSpan[0] - axisLeft), Math.abs(axSpan[1] - axisRight));
        System.out.printf("           伸出 左 %.0f / 右 %.0f px%n", plotX - axSpan[0], axSpan[1] - (plotX + plotW));

        int vertX = findVerticalAxis();
        System.out.printf("O 竖线 x    = %d      (期望 %.1f = 画布中心, 差 %.1f px)%n", vertX, W / 2f, Math.abs(vertX - W / 2f));

        int[] vTop = findVertExtent(vertX);
        float expTop = drawY - plotH * 0.10f;
        System.out.printf("O 竖线范围 y=[%d, %d]  (期望顶端 %.1f = %.1f%% of height, 目标 10.9%%)%n",
                vTop[0], vTop[1], expTop, vTop[0] / (float) H * 100);
        System.out.println();

        // ---- 曲线关键点 ----
        int[] blue = findBlueExtent();
        System.out.printf("曲线 x 范围 = [%d, %d]   \u5bbd\u5ea6 %.0f px = %.2f%% of canvas  (\u671f\u671b %.1f, \u76ee\u6807 44.0%%)%n",
                blue[0], blue[1], (float) (blue[1] - blue[0]), (blue[1] - blue[0]) / (float) W * 100, drawW);
        int apexY = findCurveApexY();
        System.out.printf("曲线最低点 y = %d      = %.1f%% of height%n", apexY, apexY / (float) H * 100);
        int endY = findCurveTopY();
        System.out.printf("曲线两端 y   = %d      = %.1f%% of height  (目标 22.0%%)%n", endY, endY / (float) H * 100);
        System.out.println();

        // ---- 一致性判定 ----
        boolean ok = true;
        ok &= Math.abs(axY - axisY) <= 2f;
        ok &= Math.abs(vertX - W / 2f) <= 2f;
        ok &= Math.abs((blue[1] - blue[0]) / (float) W - 0.44f) <= 0.012f;
        System.out.println(ok ? "GEOMETRY OK — 全部关键几何量落在容差内" : "GEOMETRY MISMATCH — 有量超出容差，见上");
        if (!ok) System.exit(1);
    }

    static boolean isBg(int rgb) {
        int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return Math.abs(r - 0x1C) < 12 && Math.abs(gg - 0x1C) < 12 && Math.abs(b - 0x1E) < 12;
    }

    /** 轴线的判定：够亮 + 近中性灰（曲线的蓝 R<B，会被排除）。 */
    static boolean isAxisLike(int rgb) {
        if (isBg(rgb)) return false;
        int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int lum = (r + gg + b) / 3;
        return lum >= 0x88 && Math.abs(r - b) <= 14 && Math.abs(r - gg) <= 14;
    }

    static boolean isBlue(int rgb) {
        int r = (rgb >> 16) & 0xFF, b = rgb & 0xFF;
        return b - r >= 60 && b >= 0x90;
    }

    /** 找最长的水平轴线所在的行（轴是最长的一条水平亮线）。 */
    static int findAxisYRuns() {
        int w = img.getWidth(), h = img.getHeight();
        int bestY = -1, bestLen = 0;
        for (int y = 0; y < h; y++) {
            int run = 0;
            for (int x = 0; x < w; x++) if (isAxisLike(img.getRGB(x, y))) run++;
            if (run > bestLen) { bestLen = run; bestY = y; }
        }
        return bestY;
    }

    static int[] findAxisSpan(int y) {
        int w = img.getWidth();
        int lo = -1, hi = -1;
        for (int x = 0; x < w; x++) if (isAxisLike(img.getRGB(x, y))) { if (lo < 0) lo = x; hi = x; }
        return new int[]{lo, hi};
    }

    /** 找 O 竖线：在绘图区中部搜索连续竖直的轴线像素最多的一列。 */
    static int findVerticalAxis() {
        int w = img.getWidth(), h = img.getHeight();
        int loX = (int) (w * 0.35f), hiX = (int) (w * 0.65f);
        int bestX = -1, bestLen = 0;
        for (int x = loX; x < hiX; x++) {
            int len = 0;
            for (int y = 0; y < h; y++) if (isAxisLike(img.getRGB(x, y))) len++;
            // 排除 x 轴那一行带来的干扰：整列计数本来就远大于 1
            if (len > bestLen) { bestLen = len; bestX = x; }
        }
        return bestX;
    }

    static int[] findVertExtent(int x) {
        int h = img.getHeight();
        int lo = -1, hi = -1;
        for (int y = 0; y < h; y++) {
            if (isAxisLike(img.getRGB(x, y)) || isAxisLike(img.getRGB(x - 1, y)) || isAxisLike(img.getRGB(x + 1, y))) {
                if (lo < 0) lo = y;
                hi = y;
            }
        }
        return new int[]{lo, hi};
    }

    static int[] findBlueExtent() {
        int w = img.getWidth(), h = img.getHeight();
        int lo = w, hi = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (isBlue(img.getRGB(x, y))) { if (x < lo) lo = x; if (x > hi) hi = x; }
        return new int[]{lo, hi};
    }

    static int findCurveApexY() {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = h - 1; y >= 0; y--) {
            for (int x = (int) (w * 0.30f); x < (int) (w * 0.70f); x++)
                if (isBlue(img.getRGB(x, y))) return y;
        }
        return -1;
    }

    static int findCurveTopY() {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (isBlue(img.getRGB(x, y))) return y;
        return -1;
    }
}
