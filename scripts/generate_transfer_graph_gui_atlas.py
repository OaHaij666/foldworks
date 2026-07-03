from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "pockethomestead" / "textures" / "gui" / "transfer_graph.png"

LOGICAL_ATLAS = 512
ATLAS_SCALE = 8
ATLAS = LOGICAL_ATLAS * ATLAS_SCALE

WHITE = (255, 255, 255, 255)
SURFACE = (255, 255, 255, 246)
SURFACE_SOFT = (250, 253, 255, 250)
FIELD = (245, 249, 252, 255)
GRID = (226, 238, 247, 255)
GRID_MAJOR = (209, 228, 241, 255)
BLUE = (91, 166, 226, 255)
BLUE_SOFT = (226, 244, 255, 255)
CYAN = (75, 187, 201, 255)
CYAN_SOFT = (226, 248, 251, 255)
PINK = (255, 132, 162, 255)
PINK_SOFT = (255, 238, 244, 255)
GREEN = (76, 187, 128, 255)
GOLD = (225, 178, 66, 255)
PURPLE = (184, 112, 204, 255)
BORDER = (207, 221, 232, 255)
BORDER_STRONG = (126, 188, 239, 255)
INK = (76, 91, 108, 255)
MUTED = (136, 151, 166, 255)
DISABLED = (231, 236, 242, 255)


class ScaledDraw:
    def __init__(self, draw: ImageDraw.ImageDraw, scale: int) -> None:
        self.draw = draw
        self.scale = scale

    def _p(self, p):
        if isinstance(p, (tuple, list)):
            return tuple(round(v * self.scale) for v in p)
        return round(p * self.scale)

    def rectangle(self, xy, **kwargs) -> None:
        self.draw.rectangle(self._p(xy), **kwargs)

    def rounded_rectangle(self, xy, radius=0, width=1, **kwargs) -> None:
        self.draw.rounded_rectangle(self._p(xy), radius=round(radius * self.scale), width=max(1, round(width * self.scale)), **kwargs)

    def line(self, xy, width=1, **kwargs) -> None:
        self.draw.line(self._p(xy), width=max(1, round(width * self.scale)), **kwargs)

    def ellipse(self, xy, width=1, **kwargs) -> None:
        self.draw.ellipse(self._p(xy), width=max(1, round(width * self.scale)), **kwargs)

    def arc(self, xy, start, end, width=1, **kwargs) -> None:
        self.draw.arc(self._p(xy), start, end, width=max(1, round(width * self.scale)), **kwargs)

    def polygon(self, xy, **kwargs) -> None:
        self.draw.polygon([self._p(p) for p in xy], **kwargs)


def mix(a: tuple[int, int, int, int], b: tuple[int, int, int, int], t: float) -> tuple[int, int, int, int]:
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))  # type: ignore[return-value]


def rounded(
    d: ImageDraw.ImageDraw,
    x: int,
    y: int,
    w: int,
    h: int,
    r: int,
    fill: tuple[int, int, int, int],
    outline: tuple[int, int, int, int] = BORDER,
    width: int = 1,
) -> None:
    d.rounded_rectangle((x, y, x + w - 1, y + h - 1), radius=r, fill=fill, outline=outline, width=width)


def sprite_panel(d: ImageDraw.ImageDraw, x: int, y: int, fill: tuple[int, int, int, int], outline=BORDER) -> None:
    rounded(d, x, y, 24, 24, 8, fill, outline)
    d.line((x + 7, y + 1, x + 17, y + 1), fill=mix(WHITE, fill, 0.18))
    d.line((x + 2, y + 22, x + 21, y + 22), fill=mix(outline, (76, 98, 120, 255), 0.25))


def sprite_button(d: ImageDraw.ImageDraw, x: int, y: int, fill: tuple[int, int, int, int], outline: tuple[int, int, int, int]) -> None:
    rounded(d, x, y, 26, 24, 8, fill, outline)
    d.line((x + 7, y + 2, x + 18, y + 2), fill=mix(WHITE, fill, 0.08))


def sprite_header(d: ImageDraw.ImageDraw, x: int, y: int, fill: tuple[int, int, int, int], line: tuple[int, int, int, int]) -> None:
    rounded(d, x, y, 24, 16, 8, fill, fill)
    d.line((x + 5, y + 15, x + 19, y + 15), fill=line)


def draw_grid(d: ImageDraw.ImageDraw, x: int, y: int) -> None:
    d.rectangle((x, y, x + 71, y + 71), fill=(253, 254, 255, 255))
    d.line((x, y, x + 71, y), fill=GRID_MAJOR)
    d.line((x, y, x, y + 71), fill=GRID_MAJOR)
    d.line((x + 36, y, x + 36, y + 71), fill=GRID)
    d.line((x, y + 36, x + 71, y + 36), fill=GRID)


def icon_save64(d: ImageDraw.ImageDraw, x: int, y: int, color=BLUE) -> None:
    rounded(d, x + 8, y + 6, 48, 52, 12, color, color)
    d.rectangle((x + 17, y + 13, x + 47, y + 24), fill=mix(WHITE, color, 0.08))
    d.rectangle((x + 22, y + 17, x + 42, y + 20), fill=color)
    d.rectangle((x + 44, y + 13, x + 47, y + 24), fill=mix(color, (32, 96, 150, 255), 0.24))
    d.rounded_rectangle((x + 18, y + 38, x + 47, y + 55), radius=4, fill=mix(WHITE, color, 0.18))
    d.rectangle((x + 22, y + 42, x + 43, y + 45), fill=mix(color, WHITE, 0.68))
    d.rectangle((x + 22, y + 50, x + 43, y + 53), fill=mix(color, (32, 96, 150, 255), 0.14))


def icon_page64(d: ImageDraw.ImageDraw, x: int, y: int, color=BLUE) -> None:
    rounded(d, x + 14, y + 5, 38, 54, 10, color, color)
    d.rectangle((x + 23, y + 16, x + 44, y + 20), fill=mix(WHITE, color, 0.08))
    d.rectangle((x + 23, y + 29, x + 44, y + 33), fill=mix(WHITE, color, 0.08))
    d.rectangle((x + 23, y + 42, x + 44, y + 46), fill=mix(WHITE, color, 0.08))


def icon_plus64(d: ImageDraw.ImageDraw, x: int, y: int, color=BLUE) -> None:
    d.ellipse((x + 8, y + 8, x + 56, y + 56), fill=mix(WHITE, BLUE_SOFT, 0.24))
    d.ellipse((x + 8, y + 8, x + 56, y + 56), outline=mix(color, WHITE, 0.08), width=5)
    d.rounded_rectangle((x + 29, y + 18, x + 35, y + 46), radius=3, fill=color)
    d.rounded_rectangle((x + 18, y + 29, x + 46, y + 35), radius=3, fill=color)


def icon_help64(d: ImageDraw.ImageDraw, x: int, y: int, color=BLUE) -> None:
    d.ellipse((x + 7, y + 7, x + 57, y + 57), fill=mix(WHITE, BLUE_SOFT, 0.26))
    d.ellipse((x + 7, y + 7, x + 57, y + 57), outline=color, width=5)
    d.arc((x + 21, y + 16, x + 43, y + 38), 205, 34, fill=color, width=6)
    d.line((x + 37, y + 31, x + 32, y + 39), fill=color, width=6)
    d.line((x + 32, y + 39, x + 32, y + 42), fill=color, width=5)
    d.ellipse((x + 28, y + 45, x + 36, y + 53), fill=color)
    d.ellipse((x + 17, y + 15, x + 24, y + 22), fill=(255, 255, 255, 135))


def icon_close64(d: ImageDraw.ImageDraw, x: int, y: int, color=BLUE) -> None:
    d.line((x + 18, y + 18, x + 46, y + 46), fill=color, width=8)
    d.line((x + 46, y + 18, x + 18, y + 46), fill=color, width=8)


def icon_chevron64(d: ImageDraw.ImageDraw, x: int, y: int, color=BLUE, right: bool = False) -> None:
    if right:
        d.polygon((
            (x + 22, y + 17), (x + 28, y + 17), (x + 43, y + 32),
            (x + 28, y + 47), (x + 22, y + 47), (x + 37, y + 32),
        ), fill=color)
    else:
        d.polygon((
            (x + 17, y + 23), (x + 23, y + 23), (x + 32, y + 34),
            (x + 41, y + 23), (x + 47, y + 23), (x + 32, y + 41),
        ), fill=color)


def icon_resource(d: ImageDraw.ImageDraw, kind: str, x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if kind == "item":
        for ox, oy in ((1, 1), (10, 1), (1, 10), (10, 10)):
            d.rounded_rectangle((x + ox, y + oy, x + ox + 6, y + oy + 6), radius=2, fill=color, outline=color)
            d.rectangle((x + ox + 1, y + oy + 1, x + ox + 5, y + oy + 1), fill=mix(WHITE, color, 0.3))
    elif kind == "fluid":
        # Circle radius 7 centered at (9, 12); upper triangle edges meet the circle at tangent points.
        d.ellipse((x + 2, y + 5, x + 16, y + 19), fill=color)
        d.polygon(((x + 9, y), (x + 3, y + 9), (x + 15, y + 9)), fill=color)
    elif kind == "energy":
        d.polygon(((x + 11, y), (x + 3, y + 9), (x + 8, y + 9), (x + 4, y + 19), (x + 16, y + 6), (x + 10, y + 6)), fill=color)
        d.line((x + 10, y + 2, x + 6, y + 7), fill=mix(WHITE, color, 0.28), width=1)
    elif kind == "stress":
        d.ellipse((x, y, x + 18, y + 18), fill=color, outline=color)
        for ax, ay in ((9, 2), (15, 9), (9, 15), (2, 9)):
            d.ellipse((x + ax - 2, y + ay - 2, x + ax + 2, y + ay + 2), fill=mix(WHITE, color, 0.08))
        d.ellipse((x + 5, y + 5, x + 13, y + 13), fill=WHITE)
        d.ellipse((x + 8, y + 8, x + 10, y + 10), fill=mix(color, WHITE, 0.2))


def icon_node(d: ImageDraw.ImageDraw, kind: str, x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if kind == "chest":
        rounded(d, x + 1, y + 5, 15, 11, 3, color, color)
        d.rectangle((x + 3, y + 8, x + 13, y + 9), fill=mix(WHITE, color, 0.22))
        d.rectangle((x + 7, y + 10, x + 10, y + 13), fill=WHITE)
    elif kind == "reroute":
        d.ellipse((x, y + 6, x + 6, y + 12), fill=color)
        d.ellipse((x + 12, y, x + 18, y + 6), fill=color)
        d.ellipse((x + 12, y + 12, x + 18, y + 18), fill=color)
        d.line((x + 6, y + 9, x + 12, y + 3), fill=color, width=2)
        d.line((x + 6, y + 9, x + 12, y + 15), fill=color, width=2)
    elif kind == "backpack":
        rounded(d, x + 2, y + 5, 15, 12, 5, mix(color, WHITE, 0.18), color)
        d.arc((x + 5, y, x + 14, y + 9), 180, 360, fill=color, width=2)
        rounded(d, x + 5, y + 10, 9, 5, 2, WHITE, WHITE)
    elif kind == "trash":
        rounded(d, x + 3, y + 5, 13, 12, 3, mix(color, WHITE, 0.16), color)
        d.rectangle((x + 2, y + 3, x + 17, y + 5), fill=color)
        d.line((x + 6, y + 8, x + 13, y + 15), fill=WHITE, width=2)
        d.line((x + 13, y + 8, x + 6, y + 15), fill=WHITE, width=2)


def draw_atlas() -> Image.Image:
    image = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
    d = ScaledDraw(ImageDraw.Draw(image), ATLAS_SCALE)

    sprite_panel(d, 0, 0, SURFACE)
    sprite_panel(d, 24, 0, SURFACE_SOFT)
    sprite_panel(d, 48, 0, SURFACE, BORDER_STRONG)
    sprite_panel(d, 72, 0, SURFACE, PINK)
    sprite_panel(d, 96, 0, DISABLED, BORDER)
    sprite_panel(d, 120, 0, FIELD)

    sprite_header(d, 0, 32, BLUE_SOFT, BLUE)
    sprite_header(d, 24, 32, CYAN_SOFT, CYAN)
    sprite_header(d, 48, 32, PINK_SOFT, PINK)
    sprite_header(d, 72, 32, DISABLED, MUTED)

    sprite_button(d, 0, 56, SURFACE, BORDER)
    sprite_button(d, 26, 56, BLUE_SOFT, BLUE)
    sprite_button(d, 52, 56, PINK_SOFT, PINK)
    sprite_button(d, 78, 56, DISABLED, BORDER)
    sprite_button(d, 104, 56, mix(BLUE_SOFT, WHITE, 0.34), BLUE)

    draw_grid(d, 152, 0)
    d.rectangle((0, 88, 0, 88), fill=WHITE)

    icon_save64(d, 0, 256)
    icon_page64(d, 64, 256)
    icon_plus64(d, 128, 256)
    icon_help64(d, 192, 256)
    icon_close64(d, 256, 256)
    icon_chevron64(d, 320, 256)
    icon_chevron64(d, 384, 256, right=True)
    icon_node(d, "chest", 0, 120, BLUE)
    icon_node(d, "reroute", 22, 120, CYAN)
    icon_node(d, "backpack", 46, 120, BLUE)
    icon_node(d, "trash", 70, 120, PINK)
    icon_resource(d, "item", 0, 144, GREEN)
    icon_resource(d, "fluid", 22, 144, CYAN)
    icon_resource(d, "energy", 44, 144, GOLD)
    icon_resource(d, "stress", 66, 144, PURPLE)

    for i, color in enumerate((GREEN, CYAN, GOLD, PURPLE, BLUE)):
        x = 104 + i * 14
        d.ellipse((x + 2, 122, x + 12, 132), fill=mix(color, WHITE, 0.25), outline=color)
        d.ellipse((x + 5, 125, x + 9, 129), fill=WHITE)

    return image


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image = draw_atlas()
    image.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
