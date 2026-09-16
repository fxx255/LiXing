import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 坐标轴箭头**局部放大**预览：上排看整体布局，下排放大轴末端看箭头细节。
 *
 * 与 PlotBitmapRenderer 的常量保持一致（改那边记得同步这里）：
 *   AXIS_OVERHANG_L_RATIO = 0.012 / AXIS_OVERHANG_R_RATIO = 0.030
 *   AXIS_VERT_OVERHANG_RATIO = 0.10 / AXIS_VERT_BELOW_RATIO = 0.012
 *   AXIS_ARROW_X_LEN_RATIO = 0.011 / AXIS_ARROW_Y_LEN_RATIO = 0.013
 *   AXIS_ARROW_HALF_RATIO = 0.5
 */
public class PlotArrowZoom {

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color AXIS = new Color(0x4A4A4F);
    static final Color GRID = new Color(0x2B2B2F);
    static final Color SERIES = new Color(0x5AA9FF);
    static final Color MARK = new Color(0x6A6A70);
    static final Color HOT = new Color(0xFF9F0A);

    static final float LEFT_PAD = 0.082f, RIGHT_PAD = 0.028f, TOP_PAD = 0.085f;
    static final float BOTTOM_PAD = 0.125f;
    static final float PLOT_INSET_X = 0.2368f, PLOT_INSET_Y = 0.03f;
    static final float OVER_L = 0.012f, OVER_R = 0.030f;
    static final float VERT_OVER = 0.10f, VERT_BELOW = 0.012f;
    static final float ARROW_X_LEN = 0.011f, ARROW_Y_LEN = 0.013f, ARROW_HALF = 0.5f;

    static int CELL_W = 993, CELL_H = 575;

    public static void main(String[] args) throws Exception {
        int totalW = 1000 * 2, totalH = 700 + 700;
        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, totalW, totalH);

        // 上排：整体（左=改动前小刻度，右=改动后箭头）
        drawCell(g, 0, 0, CELL_W, CELL_H, "A  改动前：端点小刻度", false);
        drawCell(g, 1000, 0, CELL_W, CELL_H, "B  改动后：轴端箭头", true);

        // 下排：把两个轴末端按 3.5 倍放大画出来
        drawZoom(g, 0, 700, 1000, 700, "C  x 轴右端放大 3.5x（上=小刻度 / 下=箭头）", false);
        drawZoom(g, 1000, 700, 1000, 700, "D  y 轴顶端放大 3.5x（上=小刻度 / 下=箭头）", true);

        g.dispose();
        ImageIO.write(img, "png", new File("plot-arrow-zoom.png"));
        System.out.println("已写出 plot-arrow-zoom.png");
    }

    static void drawCell(Graphics2D g, int ox, int oy, int W, int H, String title, boolean arrow) {
        g.setColor(BG);
        g.fillRect(ox, oy, W, H);

        float padL = W * LEFT_PAD, padR = W * RIGHT_PAD;
        float padT = H * TOP_PAD, padB = H * BOTTOM_PAD;
        float plotX = padL, plotY = padT;
        float plotW = W - padL - padR, plotH = H - padT - padB;
        float drawX = plotX + plotW * PLOT_INSET_X;
        float drawWr = plotW * (1 - 2 * PLOT_INSET_X);
        float drawY = plotY + plotH * PLOT_INSET_Y;
        float drawH = plotH * (1 - 2 * PLOT_INSET_Y);

        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            float y = drawY + drawH * i / 4f;
            g.drawLine((int) drawX, (int) y, (int) (drawX + drawWr), (int) y);
        }

        g.setColor(SERIES);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double fLo = -2, fHi = 2;
        double yMax = 4 * Math.PI * Math.PI * (4 + 0.25);
        float[] xs = new float[300], ys = new float[300];
        for (int i = 0; i < 300; i++) {
            double f = fLo + (fHi - fLo) * i / 299.0;
            double v = 4 * Math.PI * Math.PI * (f * f + 0.25);
            xs[i] = (float) (drawX + drawWr * (f - fLo) / (fHi - fLo));
            ys[i] = (float) (drawY + drawH * (1 - v / (yMax * 3)));
        }
        for (int i = 0; i < 299; i++) {
            g.drawLine((int) xs[i], (int) ys[i], (int) xs[i + 1], (int) ys[i + 1]);
        }

        g.setColor(MARK);
        g.setStroke(new BasicStroke(1.2f));
        for (int s = -1; s <= 1; s += 2) {
            float x = (float) (drawX + drawWr * ((s * 2.0) - fLo) / (fHi - fLo));
            g.drawLine((int) x, (int) (drawY + drawH * 0.4f), (int) x, (int) (drawY + drawH));
        }

        g.setColor(AXIS);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float axisY = drawY + drawH;
        float axisLeft = plotX - W * OVER_L;
        float axisRight = plotX + plotW + W * OVER_R;
        g.drawLine((int) axisLeft, (int) axisY, (int) axisRight, (int) axisY);

        float yAxisX = drawX;
        float yAxisTop = drawY - plotH * VERT_OVER;
        g.drawLine((int) yAxisX, (int) yAxisTop, (int) yAxisX, (int) (axisY + H * VERT_BELOW));

        if (arrow) {
            drawArrowX(g, axisRight, axisY, W * ARROW_X_LEN, +1);
            drawArrowY(g, yAxisX, yAxisTop, H * ARROW_Y_LEN, -1);
        } else {
            g.drawLine((int) axisRight, (int) axisY, (int) axisRight, (int) (axisY - H * 0.014f));
            g.drawLine((int) yAxisX, (int) yAxisTop, (int) (yAxisX + W * 0.010f), (int) yAxisTop);
        }

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString("f", (int) (axisRight + W * 0.006f), (int) (axisY + H * 0.030f + 7));
        g.drawString("S(f)", (int) (yAxisX - W * 0.055f), (int) (yAxisTop - 8));

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString(title, ox + 24, oy + 40);
    }

    /** 把 x 轴右端 / y 轴顶端按 3.5 倍放大，上下并列对比小刻度与箭头。 */
    static void drawZoom(Graphics2D g, int ox, int oy, int W, int H, String title, boolean vertical) {
        g.setColor(BG);
        g.fillRect(ox, oy, W, H);

        float zoom = 3.5f;
        float arrowLen = vertical ? CELL_H * ARROW_Y_LEN : CELL_W * ARROW_X_LEN;
        float arrowHalf = arrowLen * ARROW_HALF;
        float tickLen = vertical ? CELL_W * 0.010f : CELL_H * 0.014f;

        // 两行：0=小刻度，1=箭头
        for (int row = 0; row < 2; row++) {
            float cy = oy + 120 + row * 280;
            float cx = ox + (vertical ? W * 0.42f : W * 0.60f);

            g.setColor(AXIS);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            if (vertical) {
                // 画一段竖轴线，顶端在 cy 处
                g.drawLine((int) cx, (int) cy, (int) cx, (int) (cy + 200));
            } else {
                // 画一段横轴线，右端在 cx 处
                g.drawLine((int) (cx - 300), (int) cy, (int) cx, (int) cy);
            }

            g.setColor(HOT);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            if (row == 0) {
                // 原样式：单条端点小刻度
                if (vertical) {
                    g.drawLine((int) cx, (int) cy, (int) (cx + tickLen * zoom), (int) cy);
                } else {
                    g.drawLine((int) cx, (int) cy, (int) cx, (int) (cy - tickLen * zoom));
                }
                drawLabel(g, ox, cy, vertical, "小刻度 " + (int) (vertical ? tickLen : tickLen) + "px");
            } else {
                // 新样式：两条斜线构成箭头
                if (vertical) {
                    g.drawLine((int) cx, (int) cy,
                            (int) (cx - arrowHalf * zoom), (int) (cy + arrowLen * zoom));
                    g.drawLine((int) cx, (int) cy,
                            (int) (cx + arrowHalf * zoom), (int) (cy + arrowLen * zoom));
                } else {
                    g.drawLine((int) cx, (int) cy,
                            (int) (cx - arrowLen * zoom), (int) (cy - arrowHalf * zoom));
                    g.drawLine((int) cx, (int) cy,
                            (int) (cx - arrowLen * zoom), (int) (cy + arrowHalf * zoom));
                }
                drawLabel(g, ox, cy, vertical,
                        String.format("箭头 臂%.0fpx 张%.0fpx 半角26.6°",
                                arrowLen * zoom, arrowHalf * 2 * zoom));
            }
        }

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString(title, ox + 24, oy + 40);
    }

    static void drawLabel(Graphics2D g, int ox, float cy, boolean vertical, String s) {
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.PLAIN, 19));
        g.drawString(s, ox + 60, (int) cy + (vertical ? 30 : 40));
    }

    static void drawArrowX(Graphics2D g, float tipX, float y, float len, int dir) {
        float half = len * ARROW_HALF;
        g.drawLine((int) tipX, (int) y, (int) (tipX - len * dir), (int) (y - half));
        g.drawLine((int) tipX, (int) y, (int) (tipX - len * dir), (int) (y + half));
    }

    static void drawArrowY(Graphics2D g, float x, float tipY, float len, int dir) {
        float half = len * ARROW_HALF;
        g.drawLine((int) x, (int) tipY, (int) (x - half), (int) (tipY - len * dir));
        g.drawLine((int) x, (int) tipY, (int) (x + half), (int) (tipY - len * dir));
    }
}
