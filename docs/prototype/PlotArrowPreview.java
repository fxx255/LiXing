import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 坐标轴箭头预览：验证把「端点小刻度」换成箭头后的观感与比例。
 *
 * 与 PlotBitmapRenderer 的常量保持一致（改那边记得同步这里）：
 *   AXIS_OVERHANG_L_RATIO = 0.012 / AXIS_OVERHANG_R_RATIO = 0.030
 *   AXIS_VERT_OVERHANG_RATIO = 0.10 / AXIS_VERT_BELOW_RATIO = 0.012
 *   AXIS_ARROW_X_LEN_RATIO = 0.011 / AXIS_ARROW_Y_LEN_RATIO = 0.013
 *   AXIS_ARROW_HALF_RATIO = 0.5
 *
 * 出 3 格对比：
 *   1. 改动前：端点小刻度（原样式）
 *   2. 改动后：箭头，标准画布（约 993×575）
 *   3. 改动后：箭头，极端宽画布（1400×575）——检查纵向箭头是否被压扁
 */
public class PlotArrowPreview {

    static final Color BG = new Color(0x1C1C1E);
    static final Color TEXT = new Color(0xC9C9CE);
    static final Color AXIS = new Color(0x4A4A4F);
    static final Color GRID = new Color(0x2B2B2F);
    static final Color SERIES = new Color(0x5AA9FF);
    static final Color MARK = new Color(0x6A6A70);

    // 与渲染器一致的布局常量
    static final float LEFT_PAD = 0.082f, RIGHT_PAD = 0.028f, TOP_PAD = 0.085f;
    static final float BOTTOM_PAD = 0.125f;
    static final float PLOT_INSET_X = 0.2368f;
    static final float PLOT_INSET_Y = 0.03f;
    static final float OVER_L = 0.012f, OVER_R = 0.030f;
    static final float VERT_OVER = 0.10f, VERT_BELOW = 0.012f;
    static final float ARROW_X_LEN = 0.011f, ARROW_Y_LEN = 0.013f, ARROW_HALF = 0.5f;

    public static void main(String[] args) throws Exception {
        int w = 1000, h = 660;
        BufferedImage img = new BufferedImage(w * 3, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int cellW = 993, cellH = 575;
        g.setColor(BG);
        g.fillRect(0, 0, w * 3, h);

        drawCell(g, 0, 0, cellW, cellH, "1  改动前：端点小刻度", false);
        drawCell(g, 1000, 0, cellW, cellH, "2  改动后：箭头（标准 W:H）", true);
        drawCell(g, 2000, 0, 1400, cellH, "3  改动后：箭头（宽画布，查变形）", true);

        // 打印关键几何量，便于核对
        float aw = cellW * ARROW_X_LEN, ah = cellH * ARROW_Y_LEN;
        System.out.printf("标准画布 %dx%d：横箭臂 %.1fpx（张开 %.1fpx），纵箭臂 %.1fpx（张开 %.1fpx）%n",
                cellW, cellH, aw, aw * ARROW_HALF, ah, ah * ARROW_HALF);
        float aw2 = 1400 * ARROW_X_LEN;
        System.out.printf("宽画布   1400x%d：横箭臂 %.1fpx，纵箭臂 %.1fpx（与标准画布相同 ⇒ 无变形）%n",
                cellH, aw2, ah);
        System.out.printf("张开半角 = atan(%.2f) = %.1f°%n", ARROW_HALF, Math.toDegrees(Math.atan(ARROW_HALF)));

        g.dispose();
        File out = new File("plot-arrow-preview.png");
        ImageIO.write(img, "png", out);
        System.out.println("已写出 " + out.getPath());
    }

    static void drawCell(Graphics2D g, int ox, int oy, int W, int H, String title, boolean arrow) {
        g.setColor(BG);
        g.fillRect(ox, oy, W, H);

        // 布局
        float padL = W * LEFT_PAD, padR = W * RIGHT_PAD;
        float padT = H * TOP_PAD, padB = H * BOTTOM_PAD;
        float plotX = padL, plotY = padT;
        float plotW = W - padL - padR, plotH = H - padT - padB;
        float drawX = plotX + plotW * PLOT_INSET_X;
        float drawWr = plotW * (1 - 2 * PLOT_INSET_X);
        float drawY = plotY + plotH * PLOT_INSET_Y;
        float drawH = plotH * (1 - 2 * PLOT_INSET_Y);

        // 网格
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            float y = drawY + drawH * i / 4f;
            g.drawLine((int) drawX, (int) y, (int) (drawX + drawWr), (int) y);
        }

        // 曲线：y = 4π²(f² + 0.25)，f ∈ [-2,2]
        g.setColor(SERIES);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double fLo = -2, fHi = 2;
        double yMax = 4 * Math.PI * Math.PI * (4 + 0.25);
        float[] xs = new float[200], ys = new float[200];
        for (int i = 0; i < 200; i++) {
            double f = fLo + (fHi - fLo) * i / 199.0;
            double v = 4 * Math.PI * Math.PI * (f * f + 0.25);
            xs[i] = (float) (drawX + drawWr * (f - fLo) / (fHi - fLo));
            // y 范围取 0..3×峰值（提示词的 ×3 留白规则）
            ys[i] = (float) (drawY + drawH * (1 - v / (yMax * 3)));
        }
        for (int i = 0; i < 199; i++) {
            g.drawLine((int) xs[i], (int) ys[i], (int) xs[i + 1], (int) ys[i + 1]);
        }

        // 定义域边界竖线
        g.setColor(MARK);
        g.setStroke(new BasicStroke(1.2f));
        for (int s = -1; s <= 1; s += 2) {
            float x = (float) (drawX + drawWr * ((s * 2.0) - fLo) / (fHi - fLo));
            g.drawLine((int) x, (int) (drawY + drawH * 0.35f), (int) x, (int) (drawY + drawH));
        }

        // 坐标轴
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float axisY = drawY + drawH;
        float axisLeft = plotX - W * OVER_L;
        float axisRight = plotX + plotW + W * OVER_R;
        g.drawLine((int) axisLeft, (int) axisY, (int) axisRight, (int) axisY);

        float yAxisX = drawX; // 简化：竖轴画在绘图区左侧
        float yAxisTop = drawY - plotH * VERT_OVER;
        float yAxisBottom = axisY + H * VERT_BELOW;
        g.drawLine((int) yAxisX, (int) yAxisTop, (int) yAxisX, (int) yAxisBottom);

        if (arrow) {
            drawArrowX(g, axisRight, axisY, W * ARROW_X_LEN, +1);
            drawArrowY(g, yAxisX, yAxisTop, H * ARROW_Y_LEN, -1);
        } else {
            // 原样式：端点小刻度
            g.drawLine((int) axisRight, (int) axisY, (int) axisRight, (int) (axisY - H * 0.014f));
            g.drawLine((int) yAxisX, (int) yAxisTop, (int) (yAxisX + W * 0.010f), (int) yAxisTop);
        }

        // 轴名
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.drawString("f", (int) (axisRight + W * 0.006f), (int) (axisY + H * 0.030f + 8));
        g.drawString("S(f)", (int) (yAxisX - W * 0.055f), (int) (yAxisTop - 8));

        // 标题
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString(title, ox + 24, oy + 34);
    }

    /** 与 PlotBitmapRenderer.drawAxisArrowX 一致（两条短斜线成 `>`）。 */
    static void drawArrowX(Graphics2D g, float tipX, float y, float len, int dir) {
        float half = len * ARROW_HALF;
        g.drawLine((int) tipX, (int) y, (int) (tipX - len * dir), (int) (y - half));
        g.drawLine((int) tipX, (int) y, (int) (tipX - len * dir), (int) (y + half));
    }

    /** 与 PlotBitmapRenderer.drawAxisArrowY 一致（两条短斜线成 `^`）。 */
    static void drawArrowY(Graphics2D g, float x, float tipY, float len, int dir) {
        float half = len * ARROW_HALF;
        g.drawLine((int) x, (int) tipY, (int) (x - half), (int) (tipY - len * dir));
        g.drawLine((int) x, (int) tipY, (int) (x + half), (int) (tipY - len * dir));
    }
}
