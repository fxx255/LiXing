import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 只渲染「最终方案」的大图 + 一个带尺寸标注的放大局部，用来确认：
 *   1. 曲线终止在 ±B/2 的虚线上（位置精确）；
 *   2. 虚线与绘图区框线之间有可见余量（不顶死）。
 */
public class PlotFinalZoom {

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0x4A4A4F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);
    static final Color NOTE = new Color(0x3DDC97);

    /** 复刻 PlotBitmapRenderer 的最终布局，返回 {图, drawX, drawY, drawW, drawH, outerRight, outerLeft}。 */
    static Object[] renderInset(int w, int h) {
        float PLOT_INSET_X_RATIO = 0.05f, PLOT_INSET_Y_RATIO = 0.07f;
        float LEFT_PAD = 0.082f, RIGHT_PAD = 0.028f, TOP_PAD = 0.055f;
        float BOTTOM_PAD = 0.125f, TITLE_BAND = 0.085f;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        double dataLo = -2.0, dataHi = 2.0;
        double xLo = -2.0, xHi = 2.0;   // 定义域原样
        double spanX = xHi - xLo;
        double yLo = 0.0, yHi = 1.15, spanY = yHi - yLo;

        float padLeft = w * LEFT_PAD, padRight = w * RIGHT_PAD;
        float padTop = h * TITLE_BAND, padBottom = h * BOTTOM_PAD;
        float tickSize = w * 0.024f;

        float plotX = padLeft, plotY = padTop;
        float plotW = w - padLeft - padRight, plotH = h - padTop - padBottom;
        float insetX = plotW * PLOT_INSET_X_RATIO, insetY = plotH * PLOT_INSET_Y_RATIO;
        float drawX = plotX + insetX, drawY = plotY + insetY;
        float drawW = plotW - insetX * 2f, drawH = plotH - insetY * 2f;

        // 网格
        g.setColor(new Color(0x2B2B2F));
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = drawX + (float) ((t - xLo) / spanX * drawW);
            g.drawLine((int) px, (int) drawY, (int) px, (int) (drawY + drawH));
        }
        for (double t : new double[]{0.35, 1.0}) {
            float py = drawY + drawH - (float) ((t - yLo) / spanY * drawH);
            g.drawLine((int) drawX, (int) py, (int) (drawX + drawW), (int) py);
        }

        // 曲线
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(w * 0.004f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D path = new Path2D.Float();
        boolean started = false;
        int N = 900;
        for (int i = 0; i <= N; i++) {
            double x = dataLo + (dataHi - dataLo) * i / N;
            double y = 0.35 + 0.65 * (x / 2.0) * (x / 2.0);
            float px = drawX + (float) ((x - xLo) / spanX * drawW);
            float py = drawY + drawH - (float) ((y - yLo) / spanY * drawH);
            if (!started) { path.moveTo(px, py); started = true; } else { path.lineTo(px, py); }
        }
        g.draw(path);

        // 坐标轴（画在内缩矩形的边上）
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1f));
        g.drawLine((int) drawX, (int) (drawY + drawH), (int) (drawX + drawW), (int) (drawY + drawH));
        g.drawLine((int) drawX, (int) drawY, (int) drawX, (int) (drawY + drawH));

        // markLine ±B/2
        g.setColor(MARK);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{w * 0.005f, w * 0.004f}, 0f));
        for (double t : new double[]{-2, 2}) {
            float px = drawX + (float) ((t - xLo) / spanX * drawW);
            g.drawLine((int) px, (int) drawY, (int) px, (int) (drawY + drawH));
        }

        // x 轴刻度
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(tickSize)));
        FontMetrics xfm = g.getFontMetrics(g.getFont());
        String[] labels = {"\u2212B/2", "O", "B/2"};
        double[] pos = {-2, 0, 2};
        g.setColor(SUBTEXT);
        for (int i = 0; i < pos.length; i++) {
            float cx = drawX + (float) ((pos[i] - xLo) / spanX * drawW);
            g.drawString(labels[i], cx - xfm.stringWidth(labels[i]) / 2f, drawY + drawH + h * 0.038f);
        }
        // y 轴刻度（完整文案，此处用 Unicode 近似）
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(w * 0.0152f)));
        g.setColor(SUBTEXT);
        g.drawString("N\u2080(2\u03c0f_c)\u00b2", drawX - w * 0.010f - g.getFontMetrics().stringWidth("N\u2080(2\u03c0f_c)\u00b2"),
                drawY + drawH - (float) ((0.35 - yLo) / spanY * drawH) + w * 0.0152f * 0.36f);

        g.dispose();
        return new Object[]{img, drawX, drawY, drawW, drawH, plotX + plotW, plotX};
    }

    public static void main(String[] args) throws Exception {
        int w = 1400, h = 900;
        Object[] r = renderInset(w, h);
        BufferedImage full = (BufferedImage) r[0];
        float drawX = (Float) r[1], drawY = (Float) r[2], drawW = (Float) r[3], drawH = (Float) r[4];
        float outerRight = (Float) r[5], outerLeft = (Float) r[6];

        // 在最终图上加尺寸标注（用注释色画辅助线，说明"这里有余量"）
        Graphics2D g = full.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        float bX = drawX + drawW;   // B/2 竖线 x
        g.setColor(new Color(0x3DDC97, false ? false : true));
        g.setColor(NOTE);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10f, new float[]{5f, 4f}, 0f));
        // 从 B/2 竖线到外层右边界画一条水平标注线
        float ym = drawY + drawH * 0.18f;
        g.drawLine((int) bX, (int) ym, (int) outerRight, (int) ym);
        g.drawLine((int) bX, (int) (ym - 8), (int) bX, (int) (ym + 8));
        g.drawLine((int) outerRight, (int) (ym - 8), (int) outerRight, (int) (ym + 8));
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString(String.format("margin = %.0f px", outerRight - bX),
                bX + (outerRight - bX) / 2f - 46, ym - 10);

        // 左侧同理
        float ym2 = drawY + drawH * 0.30f;
        g.drawLine((int) outerLeft, (int) ym2, (int) drawX, (int) ym2);
        g.drawLine((int) outerLeft, (int) (ym2 - 8), (int) outerLeft, (int) (ym2 + 8));
        g.drawLine((int) drawX, (int) (ym2 - 8), (int) drawX, (int) (ym2 + 8));
        g.drawString(String.format("margin = %.0f px", drawX - outerLeft),
                outerLeft + (drawX - outerLeft) / 2f - 46, ym2 - 10);

        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(NOTE);
        g.drawString("FINAL: domain \u00b1B/2 kept exact, plot area inset for margin", 24, 30);
        g.dispose();

        String out = args.length > 0 ? args[0] : "plot-final-zoom.png";
        ImageIO.write(full, "png", new File(out));
        System.out.printf("written: %s  %dx%d%n", out, full.getWidth(), full.getHeight());
        System.out.printf("drawW=%.1f  B/2 vertical at x=%.1f  outerRight=%.1f  margin=%.1fpx (%.2f%% of w)%n",
                drawW, bX, outerRight, outerRight - bX, (outerRight - bX) / w * 100f);
        System.out.printf("left margin=%.1fpx  symmetry diff=%.2fpx%n",
                drawX - outerLeft, Math.abs((outerRight - bX) - (drawX - outerLeft)));
    }
}
