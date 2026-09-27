from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

ROOT = Path(__file__).resolve().parent
FONT = Path(r"C:\Windows\Fonts\msyh.ttc")


def read_layout(path):
    rows = [line.rstrip("\n").split("\t") for line in path.read_text(encoding="utf-8").splitlines()]
    _, width, height, title = rows[0]
    edges = []
    nodes = []
    for row in rows[1:]:
        if row[0] == "EDGE":
            edges.append((row[1], row[2], row[3] == "true", [tuple(map(float, point.split(","))) for point in row[4].split(";")]))
        elif row[0] == "NODE":
            nodes.append((row[1], row[2], *map(float, row[3:7]), row[7].replace(r"\n", "\n")))
    return float(width), float(height), title, edges, nodes


def dashed_line(draw, a, b, ink, width=2):
    dx, dy = b[0] - a[0], b[1] - a[1]
    length = math.hypot(dx, dy)
    if length == 0:
        return
    cursor = 0
    while cursor < length:
        end = min(length, cursor + 8)
        draw.line([(a[0] + dx * cursor / length, a[1] + dy * cursor / length),
                   (a[0] + dx * end / length, a[1] + dy * end / length)], fill=ink, width=width)
        cursor += 14


def render(source, destination, dark=False):
    width, height, title, edges, nodes = read_layout(source)
    scale = min(1.0, 1500 / width)
    size = (round(width * scale), round(height * scale))
    bg = "#1c1c1e" if dark else "#ffffff"
    ink = "#c9c9ce" if dark else "#202b38"
    muted = "#8e8e93" if dark else "#536274"
    image = Image.new("RGB", size, bg)
    draw = ImageDraw.Draw(image)
    title_font = ImageFont.truetype(str(FONT), round(23 * scale))
    block_font = ImageFont.truetype(str(FONT), round(20 * scale))
    io_font = ImageFont.truetype(str(FONT), round(18 * scale))
    draw.text((28 * scale, 19 * scale), title, font=title_font, fill=ink)

    def pt(point):
        return (round(point[0] * scale), round(point[1] * scale))

    for _, _, dashed, points in edges:
        points = [pt(p) for p in points]
        for a, b in zip(points, points[1:]):
            if dashed:
                dashed_line(draw, a, b, muted, max(1, round(2 * scale)))
            else:
                draw.line((a, b), fill=ink, width=max(1, round(2 * scale)))
        if len(points) >= 2:
            end = points[-1]
            previous = next((p for p in reversed(points[:-1]) if p != end), None)
            if previous:
                dx, dy = end[0] - previous[0], end[1] - previous[1]
                length = math.hypot(dx, dy)
                ux, uy = dx / length, dy / length
                side = (-uy, ux)
                arrow = [end,
                         (end[0] - ux * 11 * scale + side[0] * 4.5 * scale,
                          end[1] - uy * 11 * scale + side[1] * 4.5 * scale),
                         (end[0] - ux * 11 * scale - side[0] * 4.5 * scale,
                          end[1] - uy * 11 * scale - side[1] * 4.5 * scale)]
                draw.polygon(arrow, fill=muted if dashed else ink)

    # Dashed feedback passes behind solid control feeds. A small break makes
    # these crossings visibly different from a connected junction.
    for _, _, dashed, dashed_points in edges:
        if not dashed:
            continue
        for da, db in zip(dashed_points, dashed_points[1:]):
            for _, _, solid, solid_points in edges:
                if solid:
                    continue
                for sa, sb in zip(solid_points, solid_points[1:]):
                    dh = da[1] == db[1]
                    sh = sa[1] == sb[1]
                    if dh == sh:
                        continue
                    x = sa[0] if dh else da[0]
                    y = da[1] if dh else sa[1]
                    h1, h2 = (da[0], db[0]) if dh else (sa[0], sb[0])
                    v1, v2 = (sa[1], sb[1]) if dh else (da[1], db[1])
                    if not (min(h1, h2) + 6 < x < max(h1, h2) - 6 and
                            min(v1, v2) + 6 < y < max(v1, v2) - 6):
                        continue
                    px, py = pt((x, y))
                    radius = max(3, round(5 * scale))
                    draw.ellipse((px - radius, py - radius, px + radius, py + radius), fill=bg)
                    if sh:
                        draw.line(((px - radius - 1, py), (px + radius + 1, py)), fill=ink,
                                  width=max(1, round(2 * scale)))
                    else:
                        draw.line(((px, py - radius - 1), (px, py + radius + 1)), fill=ink,
                                  width=max(1, round(2 * scale)))
                    # Bridge the dashed feedback wire with a small connected
                    # semicircle, keeping the same diameter as the old gap.
                    arc_box = (px - radius, py - radius, px + radius, py + radius)
                    if dh:
                        draw.arc(arc_box, 180, 360, fill=muted,
                                 width=max(1, round(2 * scale)))
                    else:
                        draw.arc(arc_box, 270, 450, fill=muted,
                                 width=max(1, round(2 * scale)))

    for _, shape, x, y, w, h, label in nodes:
        rect = (round(x * scale), round(y * scale), round((x + w) * scale), round((y + h) * scale))
        if shape == "BLOCK":
            draw.rounded_rectangle(rect, radius=round(9 * scale), fill=bg, outline=ink, width=max(1, round(2 * scale)))
        elif shape in ("MIXER", "SUM"):
            draw.ellipse(rect, fill=bg, outline=ink, width=max(1, round(2 * scale)))
            if shape == "MIXER":
                label = "×"
        elif shape == "JUNCTION":
            draw.ellipse(rect, fill=ink)
            continue
        font = io_font if shape == "IO" else block_font
        if shape == "BLOCK":
            size_px = round(20 * scale)
            while size_px > round(12 * scale) and any(
                    draw.textbbox((0, 0), line, font=font)[2] > (w - 10) * scale
                    for line in label.split("\n")):
                size_px -= 1
                font = ImageFont.truetype(str(FONT), size_px)
        cx, cy = (x + w / 2) * scale, (y + h / 2) * scale
        lines = label.split("\n")
        line_height = round(26 * scale)
        for index, line in enumerate(lines):
            draw.text((cx, cy + (index - (len(lines) - 1) / 2) * line_height), line,
                      font=font, fill=ink, anchor="mm")

    image.save(destination, "WEBP", quality=78, method=6)
    print(destination.name, image.size, destination.stat().st_size)


render(ROOT / "qpsk.layout", ROOT / "qpsk_light.webp")
render(ROOT / "qam16.layout", ROOT / "qam16_dark.webp", dark=True)
