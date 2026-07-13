from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

from generate_foldworks_tablet_gui_atlas import OUT as ATLAS_PATH
from generate_foldworks_tablet_gui_atlas import draw_atlas


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "client-ui" / "previews" / "foldworks-tablet-ui-preview-v1.png"

# Minecraft GUI logical size used for this preview. A 1280x720 window at GUI scale 3 is about 427x240.
SCREEN_W = 640
SCREEN_H = 360
PREVIEW_SCALE = 4
ATLAS_SCALE = 8

PANEL_ASPECT = 1.6
HEADER_H = 26
NAV_WIDTH_MIN = 40
NAV_WIDTH_MAX = 48
NAV_ITEM_SIZE = 30
NAV_ITEM_GAP = 4
NAV_TOP_PAD = 6

SURFACE = (250, 253, 255, 255)
SURFACE_ALT = (242, 249, 255, 255)
SURFACE_SUNK = (230, 240, 249, 255)
BORDER = (202, 219, 232, 255)
BORDER_STRONG = (146, 195, 235, 255)
DIVIDER = (237, 244, 250, 255)
INK = (44, 62, 80, 255)
MUTED = (122, 139, 160, 255)
FAINT = (168, 184, 200, 255)
BLUE = (112, 177, 231, 255)
BLUE_DEEP = (76, 139, 204, 255)
BLUE_SOFT = (224, 243, 255, 255)
GREEN = (84, 190, 133, 255)
GOLD = (224, 174, 66, 255)
PINK = (241, 126, 144, 255)
PURPLE = (168, 120, 205, 255)
CYAN = (78, 184, 203, 255)


SPRITES = {
    "panel": (0, 0, 32, 32),
    "panel_soft": (32, 0, 32, 32),
    "panel_header": (64, 0, 32, 32),
    "panel_selected": (96, 0, 32, 32),
    "panel_warning": (128, 0, 32, 32),
    "panel_sunk": (160, 0, 32, 32),
    "button": (0, 40, 32, 24),
    "button_primary": (32, 40, 32, 24),
    "button_danger": (64, 40, 32, 24),
    "button_disabled": (96, 40, 32, 24),
    "chip": (0, 72, 32, 16),
    "chip_blue": (32, 72, 32, 16),
    "chip_green": (64, 72, 32, 16),
    "chip_gold": (96, 72, 32, 16),
    "chip_purple": (128, 72, 32, 16),
    "scroll_track": (176, 72, 4, 32),
    "scroll_thumb": (184, 72, 4, 32),
}
ICON_ORDER = [
    "create", "manage", "permissions", "production", "migration", "close", "settings", "enter",
    "offline", "delete", "refresh", "download", "upload", "search", "team", "archive",
]
for i, name in enumerate(ICON_ORDER):
    SPRITES["icon_" + name] = ((i % 8) * 32, 112 + (i // 8) * 32, 32, 32)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in (
        ROOT / "src/main/resources/assets/foldworks/font/notosanssc-regular.ttf",
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/arial.ttf"),
    ):
        try:
            return ImageFont.truetype(str(path), size * PREVIEW_SCALE)
        except OSError:
            pass
    return ImageFont.load_default()


F9 = font(9)
F8 = font(8)
F10 = font(10)
F11 = font(11)
F12 = font(12)


def s(v: int | float) -> int:
    return round(v * PREVIEW_SCALE)


def xy(x: int, y: int, w: int, h: int):
    return s(x), s(y), s(x + w), s(y + h)


def load_atlas() -> Image.Image:
    if ATLAS_PATH.exists():
        return Image.open(ATLAS_PATH).convert("RGBA")
    return draw_atlas()


ATLAS = load_atlas()


def crop(name: str) -> Image.Image:
    x, y, w, h = SPRITES[name]
    return ATLAS.crop((x * ATLAS_SCALE, y * ATLAS_SCALE, (x + w) * ATLAS_SCALE, (y + h) * ATLAS_SCALE))


def blit(img: Image.Image, name: str, x: int, y: int, w: int, h: int) -> None:
    sprite = crop(name).resize((s(w), s(h)), Image.Resampling.LANCZOS)
    img.alpha_composite(sprite, (s(x), s(y)))


def nine(img: Image.Image, name: str, x: int, y: int, w: int, h: int, corner: int = 8) -> None:
    src = crop(name)
    src_w = SPRITES[name][2]
    src_h = SPRITES[name][3]
    ss = ATLAS_SCALE
    c = corner * ss
    parts = [
        (0, 0, c, c, x, y, corner, corner),
        (c, 0, src_w * ss - c, c, x + corner, y, w - corner * 2, corner),
        (src_w * ss - c, 0, src_w * ss, c, x + w - corner, y, corner, corner),
        (0, c, c, src_h * ss - c, x, y + corner, corner, h - corner * 2),
        (c, c, src_w * ss - c, src_h * ss - c, x + corner, y + corner, w - corner * 2, h - corner * 2),
        (src_w * ss - c, c, src_w * ss, src_h * ss - c, x + w - corner, y + corner, corner, h - corner * 2),
        (0, src_h * ss - c, c, src_h * ss, x, y + h - corner, corner, corner),
        (c, src_h * ss - c, src_w * ss - c, src_h * ss, x + corner, y + h - corner, w - corner * 2, corner),
        (src_w * ss - c, src_h * ss - c, src_w * ss, src_h * ss, x + w - corner, y + h - corner, corner, corner),
    ]
    for sx0, sy0, sx1, sy1, dx, dy, dw, dh in parts:
        if dw <= 0 or dh <= 0:
            continue
        piece = src.crop((sx0, sy0, sx1, sy1)).resize((s(dw), s(dh)), Image.Resampling.LANCZOS)
        img.alpha_composite(piece, (s(dx), s(dy)))


def shadow(img: Image.Image, x: int, y: int, w: int, h: int, r: int = 8) -> None:
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(xy(x + 2, y + 3, w, h), radius=s(r), fill=(56, 86, 116, 34))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(s(3))))


def text(d: ImageDraw.ImageDraw, value: str, x: int, y: int, color=INK, f=F11):
    d.text((s(x), s(y)), value, fill=color, font=f)


def text_center(d: ImageDraw.ImageDraw, value: str, cx: int, y: int, color=INK, f=F11):
    box = d.textbbox((0, 0), value, font=f)
    d.text((s(cx) - (box[2] - box[0]) // 2, s(y)), value, fill=color, font=f)


def text_right(d: ImageDraw.ImageDraw, value: str, rx: int, y: int, color=INK, f=F11):
    box = d.textbbox((0, 0), value, font=f)
    d.text((s(rx) - (box[2] - box[0]), s(y)), value, fill=color, font=f)


def text_fit(d: ImageDraw.ImageDraw, value: str, x: int, y: int, max_w: int, color=INK, f=F11):
    text(d, ellipsize(d, value, max_w, f), x, y, color, f)


def ellipsize(d: ImageDraw.ImageDraw, value: str, max_w: int, f=F11) -> str:
    if d.textbbox((0, 0), value, font=f)[2] <= s(max_w):
        return value
    out = ""
    for ch in value:
        if d.textbbox((0, 0), out + ch + "…", font=f)[2] > s(max_w):
            return out + "…"
        out += ch
    return out


def pill(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, label: str, sprite="chip", color=MUTED):
    nine(img, sprite, x, y, w, h, min(4, h // 3))
    text_center(d, label, x + w // 2, y + (h - 10) // 2, color, F9)


def button(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, label: str, sprite="button", color=INK, icon: str | None = None):
    nine(img, sprite, x, y, w, h, min(4, h // 3))
    tx = x + 8
    if icon:
        blit(img, "icon_" + icon, x + 5, y + (h - 14) // 2, 14, 14)
        tx += 15
    text_center(d, label, x + w // 2 + (5 if icon else 0), y + (h - 10) // 2, color, F10)


def field(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, label: str, value: str = ""):
    nine(img, "panel_sunk", x, y, w, h, 6)
    text(d, label, x + 7, y + (h - 10) // 2, FAINT, F9)
    if value:
        text(d, value, x + 62, y + (h - 10) // 2, INK, F9)


def layout():
    max_w = max(240, SCREEN_W - 16)
    max_h = max(180, SCREEN_H - 16)
    panel_w = min(max_w, round(max_h * PANEL_ASPECT))
    panel_h = round(panel_w / PANEL_ASPECT)
    if panel_h > max_h:
        panel_h = max_h
        panel_w = round(panel_h * PANEL_ASPECT)
    panel_w = max(240, min(panel_w, max_w))
    panel_h = max(180, min(panel_h, max_h))
    panel_x = (SCREEN_W - panel_w) // 2
    panel_y = (SCREEN_H - panel_h) // 2
    sidebar_w = max(NAV_WIDTH_MIN, min(NAV_WIDTH_MAX, round(panel_w * 0.045)))
    content_x = panel_x + sidebar_w + 1
    content_y = panel_y + HEADER_H + 1
    return panel_x, panel_y, panel_w, panel_h, sidebar_w, content_x, content_y, panel_w - sidebar_w - 2, panel_h - HEADER_H - 2


PAGES = [
    ("create", "创建工造"),
    ("manage", "管理工造"),
    ("permissions", "权限"),
    ("production", "产率统计"),
    ("migration", "迁移"),
]


def base_screen(active: str) -> Image.Image:
    img = Image.new("RGBA", (s(SCREEN_W), s(SCREEN_H)), (18, 26, 36, 190))
    d = ImageDraw.Draw(img)
    px, py, pw, ph, sw, cx, cy, cw, ch = layout()
    shadow(img, px, py, pw, ph, 9)
    nine(img, "panel", px, py, pw, ph, 8)
    text(d, "工造终端", px + 12, py + 9, INK, F11)
    d.line((s(px + 1), s(py + HEADER_H), s(px + pw - 1), s(py + HEADER_H)), fill=DIVIDER, width=s(1))
    blit(img, "icon_close", px + pw - 28, py + 5, 16, 16)
    d.line((s(px + sw), s(py + HEADER_H + 1), s(px + sw), s(py + ph - 1)), fill=DIVIDER, width=s(1))

    item_size = min(NAV_ITEM_SIZE, max(20, min(sw - 12, (ph - HEADER_H - NAV_TOP_PAD * 2 - (len(PAGES) - 1) * NAV_ITEM_GAP) // len(PAGES))))
    top = py + HEADER_H + NAV_TOP_PAD
    for i, (pid, title) in enumerate(PAGES):
        ix = px + (sw - item_size) // 2
        iy = top + i * (item_size + NAV_ITEM_GAP)
        if pid == active:
            blit(img, "button_primary", ix, iy, item_size, item_size)
            d.rounded_rectangle(xy(ix + 5, iy + item_size - 4, item_size - 10, 2), radius=s(1), fill=BLUE_DEEP)
        else:
            blit(img, "button", ix, iy, item_size, item_size)
        blit(img, "icon_" + pid, ix + (item_size - 18) // 2, iy + (item_size - 18) // 2, 18, 18)
    return img


def draw_manage(img: Image.Image):
    d = ImageDraw.Draw(img)
    _, _, _, _, _, x, y, w, h = layout()
    pad = 12
    text(d, "选择要进入的工造", x + pad, y + pad, MUTED, F10)
    fy = y + pad + 16
    nine(img, "panel_soft", x + pad, fy, w - pad * 2, 42, 7)
    text(d, "权限", x + pad + 8, fy + 10, MUTED, F8)
    chip_x = x + pad + 39
    for label, sp in (("可见", "chip"), ("使用", "chip_blue"), ("编辑", "chip"), ("管理", "chip")):
        pill(img, d, chip_x, fy + 6, 34 if label != "管理" else 38, 17, label, sp, BLUE_DEEP if sp == "chip_blue" else MUTED)
        chip_x += 38
    text(d, "Owner", x + pad + 8, fy + 29, MUTED, F8)
    pill(img, d, x + pad + 45, fy + 25, 36, 17, "自己", "chip", MUTED)
    field(img, d, x + pad + 85, fy + 25, w - pad * 2 - 176, 17, "", "Skadi")
    pill(img, d, x + w - pad - 76, fy + 25, 38, 17, "在线", "chip_blue", BLUE_DEEP)
    pill(img, d, x + w - pad - 34, fy + 25, 34, 17, "清除", "chip", MUTED)

    list_y = fy + 48
    row_w = w - pad * 2
    rows = [
        ("白露庭院", "128×128  Flat  cherry_grove  私有", "Owner 2a6f1e9b", "权限 管理", True),
        ("璃月工坊", "无限  Natural  overworld  白名单", "Owner 9bd4a2c1", "权限 编辑", False),
    ]
    for i, (name, meta, owner, perm, current) in enumerate(rows):
        ry = list_y + i * 66
        nine(img, "panel_selected" if current else "panel_soft", x + pad, ry, row_w, 60, 7)
        text_fit(d, name, x + pad + 12, ry + 8, row_w - 150, INK, F11)
        text_fit(d, meta, x + pad + 12, ry + 24, row_w - 170, MUTED, F8)
        text_fit(d, owner, x + pad + 12, ry + 42, 105, FAINT, F8)
        text_fit(d, perm, x + pad + 128, ry + 42, 68, FAINT, F8)
        if current:
            pill(img, d, x + pad + row_w - 63, ry + 31, 54, 22, "当前", "chip_green", GREEN)
        else:
            button(img, d, x + pad + row_w - 59, ry + 30, 50, 22, "进入", "button_primary", BLUE_DEEP)
        for j, icon in enumerate(("offline", "settings", "delete")):
            if current and icon == "delete":
                continue
            blit(img, "button" if icon != "delete" else "button_danger", x + pad + row_w - 145 + j * 26, ry + 31, 20, 20)
            blit(img, "icon_" + icon, x + pad + row_w - 141 + j * 26, ry + 35, 12, 12)
    d.line((s(x), s(y + h - 44), s(x + w), s(y + h - 44)), fill=DIVIDER, width=s(1))
    button(img, d, x + pad, y + h - 36, w - pad * 2, 28, "新建工造", "button_primary", BLUE_DEEP, "create")


def draw_create(img: Image.Image):
    d = ImageDraw.Draw(img)
    _, _, _, _, _, x, y, w, h = layout()
    pad = 12
    yy = y + pad
    text(d, "空间类型", x + pad, yy, MUTED, F10)
    yy += 13
    chip_w = (w - pad * 2 - 16) // 3
    for i, (label, sp) in enumerate((("平坦", "chip_blue"), ("自然", "chip"), ("无限", "chip"))):
        pill(img, d, x + pad + i * (chip_w + 8), yy, chip_w, 26, label, sp, BLUE_DEEP if sp == "chip_blue" else MUTED)
    yy += 36
    text(d, "尺寸", x + pad, yy, MUTED, F10)
    yy += 13
    field(img, d, x + pad, yy, (w - pad * 2 - 8) // 2, 22, "宽度", "64")
    field(img, d, x + pad + (w - pad * 2 + 8) // 2, yy, (w - pad * 2 - 8) // 2, 22, "深度", "64")
    yy += 34
    text(d, "继承维度", x + pad, yy, MUTED, F10)
    yy += 13
    field(img, d, x + pad, yy, w - pad * 2, 20, "", "Minecraft 主世界")
    text_right(d, "⌄", x + w - pad - 9, yy + 4, MUTED, F10)
    yy += 32
    text(d, "群系", x + pad, yy, MUTED, F10)
    yy += 13
    field(img, d, x + pad, yy, w - pad * 2, 20, "", "随机")
    text_right(d, "⌄", x + w - pad - 9, yy + 4, MUTED, F10)
    yy += 32
    toggle(img, d, x + pad, yy, (w - pad * 2 - 8) // 2, "生成生物", False)
    toggle(img, d, x + pad + (w - pad * 2 + 8) // 2, yy, (w - pad * 2 - 8) // 2, "生成结构", False)
    d.line((s(x), s(y + h - 44), s(x + w), s(y + h - 44)), fill=DIVIDER, width=s(1))
    button(img, d, x + pad, y + h - 36, w - pad * 2 - 104, 28, "创建并进入", "button_primary", BLUE_DEEP)
    button(img, d, x + w - pad - 96, y + h - 36, 96, 28, "取消", "button", INK)


def toggle(img: Image.Image, d: ImageDraw.ImageDraw, x: int, y: int, w: int, label: str, on: bool):
    text(d, label, x + 4, y + 7, INK, F10)
    tx = x + w - 34
    color = BLUE if on else BORDER_STRONG
    d.rounded_rectangle(xy(tx, y + 5, 30, 16), radius=s(4), fill=color)
    knob_x = tx + (16 if on else 2)
    d.rounded_rectangle(xy(knob_x, y + 7, 12, 12), radius=s(3), fill=(255, 255, 255, 255))


def draw_permissions(img: Image.Image):
    d = ImageDraw.Draw(img)
    _, _, _, _, _, x, y, w, h = layout()
    pad = 8
    list_w = max(138, min(206, round(w * 0.30)))
    nine(img, "panel_soft", x + pad, y + pad, list_w, h - pad * 2, 7)
    text(d, "团队", x + pad + 8, y + pad + 8, INK, F10)
    button(img, d, x + pad + list_w - 52, y + pad + 5, 44, 18, "新建", "button_primary", BLUE_DEEP)
    for i, (name, sub, active) in enumerate((("工坊成员", "我创建 · 3 人", True), ("访客", "可见 · 6 人", False), ("建筑组", "可放置 · 2 人", False))):
        ry = y + pad + 32 + i * 40
        nine(img, "panel_selected" if active else "panel", x + pad + 5, ry, list_w - 10, 34, 5)
        text_fit(d, name, x + pad + 10, ry + 6, list_w - 24, BLUE_DEEP if active else INK, F10)
        text_fit(d, sub, x + pad + 10, ry + 21, list_w - 24, FAINT, F8)
    dx = x + pad + list_w + 8
    dw = x + w - pad - dx
    team_h = max(170, h - pad * 2 - 118 - 8)
    nine(img, "panel", dx, y + pad, dw, team_h, 7)
    text(d, "工坊成员", dx + 10, y + pad + 9, INK, F10)
    text_right(d, "所有者", dx + dw - 10, y + pad + 9, GREEN, F9)
    field(img, d, dx + 10, y + pad + 30, dw - 140, 22, "团队", "工坊成员")
    button(img, d, dx + dw - 122, y + pad + 30, 58, 22, "重命名", "button_primary", BLUE_DEEP)
    button(img, d, dx + dw - 58, y + pad + 30, 48, 22, "解散", "button_danger", PINK)
    field(img, d, dx + 10, y + pad + 62, dw - 130, 22, "玩家", "")
    pill(img, d, dx + dw - 116, y + pad + 62, 56, 22, "可放置", "chip_blue", BLUE_DEEP)
    button(img, d, dx + dw - 54, y + pad + 62, 44, 22, "添加", "button_primary", BLUE_DEEP)
    text(d, "成员", dx + 10, y + pad + 98, MUTED, F9)
    for i, (name, level) in enumerate((("Skadi", "管理"), ("Alex", "可进入"), ("Steve", "可见"))):
        ry = y + pad + 114 + i * 24
        nine(img, "panel_soft", dx + 10, ry, dw - 20, 20, 4)
        text_fit(d, name, dx + 16, ry + 5, dw - 116, INK, F9)
        pill(img, d, dx + dw - 84, ry + 3, 54, 15, level, "chip", MUTED)
        text_center(d, "×", dx + dw - 19, ry + 4, PINK, F9)
    py = y + pad + team_h + 8
    nine(img, "panel", dx, py, dw, y + h - pad - py, 7)
    text(d, "我的私有权限", dx + 10, py + 8, INK, F10)
    for i, label in enumerate(("拒绝", "可见", "可进入", "可放置")):
        pill(img, d, dx + 86 + i * 54, py + 28, 48, 20, label, "chip_blue" if i == 2 else "chip", BLUE_DEEP if i == 2 else MUTED)


def draw_production(img: Image.Image):
    d = ImageDraw.Draw(img)
    _, _, _, _, _, x, y, w, h = layout()
    nine(img, "panel", x, y, w, h, 7)
    text(d, "产率统计", x + 11, y + 7, INK, F10)
    pill(img, d, x + 78, y + 5, 78, 16, "默认分组", "chip_blue", BLUE_DEEP)
    field(img, d, x + 166, y + 5, 96, 16, "", "搜索资源")
    pill(img, d, x + w - 202, y + 5, 54, 16, "隐藏零", "chip", MUTED)
    pill(img, d, x + w - 142, y + 5, 62, 16, "净产率", "chip", MUTED)
    pill(img, d, x + w - 72, y + 5, 66, 16, "10分钟", "chip_blue", BLUE_DEEP)
    hx = x + 8
    hy = y + 30
    hw = w - 16
    d.rounded_rectangle(xy(hx, hy, hw, 14), radius=s(7), fill=(229, 237, 245, 150))
    text_center(d, "产物信息", hx + 60, hy + 3, MUTED, F9)
    text_center(d, "产率/消耗率走势", hx + hw // 2, hy + 3, MUTED, F9)
    text_center(d, "当前统计", hx + hw - 58, hy + 3, MUTED, F9)
    rows = [("铁锭", "库存 2.1K", GREEN, "+64/分"), ("水", "库存 16000 mB", CYAN, "+250mB/分"), ("小麦", "库存 860", GOLD, "-12/分"), ("红石", "库存 64", PINK, "+0/分")]
    for i, (name, stock, color, net) in enumerate(rows):
        ry = hy + 18 + i * 36
        nine(img, "panel_soft", hx, ry, hw, 34, 6)
        text(d, "★", hx + 7, ry + 12, GOLD if i < 2 else FAINT, F9)
        d.rounded_rectangle(xy(hx + 25, ry + 10, 14, 14), radius=s(4), fill=color)
        text_fit(d, name, hx + 45, ry + 5, 58, INK, F9)
        text_fit(d, stock, hx + 45, ry + 19, 70, MUTED, F8)
        graph_x = hx + 132
        graph_w = hw - 266
        d.rounded_rectangle(xy(graph_x, ry + 5, graph_w, 24), radius=s(4), fill=(233, 241, 248, 255))
        pts = []
        for j in range(18):
            px = graph_x + 5 + j * (graph_w - 10) / 17
            py = ry + 24 - ((j * 7 + i * 5) % 16)
            pts.append((s(px), s(py)))
        d.line(pts, fill=color, width=s(1))
        stat_label_x = hx + hw - 122
        stat_right_x = hx + hw - 12
        text(d, "生产", stat_label_x, ry + 5, BLUE_DEEP, F8)
        text_right(d, "64/分", stat_right_x, ry + 5, BLUE_DEEP, F8)
        text(d, "消耗", stat_label_x, ry + 17, GOLD, F8)
        text_right(d, "0/分", stat_right_x, ry + 17, GOLD, F8)
        text_right(d, net, stat_right_x, ry + 27, GREEN if net.startswith("+") else PINK, F8)


def draw_migration(img: Image.Image):
    d = ImageDraw.Draw(img)
    _, _, _, _, _, x, y, w, h = layout()
    pad = 12
    nine(img, "panel_warning", x + pad, y + pad, w - pad * 2, 48, 7)
    text(d, "实验性空间迁移", x + pad + 10, y + pad + 8, (122, 83, 22, 255), F10)
    text_fit(d, "下载会保存空间包；上传只会在服务器中新建空间。", x + pad + 10, y + pad + 24, w - pad * 2 - 20, (138, 106, 50, 255), F9)
    body_y = y + pad + 56
    body_h = y + h - 30 - body_y - 6
    col_gap = 8
    col_w = (w - pad * 2 - col_gap) // 2
    for ci, (title, icon, rows) in enumerate((
        ("服务器空间", "download", [("白露庭院", "私有 · 权限 管理"), ("璃月工坊", "白名单 · 权限 编辑")]),
        ("本地空间包", "upload", [("bailu_2026.phspace", "4.8 MB · 07-02 16:20"), ("workshop.phspace", "2.1 MB · 07-01 22:10")]),
    )):
        cx = x + pad + ci * (col_w + col_gap)
        text(d, title, cx + 2, body_y + 4, INK, F10)
        text_right(d, str(len(rows)), cx + col_w - 2, body_y + 4, FAINT, F9)
        list_y = body_y + 18
        for i, (name, sub) in enumerate(rows):
            ry = list_y + i * 60
            nine(img, "panel_soft", cx, ry, col_w, 54 if ci == 0 else 48, 7)
            blit(img, "icon_archive" if ci else "icon_manage", cx + 8, ry + 9, 16, 16)
            text_fit(d, name, cx + 30, ry + 8, col_w - 88, INK, F10)
            text_fit(d, sub, cx + 30, ry + 25, col_w - 88, MUTED, F8)
            button(img, d, cx + col_w - 54, ry + (28 if ci == 0 else 22), 46, 18, "下载" if ci == 0 else "上传", "button_primary", BLUE_DEEP, icon)
    d.line((s(x + pad), s(y + h - 30), s(x + w - pad), s(y + h - 30)), fill=DIVIDER, width=s(1))
    button(img, d, x + pad, y + h - 24, 54, 18, "刷新", "button", INK, "refresh")
    text(d, "空闲", x + pad + 64, y + h - 19, MUTED, F9)


def make_frame(page: str) -> Image.Image:
    img = base_screen(page)
    if page == "manage":
        draw_manage(img)
    elif page == "create":
        draw_create(img)
    elif page == "permissions":
        draw_permissions(img)
    elif page == "production":
        draw_production(img)
    elif page == "migration":
        draw_migration(img)
    return img


def draw_preview() -> Image.Image:
    frames = [(pid, title, make_frame(pid)) for pid, title in PAGES]
    gap = s(10)
    label_h = s(18)
    w = s(SCREEN_W) * 2 + gap * 3
    h = (s(SCREEN_H) + label_h) * 3 + gap * 4
    out = Image.new("RGBA", (w, h), (244, 250, 255, 255))
    d = ImageDraw.Draw(out)
    for i, (pid, title, frame) in enumerate(frames):
        col = i % 2
        row = i // 2
        ox = gap + col * (s(SCREEN_W) + gap)
        oy = gap + row * (s(SCREEN_H) + label_h + gap)
        d.text((ox, oy), title, fill=INK, font=F12)
        out.alpha_composite(frame, (ox, oy + label_h))
    return out


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img = draw_preview()
    img.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
