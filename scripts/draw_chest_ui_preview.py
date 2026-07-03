from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "client-ui" / "previews"
FONT_PATH = ROOT / "src" / "main" / "resources" / "assets" / "pockethomestead" / "font" / "notosanssc-regular.ttf"
ATLAS_PATH = ROOT / "src" / "main" / "resources" / "assets" / "pockethomestead" / "textures" / "gui" / "chest.png"

VERSION = "v8"
SCALE = 4
PANEL_W = 186
PANEL_H = 250
SLOT = 18


@dataclass(frozen=True)
class Palette:
    # Blue-white tablet direction, kept compact and Minecraft-readable.
    ink: tuple[int, int, int, int] = (50, 65, 82, 255)
    muted: tuple[int, int, int, int] = (122, 139, 160, 255)
    faint: tuple[int, int, int, int] = (168, 184, 200, 255)
    panel: tuple[int, int, int, int] = (224, 237, 248, 255)
    panel_light: tuple[int, int, int, int] = (239, 247, 253, 255)
    panel_lighter: tuple[int, int, int, int] = (250, 253, 255, 255)
    slot: tuple[int, int, int, int] = (221, 235, 246, 255)
    slot_hover: tuple[int, int, int, int] = (208, 232, 248, 255)
    slot_selected: tuple[int, int, int, int] = (205, 237, 225, 255)
    slot_locked: tuple[int, int, int, int] = (202, 212, 222, 255)
    light: tuple[int, int, int, int] = (255, 255, 255, 255)
    edge_light: tuple[int, int, int, int] = (255, 255, 255, 255)
    edge_mid: tuple[int, int, int, int] = (202, 219, 232, 255)
    edge_dark: tuple[int, int, int, int] = (146, 195, 235, 255)
    edge_deep: tuple[int, int, int, int] = (112, 152, 184, 255)
    blue: tuple[int, int, int, int] = (76, 139, 204, 255)
    blue_soft: tuple[int, int, int, int] = (224, 243, 255, 255)
    cyan: tuple[int, int, int, int] = (78, 184, 203, 255)
    green: tuple[int, int, int, int] = (84, 190, 133, 255)
    gold: tuple[int, int, int, int] = (224, 174, 66, 255)
    brass: tuple[int, int, int, int] = (176, 132, 72, 255)
    red: tuple[int, int, int, int] = (241, 126, 144, 255)
    purple: tuple[int, int, int, int] = (168, 120, 205, 255)
    clear: tuple[int, int, int, int] = (0, 0, 0, 0)


PAL = Palette()

UPGRADE_SPRITES = {
    "storage": (0, 128, 32, 32),
    "fluid": (32, 128, 32, 32),
    "network": (64, 128, 32, 32),
    "energy": (96, 128, 32, 32),
    "stress": (128, 128, 32, 32),
}
_ATLAS: Image.Image | None = None

UPGRADE_COLORS = {
    "storage": PAL.blue,
    "fluid": PAL.cyan,
    "network": PAL.green,
    "energy": PAL.gold,
    "stress": PAL.brass,
}


def atlas() -> Image.Image:
    global _ATLAS
    if _ATLAS is None:
        _ATLAS = Image.open(ATLAS_PATH).convert("RGBA")
    return _ATLAS


def atlas_crop(u: int, v: int, w: int, h: int) -> Image.Image:
    image = atlas()
    atlas_scale = max(1, image.width // 256)
    return image.crop((u * atlas_scale, v * atlas_scale, (u + w) * atlas_scale, (v + h) * atlas_scale))


def mix(a: tuple[int, int, int, int], b: tuple[int, int, int, int], t: float) -> tuple[int, int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))  # type: ignore[return-value]


class Canvas:
    def __init__(self, width: int, height: int, scale: int = SCALE, bg: tuple[int, int, int, int] = (232, 232, 232, 255)):
        self.scale = scale
        self.image = Image.new("RGBA", (width * scale, height * scale), bg)
        self.draw = ImageDraw.Draw(self.image)
        self.fonts: dict[int, ImageFont.FreeTypeFont | ImageFont.ImageFont] = {}

    def s(self, value: float) -> int:
        return round(value * self.scale)

    def xy(self, x: float, y: float) -> tuple[int, int]:
        return self.s(x), self.s(y)

    def box(self, x: float, y: float, w: float, h: float) -> tuple[int, int, int, int]:
        return self.s(x), self.s(y), self.s(x + w) - 1, self.s(y + h) - 1

    def font(self, size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        key = size * self.scale
        if key not in self.fonts:
            if FONT_PATH.exists():
                self.fonts[key] = ImageFont.truetype(str(FONT_PATH), key)
            else:
                self.fonts[key] = ImageFont.load_default()
        return self.fonts[key]

    def rect(self, x: float, y: float, w: float, h: float, fill: tuple[int, int, int, int]) -> None:
        self.draw.rectangle(self.box(x, y, w, h), fill=fill)

    def line(self, xy: Sequence[tuple[float, float]], fill: tuple[int, int, int, int], width: float = 1.0) -> None:
        self.draw.line([self.xy(x, y) for x, y in xy], fill=fill, width=max(1, self.s(width)))

    def ellipse(
        self,
        x: float,
        y: float,
        w: float,
        h: float,
        fill: tuple[int, int, int, int],
        outline: tuple[int, int, int, int] | None = None,
        width: int = 1,
    ) -> None:
        self.draw.ellipse(self.box(x, y, w, h), fill=fill, outline=outline, width=max(1, self.s(width)))

    def polygon(self, pts: Sequence[tuple[float, float]], fill: tuple[int, int, int, int]) -> None:
        self.draw.polygon([self.xy(x, y) for x, y in pts], fill=fill)

    def text(
        self,
        x: float,
        y: float,
        label: str,
        color: tuple[int, int, int, int] = PAL.ink,
        size: int = 8,
        anchor: str = "la",
    ) -> None:
        self.draw.text(self.xy(x, y), label, fill=color, font=self.font(size), anchor=anchor)

    def text_center(self, x: float, y: float, label: str, color: tuple[int, int, int, int] = PAL.ink, size: int = 8) -> None:
        self.text(x, y, label, color, size, "mm")

    def text_right(self, x: float, y: float, label: str, color: tuple[int, int, int, int] = PAL.ink, size: int = 8) -> None:
        self.text(x, y, label, color, size, "ra")


def bevel_rect(
    c: Canvas,
    x: float,
    y: float,
    w: float,
    h: float,
    fill: tuple[int, int, int, int],
    *,
    pressed: bool = False,
    light: tuple[int, int, int, int] = PAL.light,
    dark: tuple[int, int, int, int] = PAL.edge_dark,
) -> None:
    c.rect(x, y, w, h, fill)
    top_left = dark if pressed else light
    bottom_right = light if pressed else dark
    c.rect(x, y, w, 1, top_left)
    c.rect(x, y, 1, h, top_left)
    c.rect(x, y + h - 1, w, 1, bottom_right)
    c.rect(x + w - 1, y, 1, h, bottom_right)


def inset_rect(c: Canvas, x: float, y: float, w: float, h: float, fill: tuple[int, int, int, int]) -> None:
    bevel_rect(c, x, y, w, h, fill, pressed=True, light=PAL.edge_light, dark=PAL.edge_deep)


def draw_shadow(c: Canvas, x: float, y: float, w: float, h: float) -> None:
    c.rect(x + 3, y + 3, w, h, (72, 96, 120, 48))


def text_shadow(c: Canvas, x: float, y: float, label: str, color: tuple[int, int, int, int] = PAL.light, size: int = 6, anchor: str = "ra") -> None:
    c.text(x + 0.8, y + 0.8, label, PAL.edge_dark, size, anchor)
    c.text(x, y, label, color, size, anchor)


def draw_panel_shell(c: Canvas, x: int, y: int, title: str, page: str, active_tab: int) -> None:
    draw_shadow(c, x, y, PANEL_W, PANEL_H)
    bevel_rect(c, x, y, PANEL_W, PANEL_H, PAL.panel)
    c.rect(x + 3, y + 3, PANEL_W - 6, PANEL_H - 6, PAL.panel_light)
    c.rect(x + 4, y + 4, PANEL_W - 8, 16, PAL.panel_lighter)
    c.rect(x + 4, y + 20, PANEL_W - 8, 1, PAL.edge_mid)
    c.text(x + 8, y + 7, title, PAL.ink, 8)
    c.text_right(x + PANEL_W - 50, y + 7, page, PAL.muted, 7)
    draw_button(c, x + PANEL_W - 44, y + 5, 36, 13, "下一页", "normal")
    draw_tabs(c, x + 8, y + PANEL_H - 17, active_tab)


def draw_tabs(c: Canvas, x: float, y: float, active: int) -> None:
    labels = ["物", "液", "面", "设"]
    for i, label in enumerate(labels):
        draw_chip(c, x + i * 20, y, 18, 13, label, "selected" if i == active else "normal", 7)


def draw_button(c: Canvas, x: float, y: float, w: float, h: float, label: str, state: str) -> None:
    fills = {
        "normal": PAL.panel_lighter,
        "hover": mix(PAL.blue_soft, PAL.light, 0.18),
        "selected": PAL.blue_soft,
        "disabled": (220, 228, 236, 255),
        "gold": (255, 246, 226, 255),
        "danger": (255, 235, 240, 255),
    }
    fill = fills.get(state, PAL.panel_lighter)
    pressed = state == "selected"
    bevel_rect(c, x, y, w, h, fill, pressed=pressed)
    if state == "hover":
        c.rect(x + 2, y + 2, w - 4, 1, PAL.light)
    if state == "disabled":
        color = PAL.faint
    elif state == "selected":
        color = (43, 81, 105, 255)
    else:
        color = PAL.ink
    c.text_center(x + w / 2, y + h / 2 + 0.5, label, color, 7)


def draw_chip(c: Canvas, x: float, y: float, w: float, h: float, label: str, state: str = "normal", size: int = 7) -> None:
    if state == "selected":
        fill, pressed, color = PAL.blue_soft, True, PAL.blue
    elif state == "hover":
        fill, pressed, color = mix(PAL.blue_soft, PAL.light, 0.18), False, PAL.blue
    elif state == "gold":
        fill, pressed, color = (255, 246, 226, 255), False, (160, 118, 42, 255)
    elif state == "danger":
        fill, pressed, color = (255, 235, 240, 255), False, PAL.red
    elif state == "disabled":
        fill, pressed, color = (220, 228, 236, 255), False, PAL.faint
    else:
        fill, pressed, color = PAL.panel_lighter, False, PAL.ink
    bevel_rect(c, x, y, w, h, fill, pressed=pressed)
    c.text_center(x + w / 2, y + h / 2 + 0.5, label, color, size)


def draw_slot(c: Canvas, x: float, y: float, state: str = "empty") -> None:
    if state == "hover":
        fill = PAL.slot_hover
    elif state == "selected":
        fill = PAL.slot_selected
    elif state == "locked":
        fill = PAL.slot_locked
    else:
        fill = PAL.slot
    inset_rect(c, x, y, 18, 18, fill)
    c.rect(x + 2, y + 2, 14, 14, mix(fill, PAL.edge_dark, 0.06))
    c.rect(x + 2, y + 2, 14, 1, mix(fill, PAL.edge_deep, 0.22))
    c.rect(x + 2, y + 15, 14, 1, mix(fill, PAL.light, 0.20))
    if state == "locked":
        draw_icon(c, x + 3, y + 3, "lock", PAL.edge_mid)


def draw_item_placeholder(c: Canvas, x: float, y: float, color: tuple[int, int, int, int], shape: str, count: str | None = None) -> None:
    px, py = x + 3, y + 3
    shade = mix(color, PAL.edge_dark, 0.30)
    light = mix(color, PAL.light, 0.35)
    if shape == "cube":
        c.rect(px + 1, py + 2, 10, 10, shade)
        c.rect(px + 2, py + 1, 9, 9, color)
        c.rect(px + 2, py + 1, 9, 2, light)
        c.rect(px + 8, py + 4, 2, 6, mix(color, PAL.edge_deep, 0.15))
    elif shape == "gem":
        pts = [(px + 6, py + 0), (px + 12, py + 5), (px + 9, py + 12), (px + 3, py + 12), (px + 0, py + 5)]
        c.polygon(pts, shade)
        c.polygon([(px + 6, py + 1), (px + 11, py + 5), (px + 8, py + 11), (px + 4, py + 11), (px + 1, py + 5)], color)
        c.rect(px + 4, py + 4, 5, 2, light)
    else:
        c.rect(px + 3, py + 1, 7, 1, light)
        c.rect(px + 1, py + 3, 11, 7, color)
        c.rect(px + 3, py + 10, 7, 2, shade)
        c.rect(px + 2, py + 4, 2, 2, light)
    if count:
        text_shadow(c, x + 17, y + 12, count, PAL.light, 5, "ra")


def draw_upgrade_icon(c: Canvas, sx: float, sy: float, kind: str, color: tuple[int, int, int, int]) -> None:
    """Use the same high-resolution atlas sprites as the in-game renderer."""
    u, v, w, h = UPGRADE_SPRITES[kind]
    sprite = atlas_crop(u, v, w, h)
    sprite = sprite.resize((c.s(14), c.s(14)), Image.Resampling.LANCZOS)
    c.image.alpha_composite(sprite, c.xy(sx + 2, sy + 2))


def draw_icon(c: Canvas, x: float, y: float, icon: str, color: tuple[int, int, int, int]) -> None:
    shade = mix(color, PAL.edge_dark, 0.32)
    light = mix(color, PAL.light, 0.35)
    if icon == "storage":
        c.rect(x + 2, y + 5, 11, 8, shade)
        c.rect(x + 3, y + 4, 10, 8, color)
        c.rect(x + 3, y + 4, 10, 2, light)
        c.rect(x + 7, y + 7, 3, 2, PAL.gold)
    elif icon == "fluid":
        c.rect(x + 6, y + 1, 4, 3, light)
        c.rect(x + 4, y + 4, 8, 8, color)
        c.rect(x + 5, y + 12, 6, 2, shade)
        c.rect(x + 5, y + 5, 2, 4, light)
    elif icon == "network":
        c.line([(x + 4, y + 4), (x + 12, y + 5), (x + 8, y + 13), (x + 4, y + 4)], shade, 1)
        for dx, dy in ((2, 2), (10, 3), (6, 11)):
            c.rect(x + dx, y + dy, 5, 5, shade)
            c.rect(x + dx + 1, y + dy + 1, 3, 3, color)
    elif icon == "energy":
        pts = [(x + 9, y + 1), (x + 4, y + 8), (x + 8, y + 8), (x + 6, y + 15), (x + 13, y + 7), (x + 9, y + 7)]
        c.polygon([(px + 1, py) for px, py in pts], shade)
        c.polygon(pts, color)
    elif icon == "lock":
        c.rect(x + 3, y + 7, 10, 7, color)
        c.rect(x + 5, y + 3, 6, 2, color)
        c.rect(x + 4, y + 5, 2, 3, color)
        c.rect(x + 10, y + 5, 2, 3, color)
    elif icon == "graph":
        c.rect(x + 2, y + 10, 4, 4, color)
        c.rect(x + 10, y + 4, 4, 4, color)
        c.rect(x + 14, y + 12, 4, 4, color)
        c.line([(x + 6, y + 11), (x + 11, y + 7), (x + 15, y + 13)], shade, 1)
    elif icon == "warning":
        c.rect(x + 7, y + 2, 3, 8, color)
        c.rect(x + 4, y + 6, 9, 7, color)
        c.rect(x + 8, y + 5, 1, 5, PAL.panel_lighter)
        c.rect(x + 8, y + 11, 1, 1, PAL.panel_lighter)


def draw_upgrade_strip(
    c: Canvas,
    x: float,
    y: float,
    kinds: Sequence[str],
    counts: dict[str, str] | None = None,
    hover_kind: str | None = None,
) -> None:
    bevel_rect(c, x, y, 170, 24, PAL.panel_lighter)
    c.text(x + 7, y + 8, "升级", PAL.muted, 7)
    counts = counts or {}
    start = x + 43
    for i, name in enumerate(kinds):
        color = UPGRADE_COLORS[name]
        sx = start + i * 20
        draw_slot(c, sx, y + 3, "hover" if name == hover_kind else "empty")
        draw_upgrade_icon(c, sx, y + 3, name, color)
        if name in counts:
            text_shadow(c, sx + 17, y + 15, counts[name], PAL.light, 5, "ra")


def draw_inventory_grid(c: Canvas, x: float, y: float, rows: int, sample: bool = False) -> None:
    colors = [PAL.gold, PAL.green, PAL.cyan, PAL.blue, PAL.purple, (169, 113, 74, 255)]
    shapes = ["cube", "gem", "orb"]
    for row in range(rows):
        for col in range(9):
            sx, sy = x + col * SLOT, y + row * SLOT
            state = "hover" if sample and row == 0 and col == 2 else "empty"
            draw_slot(c, sx, sy, state)
            if sample and (row * 9 + col) % 4 == 0:
                draw_item_placeholder(c, sx, sy, colors[(row + col) % len(colors)], shapes[(row + col) % len(shapes)], "64" if row == 0 else None)


def draw_scrollbar(c: Canvas, x: float, y: float, h: float, thumb_y: float = 7, thumb_h: float = 24) -> None:
    inset_rect(c, x, y, 7, h, PAL.slot)
    bevel_rect(c, x + 1, y + thumb_y, 5, thumb_h, PAL.panel_lighter)
    c.rect(x + 2, y + thumb_y + 3, 3, 1, PAL.edge_mid)
    c.rect(x + 2, y + thumb_y + thumb_h - 4, 3, 1, PAL.edge_mid)


def draw_item_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "物品", 0)
    chest_x, chest_y = ox + 12, oy + 29
    bevel_rect(c, chest_x - 4, chest_y - 4, 170, 62, PAL.panel)
    draw_inventory_grid(c, chest_x, chest_y, 3, True)
    draw_scrollbar(c, ox + 174, oy + 29, 54, 9, 22)
    draw_upgrade_strip(
        c,
        ox + 8,
        oy + 98,
        ("storage", "fluid", "network", "stress"),
        {"storage": "3", "network": "3", "stress": "1"},
        "fluid",
    )
    c.rect(ox + 7, oy + 130, PANEL_W - 14, 1, PAL.edge_mid)
    c.text(ox + 8, oy + 135, "玩家物品栏", PAL.muted, 7)
    draw_inventory_grid(c, chest_x, oy + 146, 3, False)
    draw_inventory_grid(c, chest_x, oy + 208, 1, False)
    for i, col in enumerate((0, 3, 7)):
        draw_item_placeholder(c, chest_x + col * SLOT, oy + 208, [PAL.blue, PAL.green, PAL.gold][i], "cube")


def draw_fluid_row(c: Canvas, x: float, y: float, label: str, amount: str, color: tuple[int, int, int, int], fill_ratio: float, hover: bool = False) -> None:
    if hover:
        bevel_rect(c, x - 3, y - 4, 152, 20, mix(PAL.blue_soft, PAL.panel_lighter, 0.45), pressed=True)
    inset_rect(c, x, y, 13, 13, PAL.slot)
    filled_h = max(1, round(9 * fill_ratio))
    c.rect(x + 2, y + 11 - filled_h, 9, filled_h, color)
    c.rect(x + 2, y + 2, 9, 1, mix(color, PAL.light, 0.40))
    c.text(x + 18, y + 2, label, PAL.ink if hover else PAL.muted, 7)
    c.text_right(x + 146, y + 2, amount, PAL.ink if hover else PAL.muted, 7)


def draw_fluid_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "流体", 1)
    x, y, w, h = ox + 8, oy + 31, 170, 184
    bevel_rect(c, x, y, w, h, PAL.panel_lighter)
    c.text(x + 9, y + 9, "流体存储", PAL.ink, 8)
    c.text_right(x + w - 10, y + 9, "3 / 8 种", PAL.muted, 7)
    c.text(x + 9, y + 26, "46.2k / 128k mB", PAL.muted, 7)
    inset_rect(c, x + 9, y + 41, w - 18, 10, PAL.slot)
    c.rect(x + 11, y + 43, 91, 6, PAL.cyan)
    c.rect(x + 11, y + 43, 91, 1, mix(PAL.cyan, PAL.light, 0.45))
    draw_fluid_row(c, x + 12, y + 64, "蒸汽", "20.0k mB", (188, 196, 201, 255), 0.78, True)
    draw_fluid_row(c, x + 12, y + 90, "水", "16.0k mB", PAL.blue, 0.62)
    draw_fluid_row(c, x + 12, y + 116, "熔岩", "10.2k mB", (221, 114, 54, 255), 0.48)
    inset_rect(c, x + 12, y + 148, 145, 24, (184, 184, 184, 255))
    c.text_center(x + 84, y + 160, "空槽位", PAL.faint, 7)


def cube_face(c: Canvas, pts: Sequence[tuple[float, float]], fill: tuple[int, int, int, int], outline: tuple[int, int, int, int]) -> None:
    c.polygon(pts, fill)
    c.line([*pts, pts[0]], outline, 1)


def draw_face_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "面配置", 2)
    x, y, w = ox + 8, oy + 30, 170
    bevel_rect(c, x, y, w, 116, PAL.panel_lighter)
    c.text(x + 9, y + 9, "面配置", PAL.ink, 8)
    c.text_right(x + w - 10, y + 9, "正面", PAL.blue, 7)
    cx, cy = x + 85, y + 67
    top = [(cx - 29, cy - 34), (cx + 16, cy - 43), (cx + 43, cy - 21), (cx - 3, cy - 11)]
    left = [(cx - 29, cy - 34), (cx - 3, cy - 11), (cx - 3, cy + 37), (cx - 31, cy + 13)]
    right = [(cx - 3, cy - 11), (cx + 43, cy - 21), (cx + 40, cy + 26), (cx - 3, cy + 37)]
    cube_face(c, left, (155, 172, 180, 255), PAL.edge_dark)
    cube_face(c, right, (178, 202, 212, 255), PAL.blue)
    cube_face(c, top, (218, 226, 229, 255), PAL.edge_mid)
    draw_icon(c, cx + 10, cy - 9, "fluid", PAL.cyan)
    draw_icon(c, cx - 22, cy, "storage", PAL.green)
    c.text_center(cx + 19, cy + 16, "正", PAL.blue, 8)
    c.text_center(cx - 16, cy + 24, "左", PAL.ink, 7)
    c.text_center(cx + 5, cy - 27, "上", PAL.ink, 7)

    bevel_rect(c, x, y + 124, w, 78, PAL.panel_lighter)
    c.text(x + 9, y + 133, "正面", PAL.ink, 8)
    c.rect(x + 65, y + 145, 1, 49, PAL.edge_mid)
    c.text(x + 9, y + 151, "功能", PAL.muted, 7)
    draw_button(c, x + 9, y + 162, 50, 18, "流体", "hover")
    c.text(x + 76, y + 151, "流体模式", PAL.muted, 7)
    labels = [("禁", "normal"), ("入", "selected"), ("出", "hover"), ("双", "gold")]
    for i, (label, state) in enumerate(labels):
        draw_chip(c, x + 76 + i * 23, y + 162, 20, 18, label, state, 7)
    c.text(x + 76, y + 186, "应力输出：同向 64rpm", PAL.faint, 6)


def draw_switch(c: Canvas, x: float, y: float, on: bool) -> None:
    inset_rect(c, x, y, 34, 14, PAL.slot)
    fill = PAL.green if on else PAL.edge_mid
    c.rect(x + (18 if on else 3), y + 3, 11, 8, fill)
    c.rect(x + (18 if on else 3), y + 3, 11, 1, mix(fill, PAL.light, 0.42))


def draw_settings_card(c: Canvas, x: float, y: float, w: float, h: float, title: str, sub: str, icon: str | None = None) -> None:
    bevel_rect(c, x, y, w, h, PAL.panel_lighter)
    if icon:
        draw_icon(c, x + 7, y + 8, icon, PAL.blue)
        tx = x + 27
    else:
        tx = x + 9
    c.text(tx, y + 8, title, PAL.ink, 8)
    c.text(tx, y + 22, sub, PAL.muted, 7)


def draw_settings_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "设置", 3)
    x, w = ox + 8, 170
    y = oy + 29
    draw_settings_card(c, x, y, w, 34, "可视化节点", "编辑连线与过滤", "graph")
    draw_button(c, x + w - 56, y + 9, 46, 16, "打开", "selected")

    y += 39
    bevel_rect(c, x, y, w, 42, PAL.panel_lighter)
    c.text(x + 9, y + 8, "箱子标识", PAL.ink, 8)
    inset_rect(c, x + 10, y + 22, 105, 16, PAL.panel)
    c.text(x + 16, y + 27, "core-input-01", PAL.muted, 7)
    draw_button(c, x + 122, y + 22, 42, 16, "保存", "hover")

    y += 47
    bevel_rect(c, x, y, w, 36, PAL.panel_lighter)
    c.text(x + 9, y + 8, "箱子权限", PAL.ink, 8)
    for i, (label, state) in enumerate((("私有", "selected"), ("团队", "normal"), ("公开", "normal"), ("空间", "disabled"))):
        draw_chip(c, x + 10 + i * 38, y + 20, 34, 14, label, state, 6)

    y += 41
    draw_settings_card(c, x, y, w, 36, "离线模拟", "卸载后快照顶替运行", "warning")
    draw_switch(c, x + w - 46, y + 15, True)

    y += 41
    bevel_rect(c, x, y, w, 31, PAL.panel_lighter)
    c.text(x + 9, y + 8, "产率统计", PAL.ink, 8)
    c.text(x + 9, y + 22, "分组", PAL.faint, 7)
    inset_rect(c, x + 52, y + 10, 76, 16, PAL.panel)
    c.text(x + 59, y + 15, "默认", PAL.muted, 7)
    c.line([(x + 119, y + 15), (x + 123, y + 19), (x + 127, y + 15)], PAL.edge_dark, 1)
    draw_switch(c, x + w - 39, y + 9, False)


def draw_components_preview() -> Image.Image:
    c = Canvas(760, 370, SCALE, (244, 250, 255, 255))
    c.text(20, 18, f"Pocket Homestead Chest UI Components {VERSION}", PAL.ink, 10)
    c.text(20, 35, "蓝白统一风格组件预览：低装饰、像素边框、清晰槽位", PAL.muted, 7)

    x, y = 26, 62
    c.text(x, y - 18, "Slots", PAL.ink, 8)
    for i, state in enumerate(("empty", "hover", "selected", "locked")):
        draw_slot(c, x + i * 28, y, state)
        c.text_center(x + i * 28 + 9, y + 26, state, PAL.muted, 5)
    draw_item_placeholder(c, x + 124, y, PAL.gold, "cube", "64")

    x, y = 230, 62
    c.text(x, y - 18, "Buttons", PAL.ink, 8)
    for i, state in enumerate(("normal", "hover", "selected", "disabled")):
        draw_button(c, x, y + i * 23, 70, 17, state, state)

    x, y = 340, 62
    c.text(x, y - 18, "Chips", PAL.ink, 8)
    states = ("normal", "hover", "selected", "gold", "danger", "disabled")
    for i, state in enumerate(states):
        draw_chip(c, x + (i % 3) * 50, y + (i // 3) * 26, 42, 17, state[:3], state, 6)

    x, y = 530, 62
    c.text(x, y - 18, "Upgrade Icons", PAL.ink, 8)
    icons = [(name, UPGRADE_COLORS[name]) for name in ("storage", "fluid", "network", "energy", "stress")]
    for i, (name, color) in enumerate(icons):
        draw_slot(c, x + i * 30, y, "empty")
        draw_upgrade_icon(c, x + i * 30, y, name, color)
        c.text_center(x + i * 30 + 9, y + 26, name[:3], PAL.muted, 5)

    x, y = 530, 123
    c.text(x, y - 14, "Dynamic Upgrade Rows", PAL.ink, 8)
    draw_upgrade_strip(c, x, y, ("storage", "fluid", "network"), {"storage": "3", "network": "1"}, "fluid")
    draw_upgrade_strip(c, x, y + 31, ("storage", "energy", "stress"), {"stress": "1"}, "stress")

    x, y = 26, 185
    c.text(x, y - 17, "Panels", PAL.ink, 8)
    bevel_rect(c, x, y, 170, 58, PAL.panel_lighter)
    c.text(x + 10, y + 9, "蓝白面板", PAL.ink, 8)
    c.text(x + 10, y + 24, "1px 明暗边框，可直接切九宫格", PAL.muted, 7)
    inset_rect(c, x + 10, y + 39, 150, 10, PAL.slot)

    x, y = 230, 185
    c.text(x, y - 17, "Scrollbars", PAL.ink, 8)
    draw_scrollbar(c, x, y, 72, 12, 28)
    draw_scrollbar(c, x + 18, y, 72, 30, 20)

    x, y = 300, 185
    c.text(x, y - 17, "Inputs / Switches", PAL.ink, 8)
    inset_rect(c, x, y, 130, 20, PAL.panel)
    c.text(x + 8, y + 6, "core-input-01", PAL.muted, 7)
    draw_switch(c, x, y + 34, True)
    draw_switch(c, x + 44, y + 34, False)

    x, y = 500, 215
    c.text(x, y - 17, "Face Cube", PAL.ink, 8)
    cube_face(c, [(x, y + 16), (x + 42, y + 6), (x + 68, y + 25), (x + 24, y + 37)], (218, 226, 229, 255), PAL.edge_mid)
    cube_face(c, [(x + 24, y + 37), (x + 68, y + 25), (x + 66, y + 69), (x + 24, y + 82)], (178, 202, 212, 255), PAL.blue)
    cube_face(c, [(x, y + 16), (x + 24, y + 37), (x + 24, y + 82), (x - 4, y + 56)], (155, 172, 180, 255), PAL.edge_dark)
    draw_icon(c, x + 41, y + 43, "fluid", PAL.cyan)

    return c.image


def draw_chest_preview() -> Image.Image:
    margin = 26
    gap = 26
    label_h = 25
    width = margin * 2 + PANEL_W * 4 + gap * 3
    height = margin * 2 + label_h + PANEL_H
    c = Canvas(width, height, SCALE, (244, 250, 255, 255))
    c.text(margin, 16, f"Pocket Homestead Chest UI Preview {VERSION}", PAL.ink, 10)
    c.text_right(width - margin, 16, "deterministic PNG preview, not wired into game", PAL.muted, 7)

    pages = [
        ("物品页", draw_item_page),
        ("流体页", draw_fluid_page),
        ("面配置页", draw_face_page),
        ("设置页", draw_settings_page),
    ]
    for i, (label, fn) in enumerate(pages):
        ox = margin + i * (PANEL_W + gap)
        oy = margin + label_h
        c.text_center(ox + PANEL_W / 2, oy - 13, label, PAL.ink, 8)
        fn(c, ox, oy)

    return c.image


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    preview = draw_chest_preview()
    components = draw_components_preview()
    preview_path = OUT_DIR / f"chest-ui-preview-{VERSION}.png"
    components_path = OUT_DIR / f"chest-ui-components-{VERSION}.png"
    preview.save(preview_path)
    components.save(components_path)
    print(preview_path)
    print(components_path)


if __name__ == "__main__":
    main()
