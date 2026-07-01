from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "client-ui" / "previews"
FONT_PATH = ROOT / "src" / "main" / "resources" / "assets" / "pockethomestead" / "font" / "notosanssc-regular.ttf"

VERSION = "v4"
SCALE = 4
PANEL_W = 186
PANEL_H = 250
SLOT = 18


@dataclass(frozen=True)
class Palette:
    ink: tuple[int, int, int, int] = (33, 55, 75, 255)
    muted: tuple[int, int, int, int] = (96, 126, 151, 255)
    faint: tuple[int, int, int, int] = (151, 174, 193, 255)
    blue: tuple[int, int, int, int] = (55, 164, 231, 255)
    cyan: tuple[int, int, int, int] = (45, 213, 216, 255)
    gold: tuple[int, int, int, int] = (226, 184, 78, 255)
    green: tuple[int, int, int, int] = (80, 198, 144, 255)
    red: tuple[int, int, int, int] = (226, 105, 105, 255)
    panel: tuple[int, int, int, int] = (252, 254, 255, 255)
    panel_2: tuple[int, int, int, int] = (241, 249, 253, 255)
    ceramic: tuple[int, int, int, int] = (251, 254, 255, 255)
    sunken: tuple[int, int, int, int] = (226, 239, 248, 255)
    line: tuple[int, int, int, int] = (169, 205, 230, 255)
    line_soft: tuple[int, int, int, int] = (224, 239, 249, 255)
    dark_line: tuple[int, int, int, int] = (103, 168, 208, 255)
    white: tuple[int, int, int, int] = (255, 255, 255, 255)
    clear: tuple[int, int, int, int] = (0, 0, 0, 0)


PAL = Palette()


def rgba(hex_value: int, alpha: int = 255) -> tuple[int, int, int, int]:
    return ((hex_value >> 16) & 255, (hex_value >> 8) & 255, hex_value & 255, alpha)


def mix(a: tuple[int, int, int, int], b: tuple[int, int, int, int], t: float) -> tuple[int, int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))  # type: ignore[return-value]


class Canvas:
    def __init__(self, width: int, height: int, scale: int = SCALE, bg: tuple[int, int, int, int] = (236, 242, 247, 255)):
        self.scale = scale
        self.image = Image.new("RGBA", (width * scale, height * scale), bg)
        self.draw = ImageDraw.Draw(self.image)
        self.fonts: dict[int, ImageFont.FreeTypeFont | ImageFont.ImageFont] = {}

    def s(self, value: float) -> int:
        return round(value * self.scale)

    def xy(self, x: float, y: float) -> tuple[int, int]:
        return self.s(x), self.s(y)

    def box(self, x: float, y: float, w: float, h: float) -> tuple[int, int, int, int]:
        return self.s(x), self.s(y), self.s(x + w), self.s(y + h)

    def font(self, size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        key = size * self.scale
        if key not in self.fonts:
            if FONT_PATH.exists():
                self.fonts[key] = ImageFont.truetype(str(FONT_PATH), key)
            else:
                self.fonts[key] = ImageFont.load_default()
        return self.fonts[key]

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
        self.text(x, y, label, color, size, "ma")

    def text_right(self, x: float, y: float, label: str, color: tuple[int, int, int, int] = PAL.ink, size: int = 8) -> None:
        self.text(x, y, label, color, size, "ra")

    def round(
        self,
        x: float,
        y: float,
        w: float,
        h: float,
        r: float,
        fill: tuple[int, int, int, int],
        outline: tuple[int, int, int, int] | None = None,
        width: int = 1,
    ) -> None:
        fill_arg = None if fill[3] == 0 else fill
        self.draw.rounded_rectangle(self.box(x, y, w, h), radius=self.s(r), fill=fill_arg, outline=outline, width=max(1, self.s(width)))

    def rect(self, x: float, y: float, w: float, h: float, fill: tuple[int, int, int, int]) -> None:
        self.draw.rectangle(self.box(x, y, w, h), fill=fill)

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

    def line(self, xy: Sequence[tuple[float, float]], fill: tuple[int, int, int, int], width: float = 1.0) -> None:
        self.draw.line([self.xy(x, y) for x, y in xy], fill=fill, width=max(1, self.s(width)), joint="curve")

    def shadow(self, x: float, y: float, w: float, h: float, r: float, strength: int = 26) -> None:
        for i, alpha in ((4, strength // 5), (3, strength // 3), (2, strength // 2), (1, strength)):
            self.round(x - i * 0.45, y + i * 0.65, w + i * 0.9, h + i * 0.9, r + i * 0.45, (76, 154, 207, alpha))

    def gradient_round(
        self,
        x: float,
        y: float,
        w: float,
        h: float,
        r: float,
        top: tuple[int, int, int, int],
        bottom: tuple[int, int, int, int],
        outline: tuple[int, int, int, int] | None = None,
    ) -> None:
        layer = Image.new("RGBA", self.image.size, (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        x0, y0, x1, y1 = self.box(x, y, w, h)
        height = max(1, y1 - y0)
        for py in range(y0, y1):
            t = (py - y0) / height
            ld.line([(x0, py), (x1, py)], fill=mix(top, bottom, t))
        mask = Image.new("L", self.image.size, 0)
        md = ImageDraw.Draw(mask)
        md.rounded_rectangle((x0, y0, x1, y1), radius=self.s(r), fill=255)
        self.image.alpha_composite(Image.composite(layer, Image.new("RGBA", self.image.size, (0, 0, 0, 0)), mask))
        if outline:
            self.round(x, y, w, h, r, (0, 0, 0, 0), outline)


def draw_micro_grid(c: Canvas, x: float, y: float, w: float, h: float) -> None:
    for gx in range(math.floor(x) + 8, math.floor(x + w), 16):
        c.line([(gx, y + 1), (gx, y + h - 1)], (84, 183, 226, 24), 0.65)
    for gy in range(math.floor(y) + 8, math.floor(y + h), 16):
        c.line([(x + 1, gy), (x + w - 1, gy)], (84, 183, 226, 18), 0.65)


def draw_panel_shell(c: Canvas, x: int, y: int, title: str, page: str, active_tab: int) -> None:
    c.shadow(x, y, PANEL_W, PANEL_H, 7, 20)
    c.gradient_round(x, y, PANEL_W, PANEL_H, 7, PAL.white, (240, 249, 254, 255), PAL.dark_line)
    c.round(x + 1, y + 1, PANEL_W - 2, PANEL_H - 2, 6, (0, 0, 0, 0), PAL.line_soft)
    c.round(x + 3, y + 3, PANEL_W - 6, PANEL_H - 6, 5, (255, 255, 255, 26), None)
    c.round(x + 3.5, y + 3.5, PANEL_W - 7, PANEL_H - 7, 5, (0, 0, 0, 0), (214, 246, 255, 160))
    c.rect(x + 2, y + 20, PANEL_W - 4, 1, PAL.line_soft)
    c.gradient_round(x + 4, y + 4, PANEL_W - 8, 14, 4, (255, 255, 255, 245), (235, 248, 254, 230), None)
    c.text(x + 9, y + 6, title, PAL.ink, 8)
    c.text_right(x + PANEL_W - 49, y + 6, page, PAL.muted, 7)
    draw_page_button(c, x + PANEL_W - 47, y + 3, 39, 14, "下一页", "normal")
    draw_corner_screws(c, x, y, PANEL_W, PANEL_H)
    draw_tabs(c, x + 9, y + PANEL_H - 13, active_tab)


def draw_corner_screws(c: Canvas, x: float, y: float, w: float, h: float) -> None:
    for sx, sy in ((x + 5, y + 5), (x + w - 7, y + 5), (x + 5, y + h - 7), (x + w - 7, y + h - 7)):
        c.round(sx, sy, 2.2, 2.2, 1.1, (147, 176, 196, 150))
        c.rect(sx + 0.6, sy + 0.9, 1.0, 0.4, (255, 255, 255, 135))


def draw_tabs(c: Canvas, x: float, y: float, active: int) -> None:
    labels = ["物", "液", "面", "设"]
    for i, label in enumerate(labels):
        state = "selected" if i == active else "normal"
        draw_chip(c, x + i * 19, y, 16, 9, label, state, size=6)


def draw_page_button(c: Canvas, x: float, y: float, w: float, h: float, label: str, state: str) -> None:
    if state == "selected":
        top, bottom, outline, color = (246, 255, 255, 255), (214, 247, 252, 255), PAL.cyan, (32, 117, 139, 255)
    elif state == "hover":
        top, bottom, outline, color = (255, 255, 255, 255), (231, 249, 255, 255), PAL.blue, PAL.ink
    elif state == "disabled":
        top, bottom, outline, color = (236, 244, 249, 255), (224, 235, 243, 255), PAL.line_soft, PAL.faint
    else:
        top, bottom, outline, color = (255, 255, 255, 255), (239, 249, 254, 255), PAL.line, PAL.muted
    # Japanese-style soft capsule button: rounder, airy, with a tiny decorative mark.
    radius = min(8, h / 2)
    c.round(x + 0.6, y + 0.9, w, h, radius, (113, 185, 226, 24))
    c.gradient_round(x, y, w, h, radius, top, bottom, outline)
    c.round(x + 1.4, y + 1.2, w - 2.8, h - 2.4, max(1, radius - 1), (0, 0, 0, 0), (255, 255, 255, 170), 0.7)
    c.line([(x + 4, y + 2.4), (x + w - 9, y + 2.4)], (255, 255, 255, 165), 0.65)
    c.text_center(x + w / 2, y + 3.8, label, color, 6)
    draw_button_corner_mark(c, x, y, w, h, state)


def draw_button_corner_mark(c: Canvas, x: float, y: float, w: float, h: float, state: str) -> None:
    if w < 22 or h < 12:
        return
    if state == "disabled":
        mark = (158, 184, 204, 120)
    elif state == "selected":
        mark = (37, 201, 207, 210)
    elif state == "hover":
        mark = (55, 164, 231, 210)
    else:
        mark = (118, 180, 218, 145)
    bx = x + w - 8.1
    by = y + h - 7.0
    c.ellipse(bx, by, 4.4, 4.4, mix(mark, PAL.white, 0.55), mark, 0.65)
    c.ellipse(bx + 1.0, by + 0.7, 1.4, 1.4, (255, 255, 255, 175))
    c.line([(bx + 1.3, by + 2.4), (bx + 2.2, by + 3.1), (bx + 3.3, by + 1.5)], mark, 0.7)


def draw_chip(c: Canvas, x: float, y: float, w: float, h: float, label: str, state: str = "normal", size: int = 7) -> None:
    mapping = {
        "normal": ((246, 251, 254, 255), (225, 236, 245, 255), PAL.line, PAL.muted),
        "hover": ((255, 255, 255, 255), (220, 241, 249, 255), PAL.blue, PAL.ink),
        "selected": ((231, 249, 247, 255), (196, 235, 232, 255), PAL.cyan, (33, 109, 121, 255)),
        "danger": ((255, 241, 239, 255), (246, 217, 213, 255), (226, 124, 112, 255), PAL.red),
        "gold": ((255, 249, 227, 255), (239, 222, 165, 255), PAL.gold, (126, 88, 32, 255)),
        "disabled": ((229, 237, 242, 255), (215, 225, 232, 255), PAL.line_soft, PAL.faint),
    }
    top, bottom, outline, color = mapping[state]
    c.gradient_round(x, y, w, h, min(4, h / 2), top, bottom, outline)
    c.text_center(x + w / 2, y + max(2.0, h / 2 - 2.4), label, color, size)


def draw_slot(c: Canvas, x: float, y: float, state: str = "empty") -> None:
    if state == "hover":
        fill = (239, 253, 255, 255)
        inner = (255, 255, 255, 210)
        outline = PAL.blue
    elif state == "selected":
        fill = (238, 255, 251, 255)
        inner = (255, 255, 255, 230)
        outline = PAL.cyan
    elif state == "locked":
        fill = (219, 226, 232, 255)
        inner = (205, 215, 223, 255)
        outline = PAL.line
    else:
        fill = (227, 241, 250, 255)
        inner = (245, 252, 255, 255)
        outline = (143, 199, 230, 255)
    c.round(x - 1, y - 1, 18, 18, 3, (97, 174, 218, 62))
    c.round(x, y, 16, 16, 2.5, fill, outline)
    c.rect(x + 1, y + 1, 14, 1, (255, 255, 255, 180))
    c.rect(x + 1, y + 14, 14, 1, (86, 154, 198, 55))
    c.round(x + 2, y + 2, 12, 12, 2, inner)
    if state == "locked":
        draw_icon(c, x + 4, y + 4, "lock", PAL.faint)


def draw_item_placeholder(c: Canvas, x: float, y: float, color: tuple[int, int, int, int], shape: str, count: str | None = None) -> None:
    c.shadow(x + 2, y + 3, 11, 11, 3, 7)
    if shape == "cube":
        pts = [(x + 4, y + 3), (x + 12, y + 5), (x + 12, y + 12), (x + 4, y + 14), (x + 2, y + 7)]
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=color)
        c.line([(x + 4, y + 3), (x + 4, y + 14), (x + 12, y + 12)], (255, 255, 255, 80), 0.7)
    elif shape == "gem":
        pts = [(x + 8, y + 2), (x + 14, y + 7), (x + 10, y + 14), (x + 4, y + 14), (x + 2, y + 7)]
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=color)
        c.line([(x + 5, y + 5), (x + 12, y + 7), (x + 8, y + 12)], (255, 255, 255, 95), 0.7)
    else:
        c.round(x + 3, y + 3, 11, 11, 5, color, mix(color, (0, 0, 0, 255), 0.18))
        c.round(x + 6, y + 5, 5, 4, 2, (255, 255, 255, 75))
    if count:
        tw = 5 + len(count) * 3.1
        c.round(x + 16 - tw, y + 11.3, tw, 5.8, 2, (90, 169, 218, 225), (255, 255, 255, 150), 0.45)
        c.text_right(x + 16.2, y + 10.5, count, PAL.white, 4)


def draw_mini_symbol(c: Canvas, x: float, y: float, kind: str, color: tuple[int, int, int, int]) -> None:
    """Draw a tiny functional symbol inside an upgrade badge, bounded to 8x8 px."""
    line = mix(color, (20, 92, 126, 255), 0.12)
    if kind == "storage":
        c.round(x + 0.7, y + 1.4, 6.7, 5.5, 1.6, (255, 255, 255, 230), line, 0.55)
        c.rect(x + 2.0, y + 3.0, 4.2, 0.75, color)
        c.rect(x + 2.0, y + 5.0, 4.2, 0.75, mix(color, PAL.white, 0.08))
        c.ellipse(x + 5.9, y + 0.7, 1.4, 1.4, PAL.white)
    elif kind == "fluid":
        pts = [(x + 4.0, y + 0.5), (x + 7.0, y + 4.1), (x + 5.9, y + 6.8), (x + 4.0, y + 7.6), (x + 2.1, y + 6.8), (x + 1.0, y + 4.1)]
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=color)
        c.line([*pts, pts[0]], line, 0.45)
        c.ellipse(x + 3.1, y + 4.1, 1.8, 2.4, (255, 255, 255, 145))
        c.ellipse(x + 5.0, y + 2.2, 1.0, 1.0, (255, 255, 255, 180))
    elif kind == "network":
        nodes = ((1.2, 1.2), (6.0, 1.5), (3.5, 6.0))
        c.line([(x + 2.0, y + 2.0), (x + 6.7, y + 2.2), (x + 4.2, y + 6.7), (x + 2.0, y + 2.0)], line, 0.8)
        for dx, dy in nodes:
            c.ellipse(x + dx, y + dy, 2.2, 2.2, mix(color, PAL.white, 0.15), line, 0.4)
            c.ellipse(x + dx + 0.6, y + dy + 0.4, 0.65, 0.65, PAL.white)
    elif kind == "energy":
        pts = [(x + 4.5, y + 0.8), (x + 2.2, y + 4.3), (x + 4.1, y + 4.3), (x + 3.0, y + 7.2), (x + 6.5, y + 3.2), (x + 4.7, y + 3.2)]
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=color)
        c.line([*pts, pts[0]], line, 0.45)
        c.ellipse(x + 6.1, y + 1.4, 1.0, 1.0, (255, 255, 255, 160))
    elif kind == "stress":
        cx, cy = x + 4.0, y + 4.0
        for a in range(0, 360, 60):
            px = cx + math.cos(math.radians(a)) * 3.0
            py = cy + math.sin(math.radians(a)) * 3.0
            c.ellipse(px - 0.55, py - 0.55, 1.1, 1.1, color)
        c.ellipse(x + 1.5, y + 1.5, 5.0, 5.0, (255, 252, 255, 235), line, 0.45)
        c.ellipse(x + 2.6, y + 2.6, 2.8, 2.8, mix(color, PAL.white, 0.10), line, 0.45)
        c.ellipse(x + 3.45, y + 3.45, 1.1, 1.1, PAL.white)


def draw_upgrade_icon(c: Canvas, sx: float, sy: float, kind: str, color: tuple[int, int, int, int]) -> None:
    """Draw a contained 18x18 upgrade icon. Nothing should escape the slot bounds."""
    # Badge stays inside the 16x16 slot interior. The actual functional symbol is smaller
    # so it reads more like a refined sprite than a big sign.
    badge_x, badge_y, badge = sx + 3.0, sy + 3.0, 10.8
    c.ellipse(badge_x + 0.8, badge_y + 1.2, badge, badge, (82, 170, 221, 32))
    c.gradient_round(badge_x, badge_y, badge, badge, 4.2, mix(color, PAL.white, 0.42), color, mix(color, (20, 112, 150, 255), 0.18))
    c.ellipse(badge_x + 2.1, badge_y + 1.3, 3.4, 2.2, (255, 255, 255, 135))
    c.ellipse(badge_x + 8.2, badge_y + 7.9, 1.9, 1.9, mix(color, PAL.white, 0.62))
    draw_mini_symbol(c, badge_x + 1.45, badge_y + 1.55, kind, mix(color, (255, 255, 255, 255), 0.06))
    # Tiny mascot-like sparkle clipped by placement rather than a mask: still inside slot.
    c.ellipse(sx + 11.8, sy + 3.1, 2.2, 2.2, PAL.white, mix(color, PAL.white, 0.2), 0.35)
    c.ellipse(sx + 12.45, sy + 3.75, 0.75, 0.75, color)


def draw_icon(c: Canvas, x: float, y: float, icon: str, color: tuple[int, int, int, int]) -> None:
    if icon == "storage":
        base = mix(color, PAL.white, 0.10)
        shade = mix(color, (40, 92, 134, 255), 0.20)
        c.shadow(x + 1, y + 3, 11, 9, 3, 7)
        c.round(x + 1.4, y + 3.2, 10.6, 8.6, 2.8, base, shade)
        c.round(x + 3.1, y + 1.9, 6.8, 3.4, 1.7, mix(color, PAL.white, 0.22), shade)
        c.rect(x + 3.1, y + 5.8, 6.8, 1.1, (255, 255, 255, 120))
        c.rect(x + 3.1, y + 8.2, 6.8, 1.1, (255, 255, 255, 105))
        c.ellipse(x + 9.2, y + 2.2, 2.2, 2.2, PAL.white)
        c.ellipse(x + 9.8, y + 2.8, 1.0, 1.0, mix(color, PAL.white, 0.05))
    elif icon == "fluid":
        base = mix(color, PAL.white, 0.06)
        shade = mix(color, (20, 112, 148, 255), 0.16)
        pts = [(x + 6.8, y + 1.1), (x + 12.2, y + 7.3), (x + 10.4, y + 12.1), (x + 6.8, y + 13.7), (x + 3.2, y + 12.1), (x + 1.4, y + 7.3)]
        c.shadow(x + 2.4, y + 3.2, 9, 10, 5, 7)
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=base)
        c.line([*pts, pts[0]], shade, 0.85)
        c.ellipse(x + 5.1, y + 7.0, 3.9, 4.8, (255, 255, 255, 115))
        c.ellipse(x + 7.8, y + 3.8, 1.8, 1.8, (255, 255, 255, 150))
        c.ellipse(x + 11.2, y + 10.5, 1.5, 1.5, mix(color, PAL.white, 0.55))
    elif icon == "network":
        soft = mix(color, PAL.white, 0.18)
        shade = mix(color, (31, 118, 94, 255), 0.22)
        nodes = ((2.0, 2.0), (10.1, 2.2), (6.2, 10.2))
        c.line([(x + 3.8, y + 3.8), (x + 11.9, y + 4.0), (x + 8.1, y + 12.0), (x + 3.8, y + 3.8)], shade, 1.25)
        c.line([(x + 4.0, y + 4.0), (x + 11.6, y + 4.2), (x + 8.0, y + 11.7), (x + 4.0, y + 4.0)], (255, 255, 255, 85), 0.65)
        for dx, dy in nodes:
            c.ellipse(x + dx - 0.6, y + dy - 0.6, 5.0, 5.0, (63, 142, 174, 38))
            c.ellipse(x + dx, y + dy, 3.8, 3.8, soft, shade)
            c.ellipse(x + dx + 0.9, y + dy + 0.7, 1.1, 1.1, PAL.white)
    elif icon == "energy":
        base = mix(color, PAL.white, 0.10)
        shade = mix(color, (150, 102, 22, 255), 0.18)
        c.shadow(x + 2.2, y + 1.8, 10, 12, 3, 7)
        c.round(x + 2.0, y + 2.4, 9.5, 10.8, 2.5, (255, 251, 222, 255), shade)
        c.rect(x + 4.2, y + 1.3, 5.0, 1.7, shade)
        pts = [(x + 7.4, y + 3.2), (x + 4.5, y + 8.0), (x + 7.1, y + 8.0), (x + 5.6, y + 12.3), (x + 10.5, y + 6.6), (x + 7.9, y + 6.6)]
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=base)
        c.line([*pts, pts[0]], shade, 0.65)
        c.ellipse(x + 9.8, y + 3.0, 1.8, 1.8, (255, 255, 255, 130))
    elif icon == "stress":
        base = mix(color, PAL.white, 0.12)
        shade = mix(color, (95, 54, 119, 255), 0.20)
        cx, cy = x + 7.0, y + 7.1
        for a in range(0, 360, 45):
            px = cx + math.cos(math.radians(a)) * 4.9
            py = cy + math.sin(math.radians(a)) * 4.9
            c.ellipse(px - 1.4, py - 1.4, 2.8, 2.8, base, shade, 0.5)
        c.ellipse(x + 2.3, y + 2.4, 9.4, 9.4, (255, 250, 255, 255), shade, 0.8)
        c.ellipse(x + 4.5, y + 4.6, 5.0, 5.0, mix(color, PAL.white, 0.20), shade, 0.7)
        c.ellipse(x + 6.0, y + 6.0, 2.0, 2.0, PAL.white)
        c.line([(x + 3.0, y + 3.7), (x + 11.2, y + 10.0)], (255, 255, 255, 95), 0.6)
    elif icon == "lock":
        c.round(x + 1, y + 5, 10, 7, 2, color)
        c.round(x + 3, y + 1, 6, 7, 3, (0, 0, 0, 0), color, 1)
    elif icon == "graph":
        c.round(x + 1, y + 8, 3, 3, 2, color)
        c.round(x + 9, y + 2, 3, 3, 2, color)
        c.round(x + 13, y + 10, 3, 3, 2, color)
        c.line([(x + 4, y + 9), (x + 10, y + 4), (x + 14, y + 11)], color, 1.2)
    elif icon == "warning":
        pts = [(x + 7, y + 1), (x + 14, y + 13), (x, y + 13)]
        c.draw.polygon([c.xy(px, py) for px, py in pts], fill=color)
        c.rect(x + 6.4, y + 5, 1.2, 4, PAL.white)
        c.rect(x + 6.4, y + 10.4, 1.2, 1.2, PAL.white)


def draw_upgrade_strip(c: Canvas, x: float, y: float) -> None:
    c.gradient_round(x, y, 170, 24, 5, (246, 251, 253, 255), (225, 238, 246, 255), PAL.line)
    c.text(x + 7, y + 7, "升级", PAL.muted, 7)
    icons = [("storage", PAL.blue), ("fluid", PAL.cyan), ("network", PAL.green), ("energy", PAL.gold), ("stress", (188, 118, 198, 255))]
    start = x + 43
    for i, (name, color) in enumerate(icons):
        sx = start + i * 20
        draw_slot(c, sx, y + 4, "hover" if i == 1 else "empty")
        draw_upgrade_icon(c, sx, y + 4, name, color)
        if i in (0, 2):
            c.round(sx + 10, y + 14, 7, 5, 2, (90, 169, 218, 225), (255, 255, 255, 150), 0.45)
            c.text_right(sx + 17, y + 13.2, "3", PAL.white, 4)


def draw_inventory_grid(c: Canvas, x: float, y: float, rows: int, sample: bool = False) -> None:
    colors = [PAL.gold, PAL.green, PAL.cyan, PAL.blue, (185, 112, 198, 255), (198, 129, 96, 255)]
    shapes = ["cube", "gem", "orb"]
    for row in range(rows):
        for col in range(9):
            sx, sy = x + col * SLOT, y + row * SLOT
            state = "hover" if sample and row == 0 and col == 2 else "empty"
            draw_slot(c, sx, sy, state)
            if sample and (row * 9 + col) % 4 == 0:
                draw_item_placeholder(c, sx, sy, colors[(row + col) % len(colors)], shapes[(row + col) % len(shapes)], "1k" if row == 0 else None)


def draw_scrollbar(c: Canvas, x: float, y: float, h: float, thumb_y: float = 7, thumb_h: float = 24) -> None:
    c.round(x, y, 6, h, 3, (203, 221, 233, 255), PAL.line)
    c.gradient_round(x + 1, y + thumb_y, 4, thumb_h, 2, (100, 196, 205, 255), (53, 142, 196, 255), (44, 125, 166, 255))
    c.rect(x + 2, y + thumb_y + 2, 2, 1, (255, 255, 255, 120))


def draw_item_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "物品", 0)
    chest_x, chest_y = ox + 12, oy + 28
    c.gradient_round(chest_x - 4, chest_y - 4, 170, 62, 5, (237, 249, 255, 255), (221, 239, 249, 255), PAL.line)
    draw_micro_grid(c, chest_x - 3, chest_y - 3, 168, 60)
    draw_inventory_grid(c, chest_x, chest_y, 3, True)
    draw_scrollbar(c, ox + 174, oy + 29, 54, 9, 22)
    draw_upgrade_strip(c, ox + 8, oy + 98)
    c.rect(ox + 2, oy + 129, PANEL_W - 4, 1, PAL.line_soft)
    c.text(ox + 8, oy + 133, "玩家物品栏", PAL.muted, 7)
    draw_inventory_grid(c, chest_x, oy + 145, 3, False)
    draw_inventory_grid(c, chest_x, oy + 207, 1, False)
    for col in (0, 3, 7):
        sx = chest_x + col * SLOT
        sy = oy + 207
        draw_item_placeholder(c, sx, sy, [PAL.blue, PAL.green, PAL.gold][col % 3], "cube")


def draw_fluid_row(c: Canvas, x: float, y: float, label: str, amount: str, color: tuple[int, int, int, int], fill_ratio: float, hover: bool = False) -> None:
    if hover:
        c.gradient_round(x - 3, y - 4, 152, 20, 4, (255, 255, 255, 245), (231, 247, 249, 255), PAL.cyan)
    c.round(x, y, 12, 12, 3, mix(color, (255, 255, 255, 255), 0.15), mix(color, (0, 0, 0, 255), 0.18))
    c.rect(x + 1, y + 2 + (1 - fill_ratio) * 8, 10, 10 - (1 - fill_ratio) * 8, color)
    c.line([(x + 2, y + 2), (x + 10, y + 2)], (255, 255, 255, 120), 0.7)
    c.text(x + 17, y + 1, label, PAL.ink if hover else PAL.muted, 7)
    c.text_right(x + 145, y + 1, amount, PAL.ink if hover else PAL.muted, 7)


def draw_fluid_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "流体", 1)
    x, y, w, h = ox + 8, oy + 32, 170, 184
    c.gradient_round(x, y, w, h, 6, (253, 255, 255, 255), (237, 249, 254, 255), PAL.line)
    c.text(x + 10, y + 9, "流体存储", PAL.ink, 8)
    c.text_right(x + w - 10, y + 9, "3 / 8 种", PAL.muted, 7)
    c.text(x + 10, y + 25, "46.2k / 128k mB", PAL.muted, 7)
    c.gradient_round(x + 10, y + 39, w - 20, 8, 4, (203, 219, 230, 255), (218, 231, 239, 255), PAL.line)
    c.gradient_round(x + 10, y + 39, 92, 8, 4, (89, 205, 197, 255), (61, 145, 203, 255), None)
    c.rect(x + 12, y + 41, 86, 1, (255, 255, 255, 125))
    draw_fluid_row(c, x + 12, y + 62, "蒸汽", "20.0k mB", (203, 210, 216, 255), 0.78, True)
    draw_fluid_row(c, x + 12, y + 88, "水", "16.0k mB", (75, 161, 225, 255), 0.62)
    draw_fluid_row(c, x + 12, y + 114, "熔岩", "10.2k mB", (236, 128, 55, 255), 0.48)
    c.round(x + 12, y + 145, 145, 24, 5, (235, 244, 249, 170), PAL.line_soft)
    c.text_center(x + 84, y + 153, "空槽位会在接收新流体后显示", PAL.faint, 7)


def cube_face(c: Canvas, pts: Sequence[tuple[float, float]], fill: tuple[int, int, int, int], outline: tuple[int, int, int, int]) -> None:
    c.draw.polygon([c.xy(x, y) for x, y in pts], fill=fill)
    c.line([*pts, pts[0]], outline, 0.8)
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
    c.round(cx - 7, cy - 7, 14, 14, 3, (255, 255, 255, 72), None)


def draw_face_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "面配置", 2)
    x, y, w = ox + 8, oy + 30, 170
    c.gradient_round(x, y, w, 116, 6, (253, 255, 255, 255), (235, 248, 254, 255), PAL.line)
    c.text(x + 10, y + 9, "面配置", PAL.ink, 8)
    c.text_right(x + w - 10, y + 9, "正面", PAL.blue, 7)
    cx, cy = x + 85, y + 66
    top = [(cx - 28, cy - 33), (cx + 16, cy - 42), (cx + 43, cy - 21), (cx - 3, cy - 11)]
    left = [(cx - 28, cy - 33), (cx - 3, cy - 11), (cx - 3, cy + 37), (cx - 31, cy + 13)]
    right = [(cx - 3, cy - 11), (cx + 43, cy - 21), (cx + 40, cy + 26), (cx - 3, cy + 37)]
    cube_face(c, left, (218, 233, 242, 255), PAL.line)
    cube_face(c, right, (230, 242, 248, 255), PAL.blue)
    cube_face(c, top, (246, 252, 254, 255), PAL.line)
    draw_icon(c, cx + 10, cy - 8, "fluid", PAL.cyan)
    draw_icon(c, cx - 21, cy - 1, "storage", PAL.green)
    c.text_center(cx + 19, cy + 16, "正", PAL.blue, 8)
    c.text_center(cx - 16, cy + 23, "左", PAL.muted, 7)
    c.text_center(cx + 5, cy - 27, "上", PAL.muted, 7)

    c.gradient_round(x, y + 124, w, 78, 6, (253, 255, 255, 255), (236, 248, 254, 255), PAL.line)
    c.text(x + 10, y + 133, "正面", PAL.ink, 8)
    c.line([(x + 65, y + 145), (x + 65, y + 193)], PAL.line_soft, 1)
    c.text(x + 10, y + 150, "功能", PAL.muted, 7)
    draw_page_button(c, x + 10, y + 162, 50, 18, "流体", "hover")
    c.text(x + 76, y + 150, "流体模式", PAL.muted, 7)
    labels = [("禁", "normal"), ("入", "selected"), ("出", "hover"), ("双", "gold")]
    for i, (label, state) in enumerate(labels):
        draw_chip(c, x + 76 + i * 23, y + 162, 20, 18, label, state, 7)
    c.text(x + 76, y + 185, "应力输出：同向 64rpm", PAL.faint, 6)


def draw_switch(c: Canvas, x: float, y: float, on: bool) -> None:
    outline = PAL.cyan if on else PAL.line
    fill = (219, 246, 243, 255) if on else (221, 233, 241, 255)
    c.round(x, y, 34, 14, 7, fill, outline)
    c.gradient_round(x + (19 if on else 3), y + 3, 9, 8, 4, PAL.white, (220, 235, 242, 255), PAL.line)


def draw_settings_card(c: Canvas, x: float, y: float, w: float, h: float, title: str, sub: str, icon: str | None = None) -> None:
    c.gradient_round(x, y, w, h, 5, (253, 255, 255, 255), (236, 248, 254, 255), PAL.line)
    if icon:
        draw_icon(c, x + 8, y + 8, icon, PAL.blue)
        tx = x + 27
    else:
        tx = x + 10
    c.text(tx, y + 7, title, PAL.ink, 8)
    c.text(tx, y + 20, sub, PAL.muted, 7)


def draw_settings_page(c: Canvas, ox: int, oy: int) -> None:
    draw_panel_shell(c, ox, oy, "家园箱子", "设置", 3)
    x, w = ox + 8, 170
    y = oy + 28
    draw_settings_card(c, x, y, w, 36, "可视化节点", "编辑连线与过滤", "graph")
    draw_page_button(c, x + w - 58, y + 9, 48, 18, "打开", "selected")

    y += 42
    c.gradient_round(x, y, w, 48, 5, (253, 255, 255, 255), (236, 248, 254, 255), PAL.line)
    c.text(x + 10, y + 7, "箱子标识", PAL.ink, 8)
    c.gradient_round(x + 10, y + 25, 105, 18, 5, PAL.white, (235, 245, 250, 255), PAL.line)
    c.text(x + 16, y + 30, "core-input-01", PAL.muted, 7)
    draw_page_button(c, x + 122, y + 25, 42, 18, "保存", "hover")

    y += 54
    c.gradient_round(x, y, w, 38, 5, (253, 255, 255, 255), (236, 248, 254, 255), PAL.line)
    c.text(x + 10, y + 7, "箱子权限", PAL.ink, 8)
    for i, (label, state) in enumerate((("私有", "selected"), ("团队", "normal"), ("公开", "normal"), ("空间", "disabled"))):
        draw_chip(c, x + 10 + i * 38, y + 22, 34, 16, label, state, 6)

    y += 44
    draw_settings_card(c, x, y, w, 50, "离线模拟", "箱子卸载后由快照顶替运行", "warning")
    draw_switch(c, x + w - 46, y + 19, True)

    y += 56
    c.gradient_round(x, y, w, 58, 5, (253, 255, 255, 255), (236, 248, 254, 255), PAL.line)
    c.text(x + 10, y + 7, "产率统计", PAL.ink, 8)
    c.text(x + 10, y + 22, "加入统计", PAL.muted, 7)
    draw_switch(c, x + w - 46, y + 18, False)
    c.text(x + 10, y + 43, "分组", PAL.faint, 7)
    c.gradient_round(x + 52, y + 37, 86, 18, 5, PAL.white, (235, 245, 250, 255), PAL.line)
    c.text(x + 59, y + 42, "默认", PAL.muted, 7)
    c.line([(x + 129, y + 44), (x + 133, y + 48), (x + 137, y + 44)], PAL.muted, 1)


def draw_components_preview() -> Image.Image:
    c = Canvas(760, 370, SCALE, (242, 248, 252, 255))
    c.text(20, 18, f"Pocket Homestead Chest UI Components {VERSION}", PAL.ink, 10)
    c.text(20, 35, "组件预览：这些元素后续可拆入 textures/gui/chest.png 图集", PAL.muted, 7)

    x, y = 26, 62
    c.text(x, y - 18, "Slots", PAL.ink, 8)
    for i, state in enumerate(("empty", "hover", "selected", "locked")):
        draw_slot(c, x + i * 28, y, state)
        c.text_center(x + i * 28 + 8, y + 23, state, PAL.muted, 5)
    draw_item_placeholder(c, x + 124, y, PAL.gold, "cube", "64")

    x, y = 230, 62
    c.text(x, y - 18, "Buttons", PAL.ink, 8)
    for i, state in enumerate(("normal", "hover", "selected", "disabled")):
        draw_page_button(c, x, y + i * 23, 70, 17, state, state)

    x, y = 340, 62
    c.text(x, y - 18, "Chips", PAL.ink, 8)
    states = ("normal", "hover", "selected", "gold", "danger", "disabled")
    for i, state in enumerate(states):
        draw_chip(c, x + (i % 3) * 50, y + (i // 3) * 26, 42, 17, state[:3], state, 6)

    x, y = 530, 62
    c.text(x, y - 18, "Upgrade Icons", PAL.ink, 8)
    icons = [("storage", PAL.blue), ("fluid", PAL.cyan), ("network", PAL.green), ("energy", PAL.gold), ("stress", (188, 118, 198, 255))]
    for i, (name, color) in enumerate(icons):
        draw_slot(c, x + i * 30, y, "empty")
        draw_upgrade_icon(c, x + i * 30, y, name, color)
        c.text_center(x + i * 30 + 8, y + 24, name[:3], PAL.muted, 5)

    x, y = 26, 185
    c.text(x, y - 17, "Panels / Cards", PAL.ink, 8)
    c.gradient_round(x, y, 170, 58, 6, PAL.white, (232, 243, 249, 255), PAL.line)
    c.text(x + 10, y + 9, "陶瓷面板", PAL.ink, 8)
    c.text(x + 10, y + 24, "双层描边 + 微高光 + 可拉伸九宫格", PAL.muted, 7)
    draw_micro_grid(c, x + 8, y + 36, 154, 14)

    x, y = 230, 185
    c.text(x, y - 17, "Scrollbars", PAL.ink, 8)
    draw_scrollbar(c, x, y, 72, 12, 28)
    draw_scrollbar(c, x + 18, y, 72, 30, 20)

    x, y = 300, 185
    c.text(x, y - 17, "Inputs / Switches", PAL.ink, 8)
    c.gradient_round(x, y, 130, 20, 5, PAL.white, (235, 245, 250, 255), PAL.line)
    c.text(x + 8, y + 5, "core-input-01", PAL.muted, 7)
    draw_switch(c, x, y + 34, True)
    draw_switch(c, x + 44, y + 34, False)

    x, y = 500, 185
    c.text(x, y - 17, "Face Cube Style", PAL.ink, 8)
    cube_face(c, [(x, y + 16), (x + 42, y + 6), (x + 68, y + 25), (x + 24, y + 37)], (246, 252, 254, 255), PAL.line)
    cube_face(c, [(x + 24, y + 37), (x + 68, y + 25), (x + 66, y + 69), (x + 24, y + 82)], (230, 242, 248, 255), PAL.blue)
    cube_face(c, [(x, y + 16), (x + 24, y + 37), (x + 24, y + 82), (x - 4, y + 56)], (218, 233, 242, 255), PAL.line)
    draw_icon(c, x + 41, y + 43, "fluid", PAL.cyan)

    return c.image


def draw_chest_preview() -> Image.Image:
    margin = 26
    gap = 26
    label_h = 25
    width = margin * 2 + PANEL_W * 4 + gap * 3
    height = margin * 2 + label_h + PANEL_H
    c = Canvas(width, height, SCALE, (243, 249, 253, 255))
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
