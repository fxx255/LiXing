import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 纵向「碗深」四档对比：曲线该有多扁。
 *
 * 背景：横向比例对齐参考图后，纵向暴露出一个新问题 —— 曲线画成了**深碗**，
 * 与真实曲率不符。根因是 `PLOT_INSET_Y_RATIO` 沿用了横向内缩的思路，
 * 但纵向的目标完全不同：
 *   · 横向内缩 23.68% 是**主动留白**（参考图里数据本来就只占 44% 宽）；
 *   · 纵向内缩 3% 只是「防贴边」，结果曲线几乎填满整个绘图区高度
 *     ⇒ 真实数据跨度 0.65 被纵向拉伸到 581px，是参考图（295px）的近 2 倍。
 *
 * 本文件对 insetY 取四档出图，直接用眼睛比「哪一档最像参考图」。
 * 每档都标注两个可核对的量：曲线高度占画布比、x 轴距画布底比。
 *
 * 参考图（教材频谱插图 1017x624）实测：曲线占画布高 **31.9%**、x 轴距底 **21.6%**、
 * 曲线底到 x 轴 24.5%。
 */
public class PlotRefDepth {

    /** 与生产常量一致的横向部分（本对比只动纵向）。 */
    static final float LEFT_PAD = 0.082f, RIGHT_PAD = 0.082f;
    static final float TITLE_BAND = 0.085f, BOTTOM_PAD = 0.125f;
    static final float INSET_X = 0.2368f;
    static final float AXIS_STROKE_DP = 1.5f, MARK_LINE_STROKE_DP = 1.2f;
    static final float DENSITY = 2.75f;

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0xA8A8B0);
    static final Color GRID = new Color(0x2B2B2F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);

    /** 档位：insetY → 说明。参考图目标是曲线占画布高 31.9%。 */
    static final float[] INSETS = {0.03f, 0.15f, 0.20f, 0.2618f};
    static final String[] TAGS = {
        "current 0.03  (curve=63% of canvas)",
        "0.15          (curve=47%)",
        "0.20          (curve=40%)",
        "0.2618        (curve=32% = REFERENCE)",
    };

    public static void main(String[] args) throws Exception {
        int cellW = 700;
        int cellH = Math.round(cellW * 0.66f);   // 462，保持 0.66 画布比例
        int cols = 2, rows = 2, gap = 18, header = 40, label = 30;

        int W = cols * cellW + (cols + 1) * gap;
        int H = header + rows * (cellH + label) + (rows + 1) * gap;
        BufferedImage sheet = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        PlotRefFinal.aa(g);
        g.setColor(new Color(0x0E0E10));
        g.fillRect(0, 0, W, H);

        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(new Color(0xE8E8EE));
        g.drawString("VERTICAL DEPTH — PLOT_INSET_Y_RATIO  (reference target: curve = 31.9% of canvas height)", 14, 28);

        for (int i = 0; i < INSETS.length; i++) {
            int cx = gap + (i % cols) * (cellW + gap);
            int cy = header + gap + (i / cols) * (cellH + label + gap);

            BufferedImage cell = new BufferedImage(cellW, cellH, BufferedImage.TYPE_INT_RGB);
            Graphics2D cg = cell.createGraphics();
            PlotRefFinal.aa(cg);
            Metrics m = draw(cg, cellW, cellH, INSETS[i]);
            cg.dispose();

            g.drawImage(cell, cx, cy, null);
            g.setColor(INSETS[i] == 0.03f ? new Color(0xFF6B6B)
                    : (i == INSETS.length - 1 ? new Color(0x3DDC97) : new Color(0x5AA9FF)));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(cx, cy, cellW - 1, cellH - 1);

            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.drawString(String.format("insetY = %-8s curve=%.0f%%  bottom gap=%.0f%%",
                    TAGS[i].split(" ")[0], m.curveRatio * 100, m.bottomGap * 100), cx + 4, cy + cellH + 21);
        }

        g.dispose();
        ImageIO.write(sheet, "png", new File(args.length > 0 ? args[0] : "plot-ref-depth.png"));
        System.out.println("written plot-ref-depth.png  " + W + "x" + H);
    }

    static class Metrics { float curveRatio, bottomGap; }

    static Metrics draw(Graphics2D g, int W, int H, float insetYRatio) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        double xLo = -2.0, xHi = 2.0, spanX = xHi - xLo;
        double yMin = 0.35, yPeak = 1.0;
        double yLo = yMin - (yPeak - yMin) * 0.06 * 2;
        double yHi = yPeak + (yPeak - yMin) * 0.06;
        double spanY = yHi - yLo;

        float titleSize = W * 0.031f, tickSize = W * 0.024f;
        float padLeft = W * LEFT_PAD, padRight = W * RIGHT_PAD;
        float padTop = H * TITLE_BAND, padBottom = H * BOTTOM_PAD;
        float plotW = W - padLeft - padRight, plotH = H - padTop - padBottom;
        float insetX = plotW * INSET_X, insetY = plotH * insetYRatio;
        float drawX = padLeft + insetX, drawY = padTop + insetY;
        float drawW = plotW - insetX * 2f, drawH = plotH - insetY * 2f;

        final float fX = drawX, fY = drawY, fW = drawW, fH = drawH;

        // 网格
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = fX + (float) ((t - xLo) / spanX * fW);
            g.drawLine(Math.round(px), Math.round(fY), Math.round(px), Math.round(fY + fH));
        }
        for (double t : new double[]{yMin, yPeak}) {
            float py = fY + fH - (float) ((t - yLo) / spanY * fH);
            g.drawLine(Math.round(fX), Math.round(py), Math.round(fX + fW), Math.round(py));
        }

        // 曲线
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(2.2f * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        java.awt.geom.Path2D path = new java.awt.geom.Path2D.Float();
        int N = 900;
        float apexY = 0, endY = 0;
        for (int i = 0; i <= N; i++) {
            double x = xLo + (xHi - xLo) * i / N;
            double t = x / 2.0;
            double y = yMin + (yPeak - yMin) * (t * t);
            float px = fX + (float) ((x - xLo) / spanX * fW);
            float py = fY + fH - (float) ((y - yLo) / spanY * fH);
            if (i == 0) { path.moveTo(px, py); endY = py; }
            else path.lineTo(px, py);
            if (i == N / 2) apexY = py;
        }
        g.draw(path);

        // 坐标轴（与生产一致）
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

        // 轴名 f
        g.setFont(new Font("Serif", Font.ITALIC, Math.round(tickSize * 1.02f)));
        g.setColor(TEXT);
        g.drawString("f", Math.round(axisRight + W * 0.006f), Math.round(axisY + H * 0.030f));

        // markLine ±B/2
        g.setColor(MARK);
        g.setStroke(new BasicStroke(MARK_LINE_STROKE_DP * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double t : new double[]{-2, 2}) {
            float px = fX + (float) ((t - xLo) / spanX * fW);
            double yv = yMin + (yPeak - yMin) * ((t / 2.0) * (t / 2.0));
            float top = fY + fH - (float) ((yv - yLo) / spanY * fH);
            g.drawLine(Math.round(px), Math.round(top), Math.round(px), Math.round(axisY));
        }

        // 刻度
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

        // 标题
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(titleSize)));
        g.setColor(TEXT);
        String title = "P_Yc(f) = P_Ys(f)";
        fm = g.getFontMetrics();
        g.drawString(title, Math.round(W / 2f - fm.stringWidth(title) / 2f), Math.round(H * 0.052f));

        Metrics m = new Metrics();
        m.curveRatio = Math.abs(apexY - endY) / H;
        m.bottomGap = (H - axisY) / H;
        return m;
    }
}
