import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 用**生产代码的新常量 + 生产绘制顺序**复刻 PlotBitmapRenderer 的结果，用来出最终效果图。
 *
 * 与 PlotBitmapRenderer.kt 逐项对齐（改那边要同步改这里）：
 *   · 常量：见下方 static final 区，与 kt 文件末尾的 private const 一一对应；
 *   · 字号基准：kt 用 density（模拟器 2.75），本文件用 DENSITY 常量；
 *   · 文字宽度：kt 的 smartTextWidth 走真实 FontMetrics，这里也用 FontMetrics（不用字符串长度）；
 *   · 字体：普通文字 `sans-serif` → SansSerif；轴名 `f` 是单字符数学符号 → Serif Italic
 *           （Android 的 sans-serif 对单个拉丁字母用 upright，跟参考图的斜体 f 不符）。
 * 画布 1400x924（0.66 高宽比，与 PlotImageStore.PLOT_HEIGHT_RATIO 一致）。
 */
public class PlotRefFinal {

    // ---- 与生产代码一致的常量 ----
    static final float PLOT_INSET_X_RATIO = 0.2368f;
    static final float PLOT_INSET_Y_RATIO = 0.03f;
    static final float LEFT_PAD_RATIO = 0.082f;
    static final float RIGHT_PAD_RATIO = 0.082f;
    static final float TOP_PAD_RATIO = 0.055f;
    static final float TITLE_BAND_RATIO = 0.085f;
    static final float LEGEND_BAND_RATIO = 0.048f;
    static final float BOTTOM_PAD_RATIO = 0.125f;
    static final float Y_TICK_GAP_RATIO = 0.010f;
    static final float AXIS_OVERHANG_L_RATIO = 0.012f;
    static final float AXIS_OVERHANG_R_RATIO = 0.030f;
    static final float AXIS_VERT_OVERHANG_RATIO = 0.10f;
    static final float AXIS_VERT_BELOW_RATIO = 0.012f;
    static final float AXIS_STROKE_DP = 1.5f;
    static final float MARK_LINE_STROKE_DP = 1.2f;
    static final float DENSITY = 2.75f;

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0xA8A8B0);
    static final Color GRID = new Color(0x2B2B2F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);

    static final String PLAIN = "SansSerif";
    static final String MATH = "Serif";

    public static void main(String[] args) throws Exception {
        int W = 1400;
        int H = Math.round(W * 0.66f);      // 924
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        aa(g);
        draw(g, W, H, true);
        g.dispose();
        ImageIO.write(img, "png", new File(args.length > 0 ? args[0] : "plot-ref-final.png"));
        System.out.printf("written %dx%d (aspect %.2f)%n", W, H, H / (float) W);
    }

    /** 与 kt 的 smartTextWidth 语义一致：用真实字体度量量宽度。 */
    static float textWidth(Graphics2D g, String s, float size, boolean math) {
        g.setFont(new Font(math ? MATH : PLAIN, math ? Font.ITALIC : Font.PLAIN, Math.round(size)));
        return g.getFontMetrics().stringWidth(s);
    }

    static void draw(Graphics2D g, int W, int H, boolean title) {
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        // 数据：频谱形状，定义域 [-B/2, B/2] 用 ±2 代表
        double xLo = -2.0, xHi = 2.0, spanX = xHi - xLo;
        // y 轴范围模拟 balancedRange 的结果：上下各留 6% 边距
        double yMin = 0.35, yPeak = 1.0;
        double yLo = yMin - (yPeak - yMin) * 0.06 * 2;
        double yHi = yPeak + (yPeak - yMin) * 0.06;
        double spanY = yHi - yLo;

        // ---- 3. 布局（与 kt 第 3 步同序：先定字号、再用真实度量定 y 刻度方案）----
        float titleSize = W * 0.031f, tickSize = W * 0.024f;
        float padLeft = W * LEFT_PAD_RATIO, padRight = W * RIGHT_PAD_RATIO;
        float padTop = H * (title ? TITLE_BAND_RATIO : TOP_PAD_RATIO);
        float padBottom = H * BOTTOM_PAD_RATIO;

        // planYTickSize 简化版：本图 y 刻度是纯数字，一定装得下 ⇒ 用基准字号
        float tickSizeY = tickSize;
        float padLeftFinal = padLeft;

        float plotX = padLeftFinal, plotY = padTop;
        float plotW = W - padLeftFinal - padRight, plotH = H - padTop - padBottom;

        // ---- 3b. 绘图区内缩 ----
        float insetX = plotW * PLOT_INSET_X_RATIO, insetY = plotH * PLOT_INSET_Y_RATIO;
        float drawX = plotX + insetX, drawY = plotY + insetY;
        float drawW = plotW - insetX * 2f, drawH = plotH - insetY * 2f;

        final float fDrawX = drawX, fDrawY = drawY, fDrawW = drawW, fDrawH = drawH;

        // ---- 5. 网格 ----
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = fDrawX + (float) ((t - xLo) / spanX * fDrawW);
            g.drawLine(Math.round(px), Math.round(fDrawY), Math.round(px), Math.round(fDrawY + fDrawH));
        }
        for (double t : new double[]{yMin, yPeak}) {
            float py = fDrawY + fDrawH - (float) ((t - yLo) / spanY * fDrawH);
            g.drawLine(Math.round(fDrawX), Math.round(py), Math.round(fDrawX + fDrawW), Math.round(py));
        }

        // ---- 6. 曲线 ----
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(2.2f * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = new Path2D.Float();
        int N = 900;
        for (int i = 0; i <= N; i++) {
            double x = xLo + (xHi - xLo) * i / N;
            double t = x / 2.0;
            double y = yMin + (yPeak - yMin) * (t * t);
            float px = fDrawX + (float) ((x - xLo) / spanX * fDrawW);
            float py = fDrawY + fDrawH - (float) ((y - yLo) / spanY * fDrawH);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        g.draw(path);

        // ---- 7. 坐标轴 ----
        float axisY = fDrawY + fDrawH;
        float axisLeft = plotX - W * AXIS_OVERHANG_L_RATIO;
        float axisRight = plotX + plotW + W * AXIS_OVERHANG_R_RATIO;

        g.setColor(AXIS);
        g.setStroke(new BasicStroke(AXIS_STROKE_DP * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(Math.round(axisLeft), Math.round(axisY), Math.round(axisRight), Math.round(axisY));

        boolean zeroInsideX = xLo <= 0 && 0 <= xHi;
        float yAxisX = zeroInsideX ? (fDrawX + (float) ((0 - xLo) / spanX * fDrawW)) : fDrawX;
        float yAxisTop = fDrawY - plotH * AXIS_VERT_OVERHANG_RATIO;
        float yAxisBottom = axisY + H * AXIS_VERT_BELOW_RATIO;
        g.drawLine(Math.round(yAxisX), Math.round(yAxisTop), Math.round(yAxisX), Math.round(yAxisBottom));

        // 轴端小刻度
        g.drawLine(Math.round(axisRight), Math.round(axisY), Math.round(axisRight), Math.round(axisY - H * 0.014f));
        g.drawLine(Math.round(yAxisX), Math.round(yAxisTop), Math.round(yAxisX + W * 0.010f), Math.round(yAxisTop));

        // 轴名 f：贴轴远端、与端刻度错开
        g.setColor(TEXT);
        float nameSize = tickSize * 1.02f;
        float nameW = textWidth(g, "f", nameSize, true);
        float nameX = Math.min(axisRight + W * 0.006f, W - nameW - W * 0.004f);
        drawText(g, "f", nameX, axisY + H * 0.030f, nameSize, TEXT, true, false);

        // ---- 11. markLine ±B/2：实线，从轴画到曲线 ----
        g.setColor(MARK);
        g.setStroke(new BasicStroke(MARK_LINE_STROKE_DP * DENSITY, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double t : new double[]{-2, 2}) {
            float px = fDrawX + (float) ((t - xLo) / spanX * fDrawW);
            double yv = yMin + (yPeak - yMin) * ((t / 2.0) * (t / 2.0));
            float curveTop = fDrawY + fDrawH - (float) ((yv - yLo) / spanY * fDrawH);
            g.drawLine(Math.round(px), Math.round(curveTop), Math.round(px), Math.round(drawY + drawH));
        }

        // ---- 8. 刻度 ----
        g.setColor(SUBTEXT);
        String[] lb = {"\u2212B/2", "O", "B/2"};
        double[] pos = {-2, 0, 2};
        float baseline = Math.min(drawY + drawH + H * 0.038f, H - H * 0.012f);
        for (int i = 0; i < 3; i++) {
            float cx = fDrawX + (float) ((pos[i] - xLo) / spanX * fDrawW);
            drawText(g, lb[i], cx, baseline, tickSize, SUBTEXT, false, true);
        }
        for (double t : new double[]{yMin, yPeak}) {
            float py = fDrawY + fDrawH - (float) ((t - yLo) / spanY * fDrawH);
            String s = String.format("%.2f", t);
            float w = textWidth(g, s, tickSizeY, false);
            drawText(g, s, fDrawX - W * Y_TICK_GAP_RATIO - w, py + tickSizeY * 0.36f, tickSizeY, SUBTEXT, false, false);
        }

        // ---- 10. 标题 ----
        if (title) {
            drawText(g, "P_Yc(f) = P_Ys(f)", W / 2f, H * 0.052f, titleSize, TEXT, false, true);
        }
    }

    /** 复刻 drawSmartText 的「普通文字」分支：alignCenter 时 x 是中心，否则是左边界。 */
    static void drawText(Graphics2D g, String s, float x, float baselineY,
                         float size, Color color, boolean math, boolean alignCenter) {
        g.setFont(new Font(math ? MATH : PLAIN, math ? Font.ITALIC : Font.PLAIN, Math.round(size)));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        float left = alignCenter ? x - fm.stringWidth(s) / 2f : x;
        g.drawString(s, Math.round(left), Math.round(baselineY));
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
}
