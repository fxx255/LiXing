"""把参考图加网格 + 标注，方便人眼读出精确比例。"""
from PIL import Image, ImageDraw, ImageFont

P = r'D:\Tencent\xwechat_files\wxid_x54ticcbnj9422_9fde\temp\RWTemp\2026-09\9e20f478899dc29eb19741386f9343c8\c54f87d424fe9e0f30e731a9d8730467.png'
im = Image.open(P).convert('RGB')
W, H = im.size
g = im.copy()
d = ImageDraw.Draw(g)

# 每 5% 一条线，10% 加粗标注
for i in range(1, 20):
    x = int(W * i / 20)
    pct = i * 5
    col = (255, 60, 60) if pct % 10 == 0 else (255, 180, 180)
    d.line([(x, 0), (x, H)], fill=col, width=1)
    if pct % 10 == 0:
        d.text((x + 2, 4), str(pct), fill=(255, 0, 0))
for j in range(1, 20):
    y = int(H * j / 20)
    pct = j * 5
    col = (60, 120, 255) if pct % 10 == 0 else (170, 200, 255)
    d.line([(0, y), (W, y)], fill=col, width=1)
    if pct % 10 == 0:
        d.text((3, y + 2), str(pct), fill=(0, 80, 255))

g.save('ref_grid.png')
print(f"written ref_grid.png {W}x{H}")
print(f"aspect W/H = {W/H:.3f}")
print()
print("读图提示：")
print("  x 轴横线 y ≈ 516/624 = 82.7%")
print("  -B/2 竖线 x ≈ 265/1017 = 26.1%")
print("  O   竖线 x ≈ 488/1017 = 48.0%")
print("  B/2 竖线 x ≈ 707/1017 = 69.5%")
print("  曲线顶点 y ≈ 258/624 = 41.3% （左右两端）")
print("  曲线最低点 y ≈ 300/624 = 48% 附近")
