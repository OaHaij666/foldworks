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
SURFACE = (250, 253, 255, 255)
SURFACE_SOFT = (242, 249, 255, 255)
SURFACE_SUNK = (230, 240, 249, 255)
BLUE = (112, 177, 231, 255)
BLUE_DEEP = (76, 139, 204, 255)
BLUE_SOFT = (224, 243, 255, 255)
CYAN = (78, 184, 203, 255)
GREEN = (84, 190, 133, 255)
GOLD = (224, 174, 66, 255)
PINK = (241, 126, 144, 255)
PURPLE = (168, 120, 205, 255)
BORDER = (202, 219, 232, 255)
BORDER_STRONG = (146, 195, 235, 255)
INK = (50, 65, 82, 255)
MUTED = (128, 145, 162, 255)
FAINT = (176, 188, 200, 255)
WARNING = (239, 185, 74, 255)


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
    d.rr((x, y, x + 31, y + 31), 8, fill, outline, 1)
    d.line((x + 7, y + 1, x + 24, y + 1), mix(WHITE, fill, 0.12), 1)
    d.line((x + 3, y + 30, x + 28, y + 30), mix(outline, INK, 0.20), 1)
    if header:
        d.rect((x + 1, y + 1, x + 30, y + 11), mix(fill, BLUE_SOFT, 0.55))
        d.line((x + 4, y + 12, x + 27, y + 12), mix(outline, BLUE, 0.35), 1)


def draw_button(d: D, x: int, y: int, fill, outline=BORDER, danger=False):
    d.rr((x, y, x + 31, y + 23), 4, fill, outline, 1)
    d.line((x + 6, y + 2, x + 25, y + 2), mix(WHITE, fill, 0.1), 1)
    d.line((x + 4, y + 22, x + 27, y + 22), mix(outline, PINK if danger else BLUE, 0.18), 1)


def draw_chip(d: D, x: int, y: int, fill, outline=BORDER):
    d.rr((x, y, x + 31, y + 15), 4, fill, outline, 1)
    d.line((x + 6, y + 1, x + 25, y + 1), mix(WHITE, fill, 0.18), 1)


def draw_icon(d: D, kind: str, x: int, y: int, color=BLUE_DEEP):
    if kind == "create":
        d.rr((x + 11, y + 3, x + 16, y + 25), 2, color, color)
        d.rr((x + 3, y + 11, x + 25, y + 16), 2, color, color)
    elif kind == "manage":
        for ox, oy in ((4, 4), (16, 4), (4, 16), (16, 16)):
            d.rr((x + ox, y + oy, x + ox + 8, y + oy + 8), 3, color, color)
            d.rect((x + ox + 2, y + oy + 2, x + ox + 6, y + oy + 3), mix(WHITE, color, 0.18))
    elif kind == "tablet_chest":
        d.rr((x + 5, y + 8, x + 23, y + 24), 4, color, color)
        d.rect((x + 7, y + 6, x + 21, y + 11), fill=mix(color, WHITE, 0.18))
        d.line((x + 8, y + 14, x + 20, y + 14), WHITE, 2)
        d.rr((x + 9, y + 17, x + 13, y + 21), 2, mix(WHITE, color, 0.18), mix(WHITE, color, 0.18))
        d.rr((x + 15, y + 17, x + 19, y + 21), 2, mix(WHITE, color, 0.18), mix(WHITE, color, 0.18))
    elif kind == "permissions":
        d.rr((x + 5, y + 12, x + 23, y + 25), 4, color, color)
        d.arc((x + 8, y + 3, x + 20, y + 17), 180, 360, color, 4)
        d.ellipse((x + 12, y + 17, x + 16, y + 21), fill=WHITE)
    elif kind == "production":
        for i, h in enumerate((8, 14, 20)):
            bx = x + 5 + i * 7
            d.rr((bx, y + 25 - h, bx + 4, y + 25), 2, color, color)
        d.line((x + 4, y + 22, x + 12, y + 15, x + 19, y + 17, x + 25, y + 8), color, 2, "curve")
    elif kind == "migration":
        d.line((x + 5, y + 9, x + 20, y + 9, x + 16, y + 5), color, 3, "curve")
        d.line((x + 20, y + 9, x + 16, y + 13), color, 3, "curve")
        d.line((x + 23, y + 19, x + 8, y + 19, x + 12, y + 15), color, 3, "curve")
        d.line((x + 8, y + 19, x + 12, y + 23), color, 3, "curve")
    elif kind == "close":
        d.line((x + 7, y + 7, x + 21, y + 21), color, 3)
        d.line((x + 21, y + 7, x + 7, y + 21), color, 3)
    elif kind == "settings":
        d.ellipse((x + 5, y + 5, x + 23, y + 23), outline=color, width=3)
        for px, py in ((14, 1), (14, 27), (1, 14), (27, 14)):
            d.ellipse((x + px - 2, y + py - 2, x + px + 2, y + py + 2), fill=color)
        d.ellipse((x + 11, y + 11, x + 17, y + 17), fill=color)
    elif kind == "enter":
        d.line((x + 5, y + 14, x + 20, y + 14), color, 3)
        d.line((x + 15, y + 8, x + 21, y + 14, x + 15, y + 20), color, 3, "curve")
        d.line((x + 21, y + 6, x + 25, y + 6, x + 25, y + 22, x + 21, y + 22), color, 2)
    elif kind == "offline":
        d.ellipse((x + 5, y + 6, x + 23, y + 22), outline=color, width=3)
        d.line((x + 8, y + 20, x + 22, y + 6), color, 3)
    elif kind == "delete":
        d.rr((x + 7, y + 8, x + 21, y + 24), 3, color, color)
        d.rect((x + 5, y + 5, x + 23, y + 8), fill=color)
        d.line((x + 10, y + 12, x + 18, y + 20), WHITE, 2)
        d.line((x + 18, y + 12, x + 10, y + 20), WHITE, 2)
    elif kind == "refresh":
        d.arc((x + 5, y + 5, x + 23, y + 23), 30, 320, color, 3)
        d.polygon(((x + 22, y + 5), (x + 25, y + 13), (x + 17, y + 10)), color)
    elif kind == "download":
        d.line((x + 14, y + 4, x + 14, y + 18), color, 3)
        d.line((x + 8, y + 13, x + 14, y + 19, x + 20, y + 13), color, 3, "curve")
        d.rr((x + 5, y + 21, x + 23, y + 25), 2, color, color)
    elif kind == "upload":
        d.line((x + 14, y + 10, x + 14, y + 24), color, 3)
        d.line((x + 8, y + 10, x + 14, y + 4, x + 20, y + 10), color, 3, "curve")
        d.rr((x + 5, y + 24, x + 23, y + 27), 2, color, color)
    elif kind == "search":
        d.ellipse((x + 5, y + 5, x + 17, y + 17), outline=color, width=3)
        d.line((x + 16, y + 16, x + 24, y + 24), color, 3)
    elif kind == "team":
        d.ellipse((x + 10, y + 4, x + 18, y + 12), fill=color)
        d.ellipse((x + 3, y + 9, x + 10, y + 16), fill=mix(color, WHITE, 0.12))
        d.ellipse((x + 18, y + 9, x + 25, y + 16), fill=mix(color, WHITE, 0.12))
        d.rr((x + 7, y + 15, x + 21, y + 24), 5, color, color)
        d.rr((x + 1, y + 18, x + 9, y + 25), 4, mix(color, WHITE, 0.12), mix(color, WHITE, 0.12))
        d.rr((x + 19, y + 18, x + 27, y + 25), 4, mix(color, WHITE, 0.12), mix(color, WHITE, 0.12))
    elif kind == "archive":
        d.rr((x + 5, y + 8, x + 23, y + 24), 4, color, color)
        d.rect((x + 7, y + 5, x + 17, y + 10), fill=mix(color, WHITE, 0.24))
        d.line((x + 9, y + 15, x + 19, y + 15), WHITE, 2)


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
