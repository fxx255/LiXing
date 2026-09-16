import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 绘图布局验证用的一次性预览工具（不是 App 代码）。
 *
 * 目的：用**真实的字体度量**复现 PlotBitmapRenderer 的布局算法，把
 * 「v1.0.35 旧行为」与「本次修复后行为」并排画出来，肉眼确认两处修复：
 *   1. 定义域 ±B/2 不被外扩 ⇒ 曲线终止在 ±B/2 竖线上，不再溢出定义域；
 *   2. y 轴刻度文案按内容自适应字号/留白 ⇒ 不再退化成「…」三个点。
 *
 * 算法与常量严格照抄 Kotlin 版本（PlotBitmapRenderer.kt），唯一差别是
 * 用 Java2D 代替 Canvas，从而拿到真实文字宽度（Robolectric 下 measureText 恒为 1px）。
 */
public class PlotLayoutPreview {

    // ---- 与 Kotlin 版本一致的常量 ----
    static final float LEFT_PAD_RATIO = 0.082f;
    static final float RIGHT_PAD_RATIO = 0.028f;
    static final float TOP_PAD_RATIO = 0.055f;
    static final float TITLE_BAND_RATIO = 0.085f;
    static final float BOTTOM_PAD_RATIO = 0.125f;
    static final float Y_TICK_GAP_RATIO = 0.010f;
    static final float LEFT_PAD_EXTRA_MAX_RATIO = 0.10f;
    static final float Y_TICK_MIN_SIZE_SCALE = 0.62f;

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0x4A4A4F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);

    /** y 轴刻度文案排版方案：优先缩字号，缩到下限仍不够才加宽留白。 */
    static float[] planYTickSize(float widest, float available, float baseSize) {
        if (widest <= 0f || baseSize <= 0f) return new float[]{baseSize, 0f};
        if (available <= 0f) return new float[]{baseSize, widest};
        if (widest <= available) return new float[]{baseSize, widest};

        float scaled = baseSize * (available / widest);
        float minSize = baseSize * Y_TICK_MIN_SIZE_SCALE;
        if (scaled >= minSize) return new float[]{scaled, widest * (scaled / baseSize)};
        return new float[]{minSize, widest * (minSize / baseSize)};
    }

    /**
     * 渲染一张图。
     *
     * @param legacy true = 复现 v1.0.35 旧行为（定义域外扩 4% + y 文案固定字号截断）
     */
    static BufferedImage render(boolean legacy, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        // ---- 数据：理想低通/带通谱，定义域 ±B/2（数值占位 = ±2）----
        double dataLo = -2.0, dataHi = 2.0;
        double specLo = -2.0, specHi = 2.0;

        // ---- x 轴范围 ----
        double xLo, xHi;
        if (legacy) {
            // 旧行为：数据贴边就外扩 4%（把定义域撑大，曲线看起来越过边界）
            xLo = specLo;
            xHi = specHi;
            double span = xHi - xLo;
            double pad = span * 0.04;
            xLo -= pad;
            xHi += pad;
        } else {
            // 新行为：模型显式给的定义域原样保留
            xLo = specLo;
            xHi = specHi;
        }
        double spanX = xHi - xLo;

        // y 轴范围（曲线 y = 0.35 + 0.65*(x/2)^2 形状）
        double yLo = 0.0, yHi = 1.15;
        double spanY = yHi - yLo;

        // ---- 布局 ----
        float padLeft = w * LEFT_PAD_RATIO;
        float padRight = w * RIGHT_PAD_RATIO;
        float padTop = h * (TITLE_BAND_RATIO);
        float padBottom = h * BOTTOM_PAD_RATIO;
        float titleSize = w * 0.031f;
        float tickSize = w * 0.024f;

        String yTickText = "N\u2080(2\u03c0f_c)\u00b2"; // N₀(2πf_c)²
        Font baseFont = new Font("SansSerif", Font.PLAIN, Math.round(tickSize));
        FontMetrics bfm = g.getFontMetrics(baseFont);
        float widest = (float) bfm.stringWidth(yTickText);
        float available = padLeft - w * Y_TICK_GAP_RATIO;

        float tickSizeY;
        float padLeftFinal;
        if (legacy) {
            tickSizeY = tickSize;
            padLeftFinal = padLeft;
        } else {
            float[] plan = planYTickSize(widest, available, tickSize);
            tickSizeY = plan[0];
            float requiredWidth = plan[1];
            float extra = Math.max(0f, Math.min(w * LEFT_PAD_EXTRA_MAX_RATIO,
                    requiredWidth + w * Y_TICK_GAP_RATIO - padLeft));
            padLeftFinal = padLeft + extra;
        }

        float plotX = padLeftFinal;
        float plotY = padTop;
        float plotW = w - padLeftFinal - padRight;
        float plotH = h - padTop - padBottom;

        // ---- 网格 ----
        g.setColor(new Color(0x2B2B2F));
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = plotX + (float) ((t - xLo) / spanX * plotW);
            g.drawLine((int) px, (int) plotY, (int) px, (int) (plotY + plotH));
        }

        // ---- 曲线：y = 0.35 + 0.65*(x/2)^2，在定义域 ±2 内采样 ----
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(w * 0.004f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = new Path2D.Float();
        boolean started = false;
        int N = 900;
        for (int i = 0; i <= N; i++) {
            // 注意：采样区间用的是**定义域**（dataLo..dataHi），不是 xLo..xHi
            double x = dataLo + (dataHi - dataLo) * i / N;
            double y = 0.35 + 0.65 * (x / 2.0) * (x / 2.0);
            float px = plotX + (float) ((x - xLo) / spanX * plotW);
            float py = plotY + plotH - (float) ((y - yLo) / spanY * plotH);
            if (!started) { path.moveTo(px, py); started = true; } else { path.lineTo(px, py); }
        }
        g.draw(path);

        // ---- 坐标轴 ----
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1f));
        g.drawLine((int) plotX, (int) (plotY + plotH), (int) (plotX + plotW), (int) (plotY + plotH));
        g.drawLine((int) plotX, (int) plotY, (int) plotX, (int) (plotY + plotH));

        // ---- markLine：±B/2 定义域边界 ----
        g.setColor(MARK);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{w * 0.005f, w * 0.004f}, 0f));
        for (double t : new double[]{-2, 2}) {
            float px = plotX + (float) ((t - xLo) / spanX * plotW);
            g.drawLine((int) px, (int) plotY, (int) px, (int) (plotY + plotH));
        }

        // ---- y 轴刻度文案 ----
        Font yFont = new Font("SansSerif", Font.PLAIN, Math.round(tickSizeY));
        g.setFont(yFont);
        FontMetrics yfm = g.getFontMetrics(yFont);
        float labelBaselineY = plotY + plotH - (float) ((0.35 - yLo) / spanY * plotH) + tickSizeY * 0.36f;
        String shown = yTickText;
        float lw = yfm.stringWidth(shown);
        float lx = plotX - w * Y_TICK_GAP_RATIO - lw;
        if (legacy) {
            // 旧行为：夹在左侧留白里，装不下就只画省略号（用户看到的「三个点」）
            float limit = padLeft - w * 0.008f;
            if (lx + lw > limit) {
                shown = "\u2026";
                lw = yfm.stringWidth(shown);
                lx = plotX - w * Y_TICK_GAP_RATIO - lw;
            }
        }
        g.setColor(SUBTEXT);
        g.drawString(shown, lx, labelBaselineY);

        // ---- x 轴刻度文案 ----
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(tickSize)));
        FontMetrics xfm = g.getFontMetrics(g.getFont());
        String[] labels = {"\u2212B/2", "O", "B/2"};
        double[] positions = {-2, 0, 2};
        g.setColor(SUBTEXT);
        for (int i = 0; i < positions.length; i++) {
            float cx = plotX + (float) ((positions[i] - xLo) / spanX * plotW);
            int tw = xfm.stringWidth(labels[i]);
            g.drawString(labels[i], cx - tw / 2f, plotY + plotH + h * 0.038f);
        }

        // ---- 标题 ----
        g.setFont(new Font("Serif", Font.ITALIC, Math.round(titleSize)));
        g.setColor(TEXT);
        FontMetrics tfm = g.getFontMetrics(g.getFont());
        String title = "P_rc(f) = P_rs(f)";
        g.drawString(title, w / 2f - tfm.stringWidth(title) / 2f, h * 0.052f);

        // ---- 左上角标注 ----
        g.setFont(new Font("SansSerif", Font.BOLD, Math.round(w * 0.020f)));
        g.setColor(legacy ? new Color(0xFF6B6B) : new Color(0x3DDC97));
        g.drawString(legacy ? "BEFORE  (v1.0.35)" : "AFTER  (fixed)", w * 0.02f, h * 0.03f);

        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        int w = 993, h = 655;
        BufferedImage before = render(true, w, h);
        BufferedImage after = render(false, w, h);

        BufferedImage combo = new BufferedImage(w, h * 2 + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combo.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h * 2 + 20);
        g.drawImage(before, 0, 0, null);
        g.drawImage(after, 0, h + 20, null);
        g.dispose();

        String out = args.length > 0 ? args[0] : "plot-layout-preview.png";
        ImageIO.write(combo, "png", new File(out));
        System.out.println("written: " + out + "  size=" + combo.getWidth() + "x" + combo.getHeight());

        // 打印关键数值便于核对
        for (boolean legacy : new boolean[]{true, false}) {
            Graphics2D gg = (Graphics2D) new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getGraphics();
            float padLeft = w * LEFT_PAD_RATIO;
            FontMetrics fm = gg.getFontMetrics(
                    new Font("SansSerif", Font.PLAIN, Math.round(w * 0.024f)));
            float widest = fm.stringWidth("N\u2080(2\u03c0f_c)\u00b2");
            float[] plan = planYTickSize(widest, padLeft - w * Y_TICK_GAP_RATIO, w * 0.024f);
            System.out.printf("%s: yLabelWidth=%.1f available=%.1f -> textSize=%.2f padLeft=%.1f%n",
                    legacy ? "BEFORE" : "AFTER ",
                    widest, padLeft - w * Y_TICK_GAP_RATIO, legacy ? w * 0.024f : plan[0],
                    legacy ? padLeft : padLeft + Math.max(0f,
                            Math.min(w * LEFT_PAD_EXTRA_MAX_RATIO,
                                    plan[1] + w * Y_TICK_GAP_RATIO - padLeft)));
            gg.dispose();
        }
    }
}
