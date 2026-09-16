import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 方案 A 的效果验证：**纵轴留白由模型给的 y.min/max 决定，渲染器原样尊重**。
 *
 * 用户问题（2026-09-16）：「我在 APP 里搜这道题的时候，接入 API 能不能把图画到一个合适的样子？」
 * 结论：能 / 不能，取决于模型有没有给 y.min/max。渲染器侧的行为是——
 *   · 模型给了 y.min/max ⇒ balancedRange 原样使用（只扩不裁）⇒ 曲线按模型意图留白；
 *   · 模型没给 ⇒ 按数据实际跨度自动求范围、只留 6% 边距 ⇒ 曲线铺满，必然成为深碗。
 * 所以修复落在**提示词**（要求模型按「y 范围 ≈ 数据跨度 × 3」给范围），渲染器不动。
 *
 * 本文件把「同一份数据 + 三种 y 范围」并列出来，直观证明留白比例完全由 y 范围决定，
 * 也用来核对 ×3 规则是否真的落在参考图的观感上（曲线占画布高约 32%）。
 *
 * 数据（教材题 3.3(c) 的功率谱形状）：
 *   y = 4π²N₀(f² + f_c²)，f ∈ [−B/2, B/2]，令 B/2 = 2、4π²N₀ ≡ 1 ⇒ y = f² + 0.25
 *   ⇒ y 跨度 = 0.25 ~ 4.25
 */
public class PlotRefApiRender {

    // 与生产一致的常量（此方案不改渲染器，全部沿用当前值）
    static final float LEFT_PAD = 0.082f, RIGHT_PAD = 0.082f;
    static final float TITLE_BAND = 0.085f, BOTTOM_PAD = 0.125f;
    static final float INSET_X = 0.2368f, INSET_Y = 0.03f;
    static final float AXIS_STROKE_DP = 1.5f, MARK_LINE_STROKE_DP = 1.2f, DENSITY = 2.75f;

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0xA8A8B0);
    static final Color GRID = new Color(0x2B2B2F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);

    public static void main(String[] args) throws Exception {
        int cellW = 700;
        int cellH = Math.round(cellW * 0.66f);
        int cols = 2, rows = 2, gap = 18, header = 40, label = 32;
        int W = cols * cellW + (cols + 1) * gap;
        int H = header + rows * (cellH + label) + (rows + 1) * gap;

        BufferedImage sheet = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        PlotRefFinal.aa(g);
        g.setColor(new Color(0x0E0E10));
        g.fillRect(0, 0, W, H);
        g.setFont(new Font("SansSerif", Font.BOLD, 21));
        g.setColor(new Color(0xE8E8EE));
        g.drawString("PLAN A — same data, y-range as given by the model (renderer honours it as-is)", 14, 28);

        // y 数据跨度 = 0.25 .. 4.25
        double yMin = 0.25, yPeak = 4.25;
        double span = yPeak - yMin;

        // 四种 y 范围：不给（自动 6%）/ ×1 / ×2 / ×3
        double[][] ranges = {
            {yMin - span * 0.06, yPeak + span * 0.06},   // 模型没给 ⇒ balancedRange 自动
            {0, yPeak},                                   // 模型给「刚好包住」
            {0, yPeak * 2},                               // ×2
            {0, yPeak * 3},                               // ×3（提示词推荐）
        };
        String[] tags = {
            "model omitted y  -> auto +6%  (curve = 85% of axis)",
            "y = [0, peak]     (curve = 100% - worst)",
            "y = [0, 2x peak]  (curve = 50%)",
            "y = [0, 3x peak]  (curve = 33% = RECOMMENDED)",
        };

        for (int i = 0; i < ranges.length; i++) {
            int cx = gap + (i % cols) * (cellW + gap);
            int cy = header + gap + (i / cols) * (cellH + label + gap);

            BufferedImage cell = new BufferedImage(cellW, cellH, BufferedImage.TYPE_INT_RGB);
            Graphics2D cg = cell.createGraphics();
            PlotRefFinal.aa(cg);
            float ratio = draw(cg, cellW, cellH, ranges[i][0], ranges[i][1], yMin, yPeak);
            cg.dispose();

            g.drawImage(cell, cx, cy, null);
            g.setColor(i == 0 ? new Color(0xFF6B6B)
                    : (i == ranges.length - 1 ? new Color(0x3DDC97) : new Color(0x5AA9FF)));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(cx, cy, cellW - 1, cellH - 1);

            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.setColor(new Color(0xD8D8DE));
            g.drawString(String.format("curve = %.0f%% of axis height   |   %s", ratio * 100, tags[i]),
                    cx + 4, cy + cellH + 21);
        }

        g.dispose();
        ImageIO.write(sheet, "png", new File(args.length > 0 ? args[0] : "plot-ref-api.png"));
        System.out.println("written plot-ref-api.png  " + W + "x" + H);
    }

    /** 返回「曲线高度 / 绘图区可用高度」的比值。 */
    static float draw(Graphics2D g, int W, int H, double yLo, double yHi, double yMin, double yPeak) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        double xLo = -2.0, xHi = 2.0, spanX = xHi - xLo;
        double spanY = yHi - yLo;

        float titleSize = W * 0.031f, tickSize = W * 0.024f;
        float padLeft = W * LEFT_PAD, padRight = W * RIGHT_PAD;
        float padTop = H * TITLE_BAND, padBottom = H * BOTTOM_PAD;
        float plotW = W - padLeft - padRight, plotH = H - padTop - padBottom;
        float insetX = plotW * INSET_X, insetY = plotH * INSET_Y;
        float drawX = padLeft + insetX, drawY = padTop + insetY;
        float drawW = plotW - insetX * 2f, drawH = plotH - insetY * 2f;

        final float fX = drawX, fY = drawY, fW = drawW, fH = drawH;
        final double lo = yLo, hi = yHi, sp = spanY;

        // 只在「放得下」时画网格（y 范围太宽时刻度会糊成一片 —— 这正是提示词里
        // 建议「留白时别标纵轴刻度」的原因）
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = fX + (float) ((t - xLo) / spanX * fW);
            g.drawLine(Math.round(px), Math.round(fY), Math.round(px), Math.round(fY + fH));
        }

        // 曲线
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(2.2f * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Float();
        int N = 900;
        float apexY = 0, endY = 0;
        for (int i = 0; i <= N; i++) {
            double x = xLo + (xHi - xLo) * i / N;
            double y = x * x + 0.25;              // f² + f_c²，f_c² = 0.25
            float px = fX + (float) ((x - xLo) / spanX * fW);
            float py = fY + fH - (float) ((y - lo) / sp * fH);
            if (i == 0) { path.moveTo(px, py); endY = py; }
            else path.lineTo(px, py);
            if (i == N / 2) apexY = py;
        }
        g.draw(path);

        // 坐标轴
        float axisY = fY + fH;
        float axisLeft = padLeft - W * 0.012f;
        float axisRight = padLeft + plotW + W * 0.030f;
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(AXIS_STROKE_DP * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(Math.round(axisLeft), Math.round(axisY), Math.round(axisRight), Math.round(axisY));
        float yAxisX = fX + fW / 2f;
        float yAxisTop = fY - plotH * 0.10f;
        g.drawLine(Math.round(yAxisX), Math.round(yAxisTop), Math.round(yAxisX), Math.round(axisY + H * 0.012f));
        g.drawLine(Math.round(axisRight), Math.round(axisY), Math.round(axisRight), Math.round(axisY - H * 0.014f));
        g.drawLine(Math.round(yAxisX), Math.round(yAxisTop), Math.round(yAxisX + W * 0.010f), Math.round(yAxisTop));

        g.setFont(new Font("Serif", Font.ITALIC, Math.round(tickSize * 1.02f)));
        g.setColor(TEXT);
        g.drawString("f", Math.round(axisRight + W * 0.006f), Math.round(axisY + H * 0.030f));

        // markLine ±B/2
        g.setColor(MARK);
        g.setStroke(new BasicStroke(MARK_LINE_STROKE_DP * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double t : new double[]{-2, 2}) {
            float px = fX + (float) ((t - xLo) / spanX * fW);
            double yv = t * t + 0.25;
            float top = fY + fH - (float) ((yv - lo) / sp * fH);
            g.drawLine(Math.round(px), Math.round(top), Math.round(px), Math.round(axisY));
        }

        // x 刻度
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(tickSize)));
        g.setColor(SUBTEXT);
        FontMetrics fm = g.getFontMetrics();
        String[] lb = {"\u2212B/2", "O", "B/2"};
        double[] pos = {-2, 0, 2};
        float baseline = Math.min(axisY + H * 0.038f, H - H * 0.012f);
        for (int i = 0; i < 3; i++) {
            float cx = fX + (float) ((pos[i] - xLo) / spanX * fW);
            g.drawString(lb[i], Math.round(cx - fm.stringWidth(lb[i]) / 2f), Math.round(baseline));
        }

        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(titleSize)));
        g.setColor(TEXT);
        String title = "S_Yc(f) = 4\u03c0\u00b2N\u2080(f\u00b2+f_c\u00b2)";
        fm = g.getFontMetrics();
        g.drawString(title, Math.round(W / 2f - fm.stringWidth(title) / 2f), Math.round(H * 0.052f));

        return Math.abs(apexY - endY) / drawH;
    }
}
