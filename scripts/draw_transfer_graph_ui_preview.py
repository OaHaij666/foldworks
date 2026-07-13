from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "client-ui" / "previews" / "transfer-graph-ui-preview-v3.png"
CONTROLS_OUT = ROOT / "docs" / "client-ui" / "previews" / "transfer-graph-controls-preview-v1.png"
SCALE = 3
W, H = 1280, 720

BG = (253, 254, 255, 255)
GRID_MAJOR = (206, 229, 244, 255)
GRID_MINOR = (231, 243, 251, 255)
SURFACE = (255, 255, 255, 245)
SURFACE_SOFT = (250, 253, 255, 245)
INK = (58, 70, 84, 255)
MUTED = (128, 145, 160, 255)
FAINT = (172, 184, 195, 255)
BLUE = (92, 165, 226, 255)
BLUE_SOFT = (229, 245, 255, 255)
BLUE_PALE = (239, 250, 255, 255)
PINK = (255, 143, 169, 255)
PINK_SOFT = (255, 235, 241, 255)
GREEN = (76, 184, 127, 255)
CYAN = (73, 181, 198, 255)
GOLD = (224, 174, 66, 255)
PURPLE = (176, 117, 199, 255)
RED = (225, 97, 105, 255)
BORDER = (205, 218, 228, 255)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in (
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/YuGothM.ttc",
        "C:/Windows/Fonts/arial.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return ImageFont.load_default()


F12 = font(12 * SCALE)
F14 = font(14 * SCALE)
F16 = font(16 * SCALE)
F18 = font(18 * SCALE)


def s(v: int | float) -> int:
    return round(v * SCALE)


def xy(x: int, y: int, w: int, h: int) -> tuple[int, int, int, int]:
    return s(x), s(y), s(x + w), s(y + h)


def shadow(base: Image.Image, x: int, y: int, w: int, h: int, r: int, alpha: int = 42) -> None:
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(xy(x + 2, y + 3, w, h), radius=s(r), fill=(75, 98, 120, alpha))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(s(3))))


def round_rect(
    img: Image.Image,
    d: ImageDraw.ImageDraw,
    x: int,
    y: int,
    w: int,
    h: int,
    *,
    fill=SURFACE,
    outline=BORDER,
    r: int = 14,
    width: int = 1,
    cast_shadow: bool = False,
) -> None:
    if cast_shadow:
        shadow(img, x, y, w, h, r)
    d.rounded_rectangle(xy(x, y, w, h), radius=s(r), fill=fill, outline=outline, width=s(width))


def line(d: ImageDraw.ImageDraw, pts, fill, width: int = 1) -> None:
    d.line(tuple(s(v) for v in pts), fill=fill, width=s(width))


def text(d: ImageDraw.ImageDraw, value: str, x: int, y: int, fill=INK, f=F14) -> None:
    d.text((s(x), s(y)), value, fill=fill, font=f)


def text_center(d: ImageDraw.ImageDraw, value: str, cx: int, y: int, fill=INK, f=F14) -> None:
    box = d.textbbox((0, 0), value, font=f)
    d.text((s(cx) - (box[2] - box[0]) // 2, s(y)), value, fill=fill, font=f)


def text_right(d: ImageDraw.ImageDraw, value: str, x: int, y: int, fill=INK, f=F14) -> None:
    box = d.textbbox((0, 0), value, font=f)
    d.text((s(x) - (box[2] - box[0]), s(y)), value, fill=fill, font=f)


def pill(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, label: str, *, color=BLUE, fill=(255, 255, 255, 238), selected=False) -> None:
    outline = color if selected else (*color[:3], 170)
    bg = (231, 246, 255, 255) if selected and color == BLUE else (255, 242, 246, 255) if selected else fill
    round_rect(img, d, x, y, w, h, fill=bg, outline=outline, r=h // 2, width=1)
    text_center(d, label, x + w // 2, y + (h - 13) // 2, color if not selected else INK, F12)


def field(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, value: str) -> None:
    round_rect(img, d, x, y, w, h, fill=(246, 249, 252, 255), outline=(208, 222, 233, 255), r=8)
    text(d, value, x + 10, y + (h - 13) // 2, INK, F12)


def port(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, color) -> None:
    round_rect(img, d, x - 6, y - 6, 12, 12, fill=(255, 255, 255, 250), outline=(*color[:3], 160), r=6)
    d.ellipse(xy(x - 3, y - 3, 6, 6), fill=color)


def progress(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, ratio: float, color) -> None:
    round_rect(img, d, x, y, w, 8, fill=(237, 243, 247, 255), outline=(214, 226, 235, 255), r=4)
    fill_w = max(0, min(w, int(w * ratio)))
    if fill_w > 0:
        d.rounded_rectangle(xy(x, y, fill_w, 8), radius=s(4), fill=color)


def draw_grid(d: ImageDraw.ImageDraw) -> None:
    top = 0
    d.rectangle(xy(0, top, W, H - top), fill=BG)
    for x in range(36, W, 72):
        line(d, (x, top, x, H), GRID_MAJOR if (x - 36) % 288 == 0 else GRID_MINOR, 1)
    for y in range(top + 44, H, 72):
        line(d, (0, y, W, y), GRID_MAJOR if (y - top - 44) % 288 == 0 else GRID_MINOR, 1)


def chevron(d: ImageDraw.ImageDraw, x: int, y: int, color=INK, up: bool = False) -> None:
    if up:
        d.line((s(x), s(y + 5), s(x + 5), s(y), s(x + 10), s(y + 5)), fill=color, width=s(2))
        return
    d.line((s(x), s(y), s(x + 5), s(y + 5), s(x + 10), s(y)), fill=color, width=s(2))


def chevron_button(d: ImageDraw.ImageDraw, x: int, y: int, *, up: bool = False, color=BLUE) -> None:
    d.rounded_rectangle(xy(x, y, 14, 14), radius=s(5), fill=(236, 249, 255, 235), outline=(*color[:3], 185), width=s(1))
    cx = x + 7
    cy = y + 7
    if up:
        pts = ((cx - 4, cy + 2), (cx, cy - 2), (cx + 4, cy + 2))
    else:
        pts = ((cx - 4, cy - 2), (cx, cy + 2), (cx + 4, cy - 2))
    d.line(tuple((s(px), s(py)) for px, py in pts), fill=color, width=s(2), joint="curve")


def toolbar_icon(d: ImageDraw.ImageDraw, kind: str, x: int, y: int, color=INK) -> None:
    if kind == "save":
        d.rounded_rectangle(xy(x, y, 15, 15), radius=s(3), fill=(*color[:3], 34), outline=color, width=s(2))
        d.rectangle(xy(x + 3, y + 3, 9, 4), fill=color)
        d.rounded_rectangle(xy(x + 4, y + 10, 7, 4), radius=s(1), fill=(255, 255, 255, 210))
        d.rectangle(xy(x + 5, y + 11, 5, 1), fill=(*color[:3], 150))
    elif kind == "page":
        d.rounded_rectangle(xy(x + 2, y, 12, 15), radius=s(3), fill=(*color[:3], 22), outline=color, width=s(2))
        d.line((s(x + 5), s(y + 5), s(x + 11), s(y + 5)), fill=color, width=s(1))
        d.line((s(x + 5), s(y + 9), s(x + 11), s(y + 9)), fill=color, width=s(1))
    elif kind == "plus":
        d.line((s(x + 7), s(y + 2), s(x + 7), s(y + 14)), fill=color, width=s(2))
        d.line((s(x + 1), s(y + 8), s(x + 13), s(y + 8)), fill=color, width=s(2))
    elif kind == "help":
        d.ellipse(xy(x, y, 16, 16), fill=(244, 251, 255, 255), outline=color, width=s(2))
        d.arc(xy(x + 4, y + 3, 8, 8), 205, 58, fill=color, width=s(2))
        d.line((s(x + 9), s(y + 8), s(x + 8), s(y + 10)), fill=color, width=s(2))
        d.ellipse(xy(x + 7, y + 12, 2, 2), fill=color)
    elif kind == "close":
        d.line((s(x + 4), s(y + 4), s(x + 12), s(y + 12)), fill=color, width=s(2))
        d.line((s(x + 12), s(y + 4), s(x + 4), s(y + 12)), fill=color, width=s(2))


def draw_control_bar(img: Image.Image, d: ImageDraw.ImageDraw, *, status: str = "已保存", zoom_label: str = "100%", dirty: bool = False) -> None:
    x, y, h = 18, 18, 38
    w = 108
    status_color = BLUE if dirty else GREEN
    assert w <= h * 3
    round_rect(img, d, x, y, w, h, fill=(255, 255, 255, 246), outline=(213, 225, 235, 255), r=10, cast_shadow=True)

    # Compact default action bar.
    d.rounded_rectangle(xy(x + 7, y + 7, 42, 24), radius=s(8), fill=(222, 243, 255, 255), outline=BLUE, width=s(1))
    toolbar_icon(d, "save", x + 20, y + 11, BLUE)
    d.ellipse(xy(x + 58, y + 15, 8, 8), fill=status_color)
    text(d, "OK", x + 70, y + 12, status_color, F12)
    chevron(d, x + 93, y + 17, MUTED)

    # Expanded flyout preview.
    fx, fy, fw, fh = x, y + h + 8, 156, 118
    assert fw <= fh * 3
    round_rect(img, d, fx, fy, fw, fh, fill=(255, 255, 255, 248), outline=(218, 228, 237, 255), r=12, cast_shadow=True)
    d.ellipse(xy(fx + 12, fy + 16, 7, 7), fill=status_color)
    text(d, status, fx + 25, fy + 11, status_color, F12)
    chevron(d, fx + fw - 24, fy + 16, MUTED)

    field(img, d, fx + 10, fy + 36, 64, 22, "默认页")
    chevron(d, fx + 56, fy + 44, MUTED)
    field(img, d, fx + 82, fy + 36, 64, 22, "私有")
    chevron(d, fx + 128, fy + 44, MUTED)

    bx = fx + 10
    by = fy + 68
    for kind, color in (("save", BLUE), ("page", BLUE), ("plus", BLUE), ("help", BLUE)):
        round_rect(img, d, bx, by, 26, 23, fill=(255, 255, 255, 245), outline=(*color[:3], 170), r=8)
        toolbar_icon(d, kind, bx + 5, by + 4, color)
        bx += 32
    pill(img, d, fx + 10, fy + 94, 54, 18, zoom_label, color=BLUE)
    pill(img, d, fx + 84, fy + 94, 54, 18, "放弃", color=PINK)


def draw_control_grid(d: ImageDraw.ImageDraw, w: int, h: int) -> None:
    d.rectangle(xy(0, 0, w, h), fill=BG)
    for x in range(20, w, 72):
        line(d, (x, 0, x, h), GRID_MAJOR if (x - 20) % 288 == 0 else GRID_MINOR, 1)
    for y in range(20, h, 72):
        line(d, (0, y, w, y), GRID_MAJOR if (y - 20) % 288 == 0 else GRID_MINOR, 1)


def draw_controls_preview() -> Image.Image:
    logical_w, logical_h = 198, 198
    img = Image.new("RGBA", (logical_w * SCALE, logical_h * SCALE), BG)
    d = ImageDraw.Draw(img)
    draw_control_grid(d, logical_w, logical_h)
    draw_control_bar(img, d, status="未保存", zoom_label="51%", dirty=True)
    return img


def draw_edge(d: ImageDraw.ImageDraw, a, b, color, width=4) -> None:
    x0, y0 = a
    x1, y1 = b
    c1 = (x0 + 90, y0)
    c2 = (x1 - 90, y1)
    pts = []
    for i in range(40):
        t = i / 39
        mt = 1 - t
        x = mt**3 * x0 + 3 * mt**2 * t * c1[0] + 3 * mt * t**2 * c2[0] + t**3 * x1
        y = mt**3 * y0 + 3 * mt**2 * t * c1[1] + 3 * mt * t**2 * c2[1] + t**3 * y1
        pts.append((s(round(x)), s(round(y))))
    d.line(pts, fill=(*color[:3], 70), width=s(width + 5))
    d.line(pts, fill=color, width=s(width))


def icon(d: ImageDraw.ImageDraw, kind: str, x: int, y: int, color) -> None:
    if kind == "item":
        for ox, oy in ((1, 1), (10, 1), (1, 10), (10, 10)):
            d.rounded_rectangle(xy(x + ox, y + oy, 7, 7), radius=s(2), fill=(*color[:3], 230), outline=(*color[:3], 255), width=s(1))
            d.rectangle(xy(x + ox + 1, y + oy + 1, 5, 1), fill=(255, 255, 255, 175))
            d.rectangle(xy(x + ox + 1, y + oy + 5, 5, 1), fill=(*color[:3], 115))
    elif kind == "fluid":
        # Circle radius 7 centered at (9, 12); the upper sides touch the circle at its tangent points.
        d.ellipse(xy(x + 2, y + 5, 14, 14), fill=color)
        d.polygon([(s(x + 9), s(y)), (s(x + 3), s(y + 9)), (s(x + 15), s(y + 9))], fill=color)
    elif kind == "energy":
        pts = [(x + 11, y), (x + 3, y + 9), (x + 8, y + 9), (x + 4, y + 19), (x + 16, y + 6), (x + 10, y + 6)]
        d.polygon([(s(px), s(py)) for px, py in pts], fill=color)
        d.line((s(x + 10), s(y + 2), s(x + 6), s(y + 7)), fill=(255, 255, 255, 145), width=s(1))
    elif kind == "stress":
        d.ellipse(xy(x, y, 18, 18), fill=(*color[:3], 205), outline=(*color[:3], 245), width=s(1))
        for ax, ay in ((9, 2), (15, 9), (9, 15), (2, 9)):
            d.ellipse(xy(x + ax - 2, y + ay - 2, 4, 4), fill=(*color[:3], 245))
        d.ellipse(xy(x + 5, y + 5, 8, 8), fill=(255, 255, 255, 245))
        d.ellipse(xy(x + 8, y + 8, 2, 2), fill=(*color[:3], 180))


def node_icon(d: ImageDraw.ImageDraw, kind: str, x: int, y: int, accent) -> None:
    if kind == "chest":
        d.rounded_rectangle(xy(x, y + 4, 15, 12), radius=s(3), fill=(*accent[:3], 170))
        d.rectangle(xy(x + 2, y + 7, 11, 2), fill=(255, 255, 255, 185))
        d.rectangle(xy(x + 6, y + 9, 3, 4), fill=(255, 255, 255, 230))
    elif kind == "reroute":
        d.ellipse(xy(x, y + 6, 6, 6), fill=accent)
        d.ellipse(xy(x + 12, y, 6, 6), fill=accent)
        d.ellipse(xy(x + 12, y + 12, 6, 6), fill=accent)
        d.line((s(x + 6), s(y + 9), s(x + 12), s(y + 3)), fill=accent, width=s(2))
        d.line((s(x + 6), s(y + 9), s(x + 12), s(y + 15)), fill=accent, width=s(2))
    elif kind == "backpack":
        d.rounded_rectangle(xy(x + 2, y + 4, 15, 13), radius=s(5), fill=(*accent[:3], 150))
        d.arc(xy(x + 5, y, 9, 8), 180, 360, fill=accent, width=s(2))
        d.rounded_rectangle(xy(x + 5, y + 9, 9, 5), radius=s(2), fill=(255, 255, 255, 205))
    elif kind == "trash":
        d.rounded_rectangle(xy(x + 3, y + 5, 13, 12), radius=s(3), fill=(*accent[:3], 135))
        d.rectangle(xy(x + 2, y + 3, 15, 3), fill=accent)
        d.line((s(x + 6), s(y + 8), s(x + 13), s(y + 15)), fill=(255, 255, 255, 230), width=s(2))
        d.line((s(x + 13), s(y + 8), s(x + 6), s(y + 15)), fill=(255, 255, 255, 230), width=s(2))


def node_base(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, title: str, accent, selected=False, icon_kind: str | None = None) -> None:
    round_rect(img, d, x, y, w, h, fill=SURFACE, outline=accent if selected else BORDER, r=16, cast_shadow=True)
    if accent == BLUE:
        header_fill = (232, 247, 255, 255)
    elif accent == CYAN:
        header_fill = (230, 249, 252, 255)
    elif accent == PINK:
        header_fill = (255, 239, 244, 255)
    else:
        header_fill = (242, 250, 255, 255)
    d.rounded_rectangle(xy(x + 4, y + 4, w - 8, 28), radius=s(12), fill=header_fill)
    d.line((s(x + 16), s(y + 31), s(x + w - 16), s(y + 31)), fill=(*accent[:3], 195), width=s(1))
    if icon_kind:
        node_icon(d, icon_kind, x + 12, y + 9, accent)
        text(d, title, x + 34, y + 11, INK, F12)
    else:
        text(d, title, x + 12, y + 11, INK, F12)
    chevron_button(d, x + w - 28, y + 10, color=BLUE)


def chest_node(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, title: str, selected=False) -> dict[str, tuple[int, int]]:
    w, h = 202, 160
    node_base(img, d, x, y, w, h, title, BLUE, selected, "chest")
    text(d, "[12, 64, -8]", x + 12, y + 38, MUTED, F12)
    text_right(d, "带宽 18/24", x + w - 12, y + 38, GREEN, F12)
    progress(img, d, x + 12, y + 56, w - 24, 0.75, (*GREEN[:3], 210))
    rows = [
        ("物品输入", "物品输出", GREEN, BLUE, "item"),
        ("流体输入", "流体输出", CYAN, (45, 142, 130, 255), "fluid"),
        ("电力输入", "电力输出", GOLD, (188, 132, 28, 255), "energy"),
        ("应力输入", "应力输出", PURPLE, (155, 80, 160, 255), "stress"),
    ]
    ports = {}
    yy = y + 78
    for il, ol, ic, oc, kind in rows:
        port(img, d, x + 10, yy + 8, ic)
        icon(d, kind, x + 28, yy, ic)
        text(d, il, x + 50, yy + 3, INK, F12)
        text_right(d, ol, x + w - 26, yy + 3, MUTED, F12)
        port(img, d, x + w - 10, yy + 8, oc)
        ports[kind + "_out"] = (x + w - 10, yy + 8)
        ports[kind + "_in"] = (x + 10, yy + 8)
        yy += 20
    return ports


def reroute_node(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int) -> dict[str, tuple[int, int]]:
    w, h = 278, 172
    node_base(img, d, x, y, w, h, "中转节点", CYAN, icon_kind="reroute")
    outs = {}
    rows = (
        ("全部物品", "物品", GREEN, BLUE, "item", "64/m"),
        ("全部流体", "流体", CYAN, (45, 142, 130, 255), "fluid", "128mB/m"),
        ("电力 FE", "电力", GOLD, (188, 132, 28, 255), "energy", "192FE/m"),
        ("应力 SU", "应力", PURPLE, (155, 80, 160, 255), "stress", "0SU/m"),
    )
    for i, (label, short, ic, oc, kind, rate) in enumerate(rows):
        yy = y + 49 + i * 24
        row_fill = (247, 252, 255, 245) if i % 2 == 0 else (255, 255, 255, 230)
        d.rounded_rectangle(xy(x + 16, yy - 10, w - 32, 20), radius=s(8), fill=row_fill)
        port(img, d, x + 10, yy, ic)
        icon(d, kind, x + 28, yy - 10, ic)
        text(d, label, x + 54, yy - 8, INK, F12)
        text_right(d, rate, x + w - 42, yy - 8, ic, F12)
        port(img, d, x + w - 10, yy, oc)
        outs[short] = (x + w - 10, yy)
    pill(img, d, x + 14, y + h - 29, 46, 20, "禁用", color=BLUE)
    pill(img, d, x + 66, y + h - 29, 46, 20, "删除", color=PINK)
    return {
        "物品_in": (x + 10, y + 49),
        "流体_in": (x + 10, y + 73),
        "电力_in": (x + 10, y + 97),
        "应力_in": (x + 10, y + 121),
        **outs,
    }


def small_node(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, title: str, subtitle: str, accent, color, icon_kind: str) -> tuple[int, int]:
    w, h = 184, 94
    node_base(img, d, x, y, w, h, title, accent, icon_kind=icon_kind)
    port(img, d, x + 10, y + h // 2, color)
    text(d, subtitle, x + 34, y + 42, INK, F12)
    pill(img, d, x + 14, y + h - 28, 44, 20, "禁用", color=BLUE)
    pill(img, d, x + 64, y + h - 28, 44, 20, "删除", color=PINK)
    return (x + 10, y + h // 2)


def popup(img: Image.Image, d: ImageDraw.ImageDraw) -> None:
    x, y, w, h = 892, 128, 306, 250
    round_rect(img, d, x, y, w, h, fill=(255, 255, 255, 248), outline=(218, 228, 237, 255), r=18, cast_shadow=True)
    text(d, "连线设置", x + 18, y + 16, INK, F14)
    text_right(d, "物品输出 → 中转节点", x + w - 18, y + 18, BLUE, F12)
    for i, (label, value) in enumerate((("状态", "启用"), ("传输周期", "1 秒"), ("每周期数量", "64"), ("物品过滤", "minecraft:iron_ingot"))):
        yy = y + 58 + i * 38
        text(d, label, x + 20, yy, MUTED, F12)
        field(img, d, x + 110, yy - 8, 166, 26, value)
    pill(img, d, x + 18, y + h - 38, 72, 26, "禁用", color=BLUE)
    pill(img, d, x + 100, y + h - 38, 72, 26, "复制", color=BLUE)
    pill(img, d, x + w - 90, y + h - 38, 72, 26, "删除", color=PINK)


def search_popup(img: Image.Image, d: ImageDraw.ImageDraw) -> None:
    x, y, w, h = 948, 386, 178, 82
    round_rect(img, d, x, y, w, h, fill=(255, 255, 255, 250), outline=(218, 228, 237, 255), r=14, cast_shadow=True)
    text(d, "添加过滤", x + 12, y + 10, INK, F12)
    toolbar_icon(d, "close", x + w - 25, y + 9, BLUE)
    field(img, d, x + 12, y + 32, w - 24, 22, "iron")
    d.rounded_rectangle(xy(x + 12, y + 60, w - 24, 16), radius=s(6), fill=BLUE_PALE, outline=(205, 226, 239, 255), width=s(1))
    icon(d, "item", x + 19, y + 60, GREEN)
    text(d, "iron_ingot", x + 42, y + 61, INK, F12)


def draw_preview() -> Image.Image:
    img = Image.new("RGBA", (W * SCALE, H * SCALE), BG)
    d = ImageDraw.Draw(img)
    draw_grid(d)
    draw_control_bar(img, d)
    a = chest_node(img, d, 92, 162, "维度仓 A", True)
    b = reroute_node(img, d, 410, 220)
    c = chest_node(img, d, 740, 428, "维度仓 B")
    player_in = small_node(img, d, 740, 146, "玩家背包", "补货目标 自己", BLUE, GREEN, "backpack")
    trash_in = small_node(img, d, 1010, 462, "销毁节点", "剩余产物终点", PINK, RED, "trash")
    draw_edge(d, a["item_out"], b["物品_in"], BLUE, 4)
    draw_edge(d, a["fluid_out"], b["流体_in"], CYAN, 4)
    draw_edge(d, b["物品"], player_in, GREEN, 4)
    draw_edge(d, b["流体"], c["fluid_in"], CYAN, 4)
    draw_edge(d, a["energy_out"], b["电力_in"], GOLD, 3)
    draw_edge(d, b["电力"], c["energy_in"], GOLD, 3)
    draw_edge(d, c["energy_out"], trash_in, GOLD, 4)
    popup(img, d)
    search_popup(img, d)
    return img


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img = draw_preview()
    img.save(OUT)
    controls = draw_controls_preview()
    controls.save(CONTROLS_OUT)
    print(OUT)
    print(CONTROLS_OUT)


if __name__ == "__main__":
    main()
