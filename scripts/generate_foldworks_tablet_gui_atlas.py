from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "foldworks" / "textures" / "gui" / "foldworks_tablet.png"
MCMETA = OUT.with_suffix(OUT.suffix + ".mcmeta")

LOGICAL_ATLAS = 512
SCALE = 8
ATLAS = LOGICAL_ATLAS * SCALE

WHITE = (255, 255, 255, 255)
SURFACE = (248, 251, 253, 255)
SURFACE_SOFT = (231, 240, 246, 255)
SURFACE_SUNK = (215, 228, 236, 255)
BLUE = (104, 191, 234, 255)
BLUE_DEEP = (47, 140, 190, 255)
BLUE_SOFT = (221, 244, 255, 255)
CYAN = (66, 181, 204, 255)
GREEN = (47, 125, 93, 255)
GOLD = (169, 104, 24, 255)
PINK = (180, 61, 80, 255)
PURPLE = (114, 87, 154, 255)
BORDER = (165, 199, 217, 255)
BORDER_STRONG = (111, 166, 196, 255)
INK = (23, 43, 58, 255)
MUTED = (82, 107, 122, 255)
FAINT = (130, 149, 161, 255)
WARNING = (169, 104, 24, 255)


def mix(a, b, t: float):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))


class D:
    def __init__(self, image: Image.Image):
        self.image = image
        self.draw = ImageDraw.Draw(image)

    def p(self, v):
        if isinstance(v, (tuple, list)):
            return tuple(round(x * SCALE) for x in v)
        return round(v * SCALE)

    def rr(self, xy, r=0, fill=None, outline=None, width=1):
        self.draw.rounded_rectangle(self.p(xy), radius=self.p(r), fill=fill, outline=outline, width=max(1, self.p(width)))

    def rect(self, xy, fill=None, outline=None, width=1):
        self.draw.rectangle(self.p(xy), fill=fill, outline=outline, width=max(1, self.p(width)))

    def line(self, xy, fill=None, width=1, joint=None):
        self.draw.line(self.p(xy), fill=fill, width=max(1, self.p(width)), joint=joint)

    def ellipse(self, xy, fill=None, outline=None, width=1):
        self.draw.ellipse(self.p(xy), fill=fill, outline=outline, width=max(1, self.p(width)))

    def arc(self, xy, start, end, fill=None, width=1):
        self.draw.arc(self.p(xy), start, end, fill=fill, width=max(1, self.p(width)))

    def polygon(self, pts, fill=None):
        self.draw.polygon([self.p(p) for p in pts], fill=fill)


def draw_panel(d: D, x: int, y: int, fill, outline=BORDER, header=False):
    d.rr((x, y, x + 31, y + 31), 5, fill, outline, 1)
    d.line((x + 7, y + 1, x + 24, y + 1), mix(WHITE, fill, 0.12), 1)
    d.line((x + 3, y + 30, x + 28, y + 30), mix(outline, INK, 0.20), 1)
    if header:
        d.rect((x + 1, y + 1, x + 30, y + 11), mix(fill, BLUE_SOFT, 0.55))
        d.line((x + 4, y + 12, x + 27, y + 12), mix(outline, BLUE, 0.35), 1)


def draw_button(d: D, x: int, y: int, fill, outline=BORDER, danger=False):
    d.rr((x, y, x + 31, y + 23), 3, fill, outline, 1)
    d.line((x + 6, y + 2, x + 25, y + 2), mix(WHITE, fill, 0.1), 1)
    d.line((x + 4, y + 22, x + 27, y + 22), mix(outline, PINK if danger else BLUE, 0.18), 1)


def draw_chip(d: D, x: int, y: int, fill, outline=BORDER):
    d.rr((x, y, x + 31, y + 15), 2, fill, outline, 1)
    d.line((x + 6, y + 1, x + 25, y + 1), mix(WHITE, fill, 0.18), 1)


def draw_icon(d: D, kind: str, x: int, y: int, color=BLUE_DEEP):
    if kind == "create":
        d.rect((x + 5, y + 5, x + 22, y + 24), outline=color, width=2)
        d.line((x + 18, y + 5, x + 22, y + 9), color, 2)
        d.line((x + 14, y + 12, x + 14, y + 20), color, 2)
        d.line((x + 10, y + 16, x + 18, y + 16), color, 2)
    elif kind == "manage":
        for yy, knob_x in ((7, 11), (14, 19), (21, 8)):
            d.line((x + 5, y + yy, x + 23, y + yy), color, 2)
            d.rect((x + knob_x - 2, y + yy - 2, x + knob_x + 2, y + yy + 2), fill=SURFACE, outline=color, width=2)
    elif kind == "tablet_chest":
        d.rect((x + 5, y + 8, x + 23, y + 23), outline=color, width=2)
        d.line((x + 5, y + 13, x + 23, y + 13), color, 2)
        d.rect((x + 12, y + 11, x + 16, y + 16), fill=SURFACE, outline=color, width=2)
    elif kind == "permissions":
        d.polygon(((x + 14, y + 4), (x + 23, y + 8), (x + 21, y + 19), (x + 14, y + 25), (x + 7, y + 19), (x + 5, y + 8)), fill=SURFACE)
        d.line((x + 14, y + 4, x + 23, y + 8, x + 21, y + 19, x + 14, y + 25, x + 7, y + 19, x + 5, y + 8, x + 14, y + 4), color, 2, "curve")
        d.line((x + 10, y + 14, x + 13, y + 17, x + 19, y + 11), color, 2, "curve")
    elif kind == "production":
        d.line((x + 5, y + 23, x + 5, y + 6), color, 2)
        d.line((x + 5, y + 23, x + 24, y + 23), color, 2)
        d.line((x + 8, y + 19, x + 13, y + 14, x + 17, y + 16, x + 23, y + 8), color, 2, "curve")
        d.polygon(((x + 20, y + 8), (x + 24, y + 7), (x + 23, y + 11)), color)
    elif kind == "migration":
        d.line((x + 5, y + 9, x + 20, y + 9, x + 16, y + 5), color, 3, "curve")
        d.line((x + 20, y + 9, x + 16, y + 13), color, 3, "curve")
        d.line((x + 23, y + 19, x + 8, y + 19, x + 12, y + 15), color, 3, "curve")
        d.line((x + 8, y + 19, x + 12, y + 23), color, 3, "curve")
    elif kind == "close":
        d.line((x + 7, y + 7, x + 21, y + 21), color, 3)
        d.line((x + 21, y + 7, x + 7, y + 21), color, 3)
    elif kind == "settings":
        for yy, knob_x in ((7, 18), (14, 10), (21, 16)):
            d.line((x + 5, y + yy, x + 23, y + yy), color, 2)
            d.rect((x + knob_x - 2, y + yy - 2, x + knob_x + 2, y + yy + 2), fill=SURFACE, outline=color, width=2)
    elif kind == "enter":
        d.line((x + 5, y + 14, x + 20, y + 14), color, 3)
        d.line((x + 15, y + 8, x + 21, y + 14, x + 15, y + 20), color, 3, "curve")
        d.line((x + 21, y + 6, x + 25, y + 6, x + 25, y + 22, x + 21, y + 22), color, 2)
    elif kind == "offline":
        d.rect((x + 5, y + 6, x + 23, y + 22), outline=color, width=2)
        d.line((x + 8, y + 20, x + 21, y + 7), color, 2)
    elif kind == "delete":
        d.rect((x + 8, y + 8, x + 20, y + 24), outline=color, width=2)
        d.line((x + 5, y + 7, x + 23, y + 7), color, 2)
        d.line((x + 11, y + 4, x + 17, y + 4), color, 2)
        d.line((x + 12, y + 12, x + 12, y + 20), color, 2)
        d.line((x + 16, y + 12, x + 16, y + 20), color, 2)
    elif kind == "refresh":
        d.arc((x + 5, y + 5, x + 23, y + 23), 30, 320, color, 3)
        d.polygon(((x + 22, y + 5), (x + 25, y + 13), (x + 17, y + 10)), color)
    elif kind == "download":
        d.line((x + 14, y + 4, x + 14, y + 18), color, 3)
        d.line((x + 8, y + 13, x + 14, y + 19, x + 20, y + 13), color, 3, "curve")
        d.line((x + 5, y + 24, x + 23, y + 24), color, 2)
    elif kind == "upload":
        d.line((x + 14, y + 10, x + 14, y + 24), color, 3)
        d.line((x + 8, y + 10, x + 14, y + 4, x + 20, y + 10), color, 3, "curve")
        d.line((x + 5, y + 24, x + 23, y + 24), color, 2)
    elif kind == "search":
        d.ellipse((x + 5, y + 5, x + 17, y + 17), outline=color, width=3)
        d.line((x + 16, y + 16, x + 24, y + 24), color, 3)
    elif kind == "team":
        d.ellipse((x + 10, y + 4, x + 18, y + 12), outline=color, width=2)
        d.ellipse((x + 3, y + 9, x + 9, y + 15), outline=color, width=2)
        d.ellipse((x + 19, y + 9, x + 25, y + 15), outline=color, width=2)
        d.arc((x + 6, y + 13, x + 22, y + 27), 180, 360, color, 2)
        d.arc((x + 1, y + 15, x + 11, y + 25), 180, 330, color, 2)
        d.arc((x + 17, y + 15, x + 27, y + 25), 210, 360, color, 2)
    elif kind == "archive":
        d.rect((x + 5, y + 9, x + 23, y + 24), outline=color, width=2)
        d.rect((x + 4, y + 5, x + 24, y + 10), fill=SURFACE, outline=color, width=2)
        d.line((x + 10, y + 16, x + 18, y + 16), color, 2)


def draw_atlas() -> Image.Image:
    img = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
    d = D(img)

    # 32x32 nine-slice source tiles.
    draw_panel(d, 0, 0, SURFACE, BORDER)
    draw_panel(d, 32, 0, SURFACE_SOFT, BORDER)
    draw_panel(d, 64, 0, SURFACE, BORDER_STRONG, header=True)
    draw_panel(d, 96, 0, BLUE_SOFT, BLUE)
    draw_panel(d, 128, 0, (255, 246, 238, 255), WARNING)
    draw_panel(d, 160, 0, SURFACE_SUNK, BORDER)

    draw_button(d, 0, 40, WHITE, BORDER)
    draw_button(d, 32, 40, BLUE_SOFT, BLUE)
    draw_button(d, 64, 40, (255, 235, 240, 255), PINK, True)
    draw_button(d, 96, 40, SURFACE_SUNK, BORDER)

    draw_chip(d, 0, 72, SURFACE_SUNK, BORDER)
    draw_chip(d, 32, 72, BLUE_SOFT, BLUE)
    draw_chip(d, 64, 72, (231, 248, 239, 255), GREEN)
    draw_chip(d, 96, 72, (255, 246, 226, 255), GOLD)
    draw_chip(d, 128, 72, (246, 235, 255, 255), PURPLE)

    # Scrollbar pieces.
    d.rr((176, 72, 179, 103), 1, (220, 233, 244, 255), (220, 233, 244, 255))
    d.rr((184, 72, 187, 103), 1, BORDER_STRONG, BORDER_STRONG)

    icons = [
        ("create", BLUE_DEEP), ("manage", BLUE_DEEP), ("permissions", BLUE_DEEP),
        ("production", BLUE_DEEP), ("migration", BLUE_DEEP), ("close", PINK),
        ("settings", MUTED), ("enter", BLUE_DEEP), ("offline", MUTED), ("delete", PINK),
        ("refresh", BLUE_DEEP), ("download", BLUE_DEEP), ("upload", BLUE_DEEP),
        ("search", MUTED), ("team", CYAN), ("archive", GOLD),
        ("tablet_chest", BLUE_DEEP),
    ]
    for i, (kind, color) in enumerate(icons):
        draw_icon(d, kind, (i % 8) * 32, 112 + (i // 8) * 32, color)

    # Small status dots.
    for i, color in enumerate((GREEN, BLUE, GOLD, PINK, PURPLE, CYAN)):
        d.ellipse((272 + i * 12, 112, 280 + i * 12, 120), fill=color)
        d.ellipse((274 + i * 12, 114, 278 + i * 12, 118), fill=mix(WHITE, color, 0.18))

    return img


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    draw_atlas().save(OUT)
    MCMETA.write_text('{\n  "texture": {\n    "blur": true,\n    "clamp": false\n  }\n}\n', encoding="utf-8")
    print(OUT)


if __name__ == "__main__":
    main()
