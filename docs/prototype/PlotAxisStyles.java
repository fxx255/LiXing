import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 坐标轴「样式」方案对比：同一份数据画成 4 种轴样式，用来挑视觉方案。
 *
 * 背景：用户希望「图中有更明显的 xy 坐标轴」，并指出 x 轴可以「延伸出去」
 * （截图里 x 轴底部横线向左右伸出到 -B/2 与 B/2 之外）。
 *
 * 4 个方案：
 *   A 现状   —— 轴只画在内缩绘图区的边上，曲线占 70%，四周留白但**看不到边框**
 *   B 轴线延伸 —— 保持数据映射不变，只把 x 轴横线向左右各延长一段（配箭头），
 *                同时把左竖轴上下延长；这是最贴近截图「延伸出去」的做法
 *   C 完整坐标轴 —— 画成教科书式：轴线穿过原点、带箭头、末端标 x / y
 *   D 加边框 —— 保留内缩余量，但把内缩矩形整体描一圈浅色边框（视觉上更「有框」）
 */
public class PlotAxisStyles {

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color SUBTEXT = new Color(0x8E8E93);
    static final Color AXIS = new Color(0x4A4A4F);
    static final Color AXIS_STRONG = new Color(0x8E8E93);   // 更明显的轴
    static final Color GRID = new Color(0x2B2B2F);
    static final Color MARK = new Color(0x6A6A70);
    static final Color SERIES = new Color(0x5AA9FF);
    static final Color NOTE = new Color(0x3DDC97);

    // 与 PlotBitmapRenderer 一致的布局常量
    static final float PLOT_INSET_X_RATIO = 0.15f;
    static final float PLOT_INSET_Y_RATIO = 0.07f;
    static final float LEFT_PAD = 0.082f, RIGHT_PAD = 0.028f, TOP_PAD = 0.085f;
    static final float BOTTOM_PAD = 0.125f;

    /** 把 4 个方案拼成 2x2 网格。 */
    public static void main(String[] args) throws Exception {
        int cellW = 1000, cellH = 660;
        int gapX = 24, gapY = 44;
        int W = cellW * 2 + gapX * 3;
        int H = cellH * 2 + gapY * 3 + 30;

        BufferedImage sheet = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setColor(new Color(0x101012));
        sg.fillRect(0, 0, W, H);
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        String[] titles = {
                "A  现状（轴只在内缩绘图区边上，曲线占 70%）",
                "B  x 轴延伸（推荐：轴线左右各伸出，配箭头）",
                "C  完整坐标轴（轴线穿过原点 + 箭头 + x/y 标注）",
                "D  内缩 + 浅色边框（保留余量同时「有框」）",
        };

        sg.setFont(new Font("SansSerif", Font.BOLD, 24));
        sg.setColor(new Color(0xE8E8EE));
        sg.drawString("Plot axis style comparison  —  1400x900 canvas, domain [-B/2, B/2], 70% occupancy",
                24, 34);

        for (int i = 0; i < 4; i++) {
            int col = i % 2, row = i / 2;
            int x = gapX + col * (cellW + gapX);
            int y = gapY + 30 + row * (cellH + gapY);
            BufferedImage cell = render(cellW, cellH, i);
            sg.drawImage(cell, x, y, null);
            sg.setColor(new Color(0x3DDC97));
            sg.setStroke(new BasicStroke(2f));
            sg.drawRect(x - 1, y - 1, cellW + 1, cellH + 1);
            sg.setFont(new Font("SansSerif", Font.BOLD, 19));
            sg.setColor(new Color(0xE8E8EE));
            sg.drawString(titles[i], x + 10, y - 8);
        }
        sg.dispose();

        String out = args.length > 0 ? args[0] : "plot-axis-styles.png";
        ImageIO.write(sheet, "png", new File(out));
        System.out.println("written: " + out + "  " + W + "x" + H);
    }

    /** 每个 cell 只画一张图；variant 决定轴样式。 */
    static BufferedImage render(int w, int h, int variant) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        // 数据：定义域 [-B/2, B/2]，用 ±2 代表
        double dataLo = -2.0, dataHi = 2.0;
        double xLo = -2.0, xHi = 2.0;
        double spanX = xHi - xLo;
        double yLo = 0.0, yHi = 1.15, spanY = yHi - yLo;

        float padLeft = w * LEFT_PAD, padRight = w * RIGHT_PAD;
        float padTop = h * TOP_PAD, padBottom = h * BOTTOM_PAD;
        float tickSize = w * 0.024f;

        float plotX = padLeft, plotY = padTop;
        float plotW = w - padLeft - padRight, plotH = h - padTop - padBottom;
        float insetX = plotW * PLOT_INSET_X_RATIO, insetY = plotH * PLOT_INSET_Y_RATIO;
        float drawX = plotX + insetX, drawY = plotY + insetY;
        float drawW = plotW - insetX * 2f, drawH = plotH - insetY * 2f;

        // 数据映射（4 个方案共用，保证曲线位置一致）
        final float fDrawX = drawX, fDrawW = drawW, fDrawY = drawY, fDrawH = drawH;

        // 网格
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (double t : new double[]{-2, 0, 2}) {
            float px = fDrawX + (float) ((t - xLo) / spanX * fDrawW);
            g.drawLine((int) px, (int) fDrawY, (int) px, (int) (fDrawY + fDrawH));
        }
        for (double t : new double[]{0.35, 1.0}) {
            float py = fDrawY + fDrawH - (float) ((t - yLo) / spanY * fDrawH);
            g.drawLine((int) fDrawX, (int) py, (int) (fDrawX + fDrawW), (int) py);
        }

        // markLine ±B/2
        g.setColor(MARK);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{w * 0.005f, w * 0.004f}, 0f));
        for (double t : new double[]{-2, 2}) {
            float px = fDrawX + (float) ((t - xLo) / spanX * fDrawW);
            g.drawLine((int) px, (int) fDrawY, (int) px, (int) (fDrawY + fDrawH));
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
            float px = fDrawX + (float) ((x - xLo) / spanX * fDrawW);
            float py = fDrawY + fDrawH - (float) ((y - yLo) / spanY * fDrawH);
            if (!started) { path.moveTo(px, py); started = true; } else { path.lineTo(px, py); }
        }
        g.draw(path);

        // 轴线（画在曲线之上，保证「明显」）
        float axisBottom = fDrawY + fDrawH;
        switch (variant) {
            case 0: { // A 现状
                g.setColor(AXIS);
                g.setStroke(new BasicStroke(1f));
                g.drawLine((int) fDrawX, (int) axisBottom, (int) (fDrawX + fDrawW), (int) axisBottom);
                g.drawLine((int) fDrawX, (int) fDrawY, (int) fDrawX, (int) axisBottom);
                break;
            }
            case 1: { // B 轴线延伸
                float ext = w * 0.045f;   // 左右各伸出
                float extUp = h * 0.045f, extDown = h * 0.020f;
                g.setColor(AXIS_STRONG);
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.drawLine((int) (fDrawX - ext), (int) axisBottom, (int) (fDrawX + fDrawW + ext), (int) axisBottom);
                g.drawLine((int) fDrawX, (int) (fDrawY - extUp), (int) fDrawX, (int) (axisBottom + extDown));
                // 箭头
                drawArrowX(g, fDrawX + fDrawW + ext, axisBottom, w * 0.011f);
                drawArrowX(g, fDrawX - ext, axisBottom, -w * 0.011f);
                drawArrowY(g, fDrawX, fDrawY - extUp, h * 0.013f);
                break;
            }
            case 2: { // C 完整坐标轴：穿过原点
                float ox = fDrawX + (float) ((0 - xLo) / spanX * fDrawW);
                float oy = axisBottom;
                float ext = w * 0.045f;
                g.setColor(AXIS_STRONG);
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.drawLine((int) (fDrawX - ext), (int) oy, (int) (fDrawX + fDrawW + ext), (int) oy);
                g.drawLine((int) ox, (int) (fDrawY - h * 0.045f), (int) ox, (int) (oy + h * 0.020f));
                drawArrowX(g, fDrawX + fDrawW + ext, oy, w * 0.011f);
                drawArrowY(g, ox, fDrawY - h * 0.045f, h * 0.013f);
                g.setFont(new Font("SansSerif", Font.ITALIC, Math.round(tickSize * 1.15f)));
                g.drawString("x", (int) (fDrawX + fDrawW + ext) - 4, (int) oy + Math.round(tickSize * 1.1f));
                g.drawString("y", (int) ox - Math.round(tickSize * 1.5f), (int) (fDrawY - h * 0.045f) + 4);
                break;
            }
            case 3: { // D 浅色边框
                g.setColor(AXIS);
                g.setStroke(new BasicStroke(1.2f));
                g.drawRect((int) fDrawX, (int) fDrawY, (int) fDrawW, (int) fDrawH);
                break;
            }
        }

        // x 轴刻度
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(tickSize)));
        FontMetrics xfm = g.getFontMetrics(g.getFont());
        String[] labels = {"\u2212B/2", "O", "B/2"};
        double[] pos = {-2, 0, 2};
        g.setColor(SUBTEXT);
        for (int i = 0; i < pos.length; i++) {
            float cx = fDrawX + (float) ((pos[i] - xLo) / spanX * fDrawW);
            g.drawString(labels[i], cx - xfm.stringWidth(labels[i]) / 2f, axisBottom + h * 0.052f);
        }
        // y 轴刻度
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.round(w * 0.0152f)));
        String yt = "N\u2080(2\u03c0f_c)\u00b2";
        g.drawString(yt, fDrawX - w * 0.010f - g.getFontMetrics().stringWidth(yt),
                fDrawY + fDrawH - (float) ((0.35 - yLo) / spanY * fDrawH) + w * 0.0152f * 0.36f);

        g.dispose();
        return img;
    }

    static void drawArrowX(Graphics2D g, float tipX, float y, float len) {
        g.drawLine((int) tipX, (int) y, (int) (tipX - len), (int) (y - len * 0.5f));
        g.drawLine((int) tipX, (int) y, (int) (tipX - len), (int) (y + len * 0.5f));
    }

    static void drawArrowY(Graphics2D g, float x, float tipY, float len) {
        g.drawLine((int) x, (int) tipY, (int) (x - len * 0.5f), (int) (tipY + len));
        g.drawLine((int) x, (int) tipY, (int) (x + len * 0.5f), (int) (tipY + len));
    }
}
