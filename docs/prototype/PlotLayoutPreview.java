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
    /** 绘图区内缩比例：曲线四周的视觉余量（改「画在哪」而非「范围是多少」）。 */
    static final float PLOT_INSET_X_RATIO = 0.05f;
    static final float PLOT_INSET_Y_RATIO = 0.07f;

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

    /** 渲染模式：用于对比三个历史阶段的行为。 */
    enum Mode {
        /** v1.0.35：范围外扩 4%（定义域被撑大 ⇒ 曲线看起来越过 ±B/2）+ y 文案截断。 */
        LEGACY,
        /** v1.0.36：范围精确、但绘图区**不内缩** ⇒ 曲线顶死在框线上。 */
        TIGHT,
        /** 当前：范围精确 + 绘图区内缩 ⇒ 位置正确且留有余量。 */
        INSET,
    }

    /**
     * 渲染一张图。
     */
    static BufferedImage render(Mode mode, int w, int h) {
        boolean legacy = mode == Mode.LEGACY;
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

        // 外层绘图区（图例/标题带所处矩形）
        float plotX = padLeftFinal;
        float plotY = padTop;
        float plotW = w - padLeftFinal - padRight;
        float plotH = h - padTop - padBottom;

        // 内层绘图区：数据真正落笔的范围。范围是语义量（必须精确），
        // 这块矩形是视觉量（可以留白），二者解耦 —— 这就是本次修复的关键。
        float insetX = mode == Mode.INSET ? plotW * PLOT_INSET_X_RATIO : 0f;
        float insetY = mode == Mode.INSET ? plotH * PLOT_INSET_Y_RATIO : 0f;
        float drawX = plotX + insetX;
        float drawY = plotY + insetY;
        float drawW = plotW - insetX * 2f;
        float drawH = plotH - insetY * 2f;

        // ---- 网格 ----
        g.setColor(new Color(0x2B2B2F));
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = drawX + (float) ((t - xLo) / spanX * drawW);
            g.drawLine((int) px, (int) drawY, (int) px, (int) (drawY + drawH));
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
            float px = drawX + (float) ((x - xLo) / spanX * drawW);
            float py = drawY + drawH - (float) ((y - yLo) / spanY * drawH);
            if (!started) { path.moveTo(px, py); started = true; } else { path.lineTo(px, py); }
        }
        g.draw(path);

        // ---- 坐标轴 ----
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1f));
        g.drawLine((int) drawX, (int) (drawY + drawH), (int) (drawX + drawW), (int) (drawY + drawH));
        g.drawLine((int) drawX, (int) drawY, (int) drawX, (int) (drawY + drawH));

        // ---- markLine：±B/2 定义域边界（落在绘图区内部，不贴框线）----
        g.setColor(MARK);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{w * 0.005f, w * 0.004f}, 0f));
        for (double t : new double[]{-2, 2}) {
            float px = drawX + (float) ((t - xLo) / spanX * drawW);
            g.drawLine((int) px, (int) drawY, (int) px, (int) (drawY + drawH));
        }

        // ---- y 轴刻度文案 ----
        Font yFont = new Font("SansSerif", Font.PLAIN, Math.round(tickSizeY));
        g.setFont(yFont);
        FontMetrics yfm = g.getFontMetrics(yFont);
        float labelBaselineY = drawY + drawH - (float) ((0.35 - yLo) / spanY * drawH) + tickSizeY * 0.36f;
        String shown = yTickText;
        float lw = yfm.stringWidth(shown);
        float lx = drawX - w * Y_TICK_GAP_RATIO - lw;
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
            float cx = drawX + (float) ((positions[i] - xLo) / spanX * drawW);
            int tw = xfm.stringWidth(labels[i]);
            g.drawString(labels[i], cx - tw / 2f, drawY + drawH + h * 0.038f);
        }

        // ---- 标题 ----
        g.setFont(new Font("Serif", Font.ITALIC, Math.round(titleSize)));
        g.setColor(TEXT);
        FontMetrics tfm = g.getFontMetrics(g.getFont());
        String title = "P_rc(f) = P_rs(f)";
        g.drawString(title, w / 2f - tfm.stringWidth(title) / 2f, h * 0.052f);

        // ---- 左上角标注 ----
        String badge;
        Color badgeColor;
        switch (mode) {
            case LEGACY:
                badge = "BEFORE (v1.0.35): range padded 4% -> curve crosses \u00b1B/2; y label truncated";
                badgeColor = new Color(0xFF6B6B);
                break;
            case TIGHT:
                badge = "v1.0.36 first try: range exact BUT curve touches the frame";
                badgeColor = new Color(0xFFB454);
                break;
            default:
                badge = "FIXED: range exact + plot inset -> curve ends on \u00b1B/2 with clear margin";
                badgeColor = new Color(0x3DDC97);
                break;
        }
        g.setFont(new Font("SansSerif", Font.BOLD, Math.round(w * 0.019f)));
        g.setColor(badgeColor);
        g.drawString(badge, w * 0.02f, h * 0.028f);

        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        int w = 993, h = 560;
        BufferedImage[] imgs = {
                render(Mode.LEGACY, w, h),
                render(Mode.TIGHT, w, h),
                render(Mode.INSET, w, h),
        };
        String[] captions = {
                "1) v1.0.35  \u2014  \u66f2\u7ebf\u8d8a\u8fc7 \u00b1B/2\u3001y \u523b\u5ea6\u53ea\u5269\u7701\u7565\u53f7",
                "2) v1.0.36 \u9996\u7248  \u2014  \u8fb9\u754c\u7cbe\u786e\u4f46\u66f2\u7ebf\u9876\u6b7b\u6846\u7ebf",
                "3) \u6700\u7ec8\u65b9\u6848  \u2014  \u8fb9\u754c\u7cbe\u786e + \u56db\u5468\u7559\u6709\u4f59\u91cf",
        };
        int gap = 26;
        BufferedImage combo = new BufferedImage(w, (h + gap) * imgs.length, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, combo.getWidth(), combo.getHeight());
        for (int i = 0; i < imgs.length; i++) {
            int y = i * (h + gap);
            g.drawImage(imgs[i], 0, y, null);
            g.setColor(new Color(0xE6E6E8));
            g.setFont(new Font("SansSerif", Font.BOLD, Math.round(w * 0.020f)));
            g.drawString(captions[i], w * 0.03f, y + h + gap * 0.62f);
        }
        g.dispose();

        String out = args.length > 0 ? args[0] : "plot-layout-preview.png";
        ImageIO.write(combo, "png", new File(out));
        System.out.println("written: " + out + "  size=" + combo.getWidth() + "x" + combo.getHeight());

        // 打印关键数值便于核对
        Graphics2D gg = (Graphics2D) new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getGraphics();
        float padLeft = w * LEFT_PAD_RATIO;
        FontMetrics fm = gg.getFontMetrics(new Font("SansSerif", Font.PLAIN, Math.round(w * 0.024f)));
        float widest = fm.stringWidth("N\u2080(2\u03c0f_c)\u00b2");
        float available = padLeft - w * Y_TICK_GAP_RATIO;
        float[] plan = planYTickSize(widest, available, w * 0.024f);
        float padL = padLeft + Math.max(0f, Math.min(w * LEFT_PAD_EXTRA_MAX_RATIO,
                plan[1] + w * Y_TICK_GAP_RATIO - padLeft));
        System.out.printf("yLabelWidth=%.1f available=%.1f -> textSize=%.2f (min %.2f) padLeft=%.1f%n",
                widest, available, plan[0], w * 0.024f * Y_TICK_MIN_SIZE_SCALE, padL);

        float plotW = w - padL - w * RIGHT_PAD_RATIO;
        float plotH = h - h * TITLE_BAND_RATIO - h * BOTTOM_PAD_RATIO;
        float insetX = plotW * PLOT_INSET_X_RATIO;
        System.out.printf("plotW=%.1f insetX=%.1f  -> left margin of data=%.1fpx (%.2f%% of w)%n",
                plotW, insetX, insetX, insetX / w * 100f);
        System.out.printf("plotH=%.1f insetY=%.1f (%.2f%% of h)%n",
                plotH, plotH * PLOT_INSET_Y_RATIO, plotH * PLOT_INSET_Y_RATIO / h * 100f);
        gg.dispose();
    }
}
