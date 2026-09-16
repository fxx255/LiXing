import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 参考图 vs 复刻版 上下叠放对比：把教材参考图缩放到与复刻版同宽。
 *
 * 两种模式：
 *   1) 默认 —— 上下两张图，各自独立看；
 *   2) `--guide` —— 在两张图上叠同一条**百分比参考网格**（竖线在 0/26/48/70/100%，
 *      横线在 10.9/22/78.4%），用来核对关键几何位置是否真的对齐了参考图。
 *
 * 用网格而非直接叠加是因为参考图是手机翻拍，有透视畸变与背面透印文字，
 * 像素级对齐本身就不成立；能对齐的只有**归一化后的比例**。
 */
public class PlotRefCompare {

    static final float[] V_LINES = {0f, 0.26f, 0.48f, 0.70f, 1f};
    static final String[] V_NAMES = {"0%", "-B/2", "O", "B/2", "100%"};
    static final float[] H_LINES = {0.109f, 0.22f, 0.784f};
    static final String[] H_NAMES = {"O竖线顶 10.9%", "曲线两端 22%", "x轴 78.4%"};

    public static void main(String[] args) throws Exception {
        boolean guide = args.length > 0 && args[0].equals("--guide");
        String refPath = args.length > 1 ? args[1]
                : "D:/Tencent/xwechat_files/wxid_x54ticcbnj9422_9fde/temp/RWTemp/2026-09/9e20f478899dc29eb19741386f9343c8/c54f87d424fe9e0f30e731a9d8730467.png";
        BufferedImage ref = ImageIO.read(new File(refPath));

        int W = 1240;
        int refH = Math.round(ref.getHeight() * W / (float) ref.getWidth());
        int mineH = Math.round(W * 0.66f);

        BufferedImage mine = new BufferedImage(W, mineH, BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = mine.createGraphics();
        PlotRefFinal.aa(mg);
        PlotRefFinal.draw(mg, W, mineH, true);
        mg.dispose();

        if (guide) {
            drawGuide(ref);
            drawGuide(mine);
        }

        int gap = 16, header = 42, footer = 10;
        int H = header + (refH + 34) + gap + (mineH + 34) + footer;
        BufferedImage sheet = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        PlotRefFinal.aa(g);
        g.setColor(new Color(0x0E0E10));
        g.fillRect(0, 0, W, H);

        g.setFont(new Font("SansSerif", Font.BOLD, 21));
        g.setColor(new Color(0xE8E8EE));
        g.drawString(guide ? "GEOMETRY CHECK — same width, percentage guides"
                        : "REFERENCE (textbook)  vs  REPRODUCTION  —  same width",
                8, 27);

        int y1 = header;
        g.drawImage(ref, 0, y1, W, refH, null);
        g.setColor(new Color(0xFF6B6B));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(0, y1, W - 1, refH - 1);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(new Color(0xFFB0B0));
        g.drawString("REFERENCE (textbook scan, " + ref.getWidth() + "x" + ref.getHeight() + ")", 8, y1 + refH + 20);

        int y2 = y1 + refH + 34;
        g.drawImage(mine, 0, y2, null);
        g.setColor(new Color(0x3DDC97));
        g.drawRect(0, y2, W - 1, mineH - 1);
        g.setColor(new Color(0x8BF0C0));
        g.drawString("REPRODUCTION (PlotBitmapRenderer, " + W + "x" + mineH + ")", 8, y2 + mineH + 20);

        g.dispose();
        String out = guide ? "plot-ref-guide.png" : "plot-ref-compare.png";
        ImageIO.write(sheet, "png", new File(out));
        System.out.println("written " + out + "  " + W + "x" + H);
    }

    static void drawGuide(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        PlotRefFinal.aa(g);
        int w = img.getWidth(), h = img.getHeight();
        g.setStroke(new BasicStroke(Math.max(1.4f, w / 700f)));

        g.setColor(new Color(0x00E5FF));
        for (float v : V_LINES) {
            int x = Math.round(v * (w - 1));
            g.drawLine(x, 0, x, h - 1);
        }
        g.setColor(new Color(0xFFD400));
        for (float v : H_LINES) {
            int y = Math.round(v * (h - 1));
            g.drawLine(0, y, w - 1, y);
        }

        g.setFont(new Font("SansSerif", Font.BOLD, Math.round(w * 0.016f)));
        for (int i = 0; i < V_LINES.length; i++) {
            int x = Math.round(V_LINES[i] * (w - 1));
            String s = V_NAMES[i];
            int tw = g.getFontMetrics().stringWidth(s);
            int tx = Math.max(2, Math.min(w - tw - 2, x - tw / 2));
            g.setColor(new Color(0x00232B));
            g.fillRect(tx - 2, 2, tw + 4, g.getFontMetrics().getHeight());
            g.setColor(new Color(0x00E5FF));
            g.drawString(s, tx, 2 + g.getFontMetrics().getAscent());
        }
        for (int i = 0; i < H_LINES.length; i++) {
            int y = Math.round(H_LINES[i] * (h - 1));
            String s = H_NAMES[i];
            g.setColor(new Color(0x2B2200));
            g.fillRect(w - g.getFontMetrics().stringWidth(s) - 8, y + 2,
                    g.getFontMetrics().stringWidth(s) + 6, g.getFontMetrics().getHeight());
            g.setColor(new Color(0xFFD400));
            g.drawString(s, w - g.getFontMetrics().stringWidth(s) - 5, y + 2 + g.getFontMetrics().getAscent());
        }
        g.dispose();
    }
}
