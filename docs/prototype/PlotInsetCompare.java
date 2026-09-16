import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 横向内缩比例对比：曲线应占绘图区宽度的多少才「最美观」。
 *
 * 背景：用户反馈当前生成的图里曲线**占满了整个画出的横坐标范围**，
 * 希望像教材题 3.3(c) 那样，曲线只占横轴的 ~70%，两侧留出明显余量。
 *
 * 注意区分两个「范围」：
 *   · 坐标轴范围（语义量，= 定义域 ±B/2）—— 绝不能动；
 *   · 绘图区矩形（视觉量）—— 曲线画在这块矩形里，矩形可以比曲线宽。
 *
 * 本工具把同一个定义域 ±B/2 放进不同的绘图区宽度，看曲线实际占比。
 */
public class PlotInsetCompare {

    static final Color BG = new Color(0x1C1C1E);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0x4A4A4F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);
    static final Color NOTE = new Color(0x3DDC97);

    /**
     * @param insetX 横向内缩比例（相对绘图区宽）
     * @param axisAtDomainEnds true = 坐标轴画在定义域端点（教材风格：y 轴在中间、
     *                         曲线两端即绘图区边界）；false = 坐标轴画在绘图区边界
     */
    static BufferedImage render(float insetX, boolean label) {
        int w = 1000, h = 460;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        float padLeft = w * 0.082f, padRight = w * 0.028f;
        float padTop = h * 0.10f, padBottom = h * 0.125f;
        float plotX = padLeft, plotY = padTop;
        float plotW = w - padLeft - padRight, plotH = h - padTop - padBottom;

        float ins = plotW * insetX;
        float drawX = plotX + ins, drawY = plotY + plotH * 0.06f;
        float drawW = plotW - ins * 2f, drawH = plotH * 0.88f;

        double xLo = -2.0, xHi = 2.0, spanX = xHi - xLo;
        double yLo = 0.0, yHi = 1.15, spanY = yHi - yLo;

        // 网格（按定义域刻度）
        g.setColor(new Color(0x2B2B2F));
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = drawX + (float) ((t - xLo) / spanX * drawW);
            g.drawLine((int) px, (int) drawY, (int) px, (int) (drawY + drawH));
        }

        // 曲线 y = 0.35 + 0.65*(x/2)^2
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(w * 0.0045f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = new Path2D.Float();
        boolean started = false;
        for (int i = 0; i <= 900; i++) {
            double x = xLo + spanX * i / 900.0;
            double y = 0.35 + 0.65 * (x / 2.0) * (x / 2.0);
            float px = drawX + (float) ((x - xLo) / spanX * drawW);
            float py = drawY + drawH - (float) ((y - yLo) / spanY * drawH);
            if (!started) { path.moveTo(px, py); started = true; } else { path.lineTo(px, py); }
        }
        g.draw(path);

        // 坐标轴：画在**绘图区**边界（与外层框线重合的观感）
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine((int) drawX, (int) (drawY + drawH), (int) (drawX + drawW), (int) (drawY + drawH));
        g.drawLine((int) drawX, (int) drawY, (int) drawX, (int) (drawY + drawH));

        // markLine ±B/2
        g.setColor(MARK);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 5f}, 0f));
        for (double t : new double[]{-2, 2}) {
            float px = drawX + (float) ((t - xLo) / spanX * drawW);
            g.drawLine((int) px, (int) drawY, (int) px, (int) (drawY + drawH));
        }

        // x 轴刻度
        g.setFont(new Font("SansSerif", Font.PLAIN, 17));
        FontMetrics fm = g.getFontMetrics();
        String[] labels = {"\u2212B/2", "O", "B/2"};
        double[] pos = {-2, 0, 2};
        g.setColor(SUBTEXT);
        for (int i = 0; i < pos.length; i++) {
            float cx = drawX + (float) ((pos[i] - xLo) / spanX * drawW);
            g.drawString(labels[i], cx - fm.stringWidth(labels[i]) / 2f, drawY + drawH + 30);
        }

        // 顶部说明：曲线占绘图区宽度的比例
        if (label) {
            g.setColor(NOTE);
            g.setFont(new Font("SansSerif", Font.BOLD, 19));
            String txt = String.format("inset=%.0f%%  ->  curve occupies %.0f%% of plot width",
                    insetX * 100, (drawW / plotW) * 100);
            g.drawString(txt, 20, 28);
        }
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        float[] insets = {0.0f, 0.05f, 0.15f, 0.20f};
        String[] caps = {
                "0%   (v1.0.36 当前)  曲线占满 100%",
                "5%   曲线占 90%",
                "15%  曲线占 70%  <-- 教材观感",
                "20%  曲线占 60%",
        };
        int w = 1000, h = 460, gap = 30;
        BufferedImage combo = new BufferedImage(w, (h + gap) * insets.length, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, combo.getWidth(), combo.getHeight());
        for (int i = 0; i < insets.length; i++) {
            int y = i * (h + gap);
            g.drawImage(render(insets[i], true), 0, y, null);
            g.setColor(i == 2 ? new Color(0x3DDC97) : new Color(0x9A9AA0));
            g.setFont(new Font("SansSerif", Font.BOLD, 19));
            g.drawString(caps[i], 22, y + h + 22);
        }
        g.dispose();

        String out = args.length > 0 ? args[0] : "plot-inset-compare.png";
        ImageIO.write(combo, "png", new File(out));
        System.out.println("written: " + out + "  " + combo.getWidth() + "x" + combo.getHeight());
        for (float f : insets) {
            System.out.printf("inset=%.2f -> curve/plot = %.1f%%%n", f, (1 - 2 * f) * 100);
        }
    }
}
