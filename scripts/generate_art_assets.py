from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/pockethomestead"
BLOCK = ASSETS / "textures/block"
ITEM = ASSETS / "textures/item"
MODEL_BLOCK = ASSETS / "models/block"
PREVIEW = ROOT / "build/art_preview"


PALETTE = {
    "transparent": (0, 0, 0, 0),
    "black": (7, 8, 9, 255),
    "iron": (34, 37, 38, 255),
    "iron2": (53, 56, 55, 255),
    "iron3": (79, 82, 78, 255),
    "brass_shadow": (76, 49, 31, 255),
    "brass_dark": (118, 77, 45, 255),
    "brass": (198, 134, 75, 255),
    "brass2": (224, 168, 88, 255),
    "brass_hi": (236, 184, 100, 255),
    "copper": (150, 74, 42, 255),
    "cyan": (21, 214, 232, 255),
    "cyan_soft": (69, 238, 241, 255),
    "blue": (39, 117, 220, 255),
    "green": (64, 229, 130, 255),
    "amber": (255, 189, 70, 255),
    "red": (238, 76, 58, 255),
    "jade": (46, 226, 156, 255),
    "jade_dark": (10, 72, 56, 255),
    "glass": (79, 184, 210, 158),
}


def color(name: str) -> tuple[int, int, int, int]:
    return PALETTE[name]


def img(size: tuple[int, int], fill: str = "transparent") -> Image.Image:
    return Image.new("RGBA", size, color(fill))


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def rect(draw: ImageDraw.ImageDraw, xy, fill: str | tuple[int, int, int, int], outline=None) -> None:
    draw.rectangle(xy, fill=color(fill) if isinstance(fill, str) else fill, outline=color(outline) if isinstance(outline, str) else outline)


def line(draw: ImageDraw.ImageDraw, xy, fill: str | tuple[int, int, int, int], width: int = 1) -> None:
    draw.line(xy, fill=color(fill) if isinstance(fill, str) else fill, width=width)


def ellipse(draw: ImageDraw.ImageDraw, xy, fill=None, outline=None, width: int = 1) -> None:
    draw.ellipse(
        xy,
        fill=color(fill) if isinstance(fill, str) else fill,
        outline=color(outline) if isinstance(outline, str) else outline,
        width=width,
    )


def poly(draw: ImageDraw.ImageDraw, points, fill: str | tuple[int, int, int, int], outline=None) -> None:
    draw.polygon(points, fill=color(fill) if isinstance(fill, str) else fill, outline=color(outline) if isinstance(outline, str) else outline)


def blend(a, b, t: float):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))


def adjust(c, amount: int):
    return (
        max(0, min(255, c[0] + amount)),
        max(0, min(255, c[1] + amount)),
        max(0, min(255, c[2] + amount)),
        c[3],
    )


def add_noise(image: Image.Image, strength: int = 9, step: int = 3, alpha_only: bool = False) -> None:
    px = image.load()
    w, h = image.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            n = ((x * 17 + y * 31 + (x // step) * 11 + (y // step) * 5) % (strength * 2 + 1)) - strength
            if alpha_only:
                px[x, y] = (r, g, b, max(0, min(255, a + n)))
            else:
                px[x, y] = (
                    max(0, min(255, r + n)),
                    max(0, min(255, g + n)),
                    max(0, min(255, b + n)),
                    a,
                )


def draw_brass_corner(draw: ImageDraw.ImageDraw, x: int, y: int, sx: int, sy: int) -> None:
    return


def brass_surface_color(x: int, y: int, horizontal: bool = True, bias: int = 0) -> tuple[int, int, int, int]:
    """Clean bright brass: flat body color with a soft low-contrast shade."""
    major = y if horizontal else x
    t = major / 7
    if t < 0.24:
        base = color("brass2")
    elif t < 0.78:
        base = color("brass")
    else:
        base = blend(color("brass"), color("brass_dark"), (t - 0.78) / 0.22)
    return adjust(base, bias)


def draw_brass_strip(
    draw: ImageDraw.ImageDraw,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    horizontal: bool,
    bias: int = 0,
) -> None:
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            local_x = x - x0
            local_y = y - y0
            draw.point((x, y), fill=brass_surface_color(local_x, local_y, horizontal, bias))


def draw_brass_frame(draw: ImageDraw.ImageDraw, x0: int, y0: int, x1: int, y1: int, inset: int = 3) -> None:
    rect(draw, (x0, y0, x1, y1), "brass_shadow")
    draw_brass_strip(draw, x0 + 1, y0 + 1, x1 - 1, y0 + inset - 1, True)
    draw_brass_strip(draw, x0 + 1, y1 - inset + 1, x1 - 1, y1 - 1, True, -8)
    draw_brass_strip(draw, x0 + 1, y0 + inset, x0 + inset - 1, y1 - inset, False, -2)
    draw_brass_strip(draw, x1 - inset + 1, y0 + inset, x1 - 1, y1 - inset, False, -10)
    rect(draw, (x0 + inset, y0 + inset, x1 - inset, y1 - inset), (0, 0, 0, 0))
    line(draw, (x0 + 1, y0 + 1, x1 - 1, y0 + 1), "brass2")
    line(draw, (x0 + 1, y0 + 1, x0 + 1, y1 - 1), "brass")
    line(draw, (x0 + 1, y1 - 1, x1 - 1, y1 - 1), "brass_dark")
    line(draw, (x1 - 1, y0 + 1, x1 - 1, y1 - 1), "brass_dark")

def draw_panel_plate(draw: ImageDraw.ImageDraw, x0: int, y0: int, x1: int, y1: int) -> None:
    rect(draw, (x0, y0, x1, y1), "black")
    rect(draw, (x0 + 1, y0 + 1, x1 - 1, y1 - 1), "iron")
    for y in range(y0 + 4, y1, 6):
        line(draw, (x0 + 2, y, x1 - 2, y), (18, 20, 21, 255))
    for x in range(x0 + 4, x1, 6):
        line(draw, (x, y0 + 2, x, y1 - 2), (18, 20, 21, 255))
    rect(draw, (x0 + 3, y0 + 3, x1 - 3, y1 - 3), (0, 0, 0, 0), "iron2")


def generate_chest_frame() -> Image.Image:
    image = img((64, 64), "brass_dark")
    d = ImageDraw.Draw(image)
    draw_brass_strip(d, 0, 0, 63, 63, True, 0)
    line(d, (0, 0, 63, 0), "brass2")
    line(d, (0, 63, 63, 63), "brass_dark")
    return image


def generate_chest_panel() -> Image.Image:
    image = img((64, 64), "iron")
    d = ImageDraw.Draw(image)
    draw_brass_frame(d, 0, 0, 63, 63, 4)
    rect(d, (4, 4, 59, 59), "black")
    rect(d, (6, 6, 57, 57), "iron")
    for i in range(9, 58, 8):
        line(d, (i, 6, i, 57), (18, 20, 21, 255))
        line(d, (6, i, 57, i), (18, 20, 21, 255))
    for i in range(10, 57, 16):
        line(d, (7, i, 56, i), (50, 53, 52, 255))
    draw_brass_corner(d, 3, 3, 1, 1)
    draw_brass_corner(d, 60, 3, -1, 1)
    draw_brass_corner(d, 3, 60, 1, -1)
    draw_brass_corner(d, 60, 60, -1, -1)
    line(d, (6, 6, 57, 6), "iron3")
    line(d, (6, 6, 6, 57), "iron3")
    line(d, (6, 57, 57, 57), "black")
    line(d, (57, 6, 57, 57), "black")
    add_noise(image, 5, 3)
    return image


def generate_chest_panel_top() -> Image.Image:
    """顶/底面专用面板：去除上下方向指示元素，保留无方向性的基础网格。"""
    image = img((64, 64), "iron")
    d = ImageDraw.Draw(image)
    draw_brass_frame(d, 0, 0, 63, 63, 4)
    rect(d, (4, 4, 59, 59), "black")
    rect(d, (6, 6, 57, 57), "iron")
    for i in range(9, 58, 8):
        line(d, (i, 6, i, 57), (18, 20, 21, 255))
        line(d, (6, i, 57, i), (18, 20, 21, 255))
    draw_brass_corner(d, 3, 3, 1, 1)
    draw_brass_corner(d, 60, 3, -1, 1)
    draw_brass_corner(d, 3, 60, 1, -1)
    draw_brass_corner(d, 60, 60, -1, -1)
    line(d, (6, 6, 57, 6), "iron3")
    line(d, (6, 6, 6, 57), "iron3")
    line(d, (6, 57, 57, 57), "black")
    line(d, (57, 6, 57, 57), "black")
    add_noise(image, 5, 3)
    return image


def draw_brass_border(d: ImageDraw.ImageDraw) -> None:
    """64px face, 16 model units. The outer 8px frame is seam-safe.

    No random noise is placed on the outer frame because all six cube faces can
    be mirrored or rotated by Minecraft's block model UV rules.
    """
    rect(d, (0, 0, 63, 63), "brass_shadow")
    draw_brass_strip(d, 1, 1, 62, 7, True)
    draw_brass_strip(d, 1, 56, 62, 62, True, -8)
    draw_brass_strip(d, 1, 8, 7, 55, False, -2)
    draw_brass_strip(d, 56, 8, 62, 55, False, -10)

    rect(d, (0, 0, 63, 63), None, "brass_shadow")
    rect(d, (1, 1, 62, 62), None, "brass_dark")
    line(d, (2, 2, 61, 2), "brass2")
    line(d, (2, 2, 2, 61), "brass")
    line(d, (2, 61, 61, 61), "brass_dark")
    line(d, (61, 2, 61, 61), "brass_dark")
    rect(d, (7, 7, 56, 56), None, "brass_shadow")
    line(d, (8, 8, 55, 8), "brass_dark")
    line(d, (8, 8, 8, 55), "brass_dark")
    line(d, (8, 55, 55, 55), "brass_shadow")
    line(d, (55, 8, 55, 55), "brass_shadow")

    draw_brass_corner(d, 3, 3, 1, 1)
    draw_brass_corner(d, 60, 3, -1, 1)
    draw_brass_corner(d, 3, 60, 1, -1)
    draw_brass_corner(d, 60, 60, -1, -1)

def draw_inner_panel(d: ImageDraw.ImageDraw) -> None:
    rect(d, (8, 8, 55, 55), "black")
    rect(d, (10, 10, 53, 53), "iron")
    rect(d, (11, 11, 52, 52), (41, 45, 45, 255))
    for i in range(18, 47, 8):
        line(d, (12, i, 51, i), (14, 16, 17, 255))
        line(d, (i, 12, i, 51), (14, 16, 17, 255))
    line(d, (10, 10, 53, 10), "iron3")
    line(d, (10, 10, 10, 53), "iron3")
    line(d, (10, 53, 53, 53), "black")
    line(d, (53, 10, 53, 53), "black")


def draw_face_plate(vertical: bool) -> Image.Image:
    image = img((64, 64), "brass")
    d = ImageDraw.Draw(image)

    # Exact mapping: 16 model units -> 64px, so 1 unit = 4px.
    # Outer frame: 2 units / 8px. Configurable module area: centered 12 units.
    draw_brass_border(d)
    draw_inner_panel(d)

    if vertical:
        rect(d, (13, 13, 16, 50), (74, 82, 79, 255))
        rect(d, (47, 13, 50, 50), (74, 82, 79, 255))
        line(d, (14, 14, 14, 49), "iron3")
        line(d, (48, 14, 48, 49), "iron3")
        line(d, (16, 14, 16, 49), "black")
        line(d, (50, 14, 50, 49), "black")
        rect(d, (21, 21, 42, 42), (21, 24, 24, 255), "iron2")
        rect(d, (25, 25, 38, 38), (14, 17, 17, 255), "black")
        line(d, (29, 12, 34, 12), "brass_shadow")
        line(d, (29, 51, 34, 51), "brass_shadow")
        line(d, (14, 29, 14, 34), "brass_shadow")
        line(d, (49, 29, 49, 34), "brass_shadow")
    else:
        rect(d, (15, 15, 48, 48), (24, 27, 27, 255), "iron2")
        rect(d, (19, 19, 44, 44), (16, 19, 19, 255), "black")
        ellipse(d, (24, 24, 39, 39), (32, 38, 38, 255), "iron3", 1)
        ellipse(d, (28, 28, 35, 35), "black", "brass_dark", 1)
        line(d, (16, 16, 20, 16), "brass_shadow")
        line(d, (43, 16, 47, 16), "brass_shadow")
        line(d, (16, 47, 20, 47), "brass_shadow")
        line(d, (43, 47, 47, 47), "brass_shadow")

    return image


def generate_chest_face_side() -> Image.Image:
    return draw_face_plate(True)


def generate_chest_face_cap() -> Image.Image:
    return draw_face_plate(False)


def draw_module_shell(base: Image.Image) -> ImageDraw.ImageDraw:
    d = ImageDraw.Draw(base)
    draw_brass_frame(d, 1, 1, 30, 30, 4)
    draw_panel_plate(d, 5, 5, 26, 26)
    draw_brass_corner(d, 3, 3, 1, 1)
    draw_brass_corner(d, 28, 3, -1, 1)
    draw_brass_corner(d, 3, 28, 1, -1)
    draw_brass_corner(d, 28, 28, -1, -1)
    return d


def item_port_frame(phase: int) -> Image.Image:
    image = img((32, 32))
    d = draw_module_shell(image)
    rect(d, (8, 7, 23, 24), (24, 29, 29, 255), "black")
    rect(d, (10, 9, 21, 22), (36, 43, 42, 255), "iron2")
    for y in (10, 13, 16, 19, 22):
        line(d, (11, y, 20, y), (13, 18, 19, 255))

    for arrow_index in range(4):
        y = 21 - ((phase + arrow_index * 3) % 12)
        if 12 <= y <= 21:
            line(d, (17, y - 1, 19, y + 1), (70, 45, 16, 115))
            line(d, (17, y - 1, 15, y + 1), (70, 45, 16, 115))
            line(d, (16, y - 2, 18, y), "amber")
            line(d, (16, y - 2, 14, y), "amber")
            rect(d, (16, y - 2, 16, y - 2), "brass_hi")

    rect(d, (10, 9, 21, 22), None, "black")
    return image


def generate_item_port() -> Image.Image:
    frames = [item_port_frame(i) for i in range(12)]
    strip = img((32, 32 * len(frames)))
    for i, frame in enumerate(frames):
        strip.alpha_composite(frame, (0, i * 32))
    return strip


def fluid_frame(phase: int) -> Image.Image:
    image = img((32, 32))
    d = draw_module_shell(image)
    line(d, (5, 15, 8, 15), "copper", 2)
    line(d, (23, 15, 26, 15), "copper", 2)
    rect(d, (9, 6, 22, 25), (15, 20, 21, 255), "black")
    rect(d, (10, 7, 21, 24), (43, 61, 63, 255), "iron2")
    rect(d, (12, 8, 19, 23), (19, 33, 36, 255))
    line(d, (13, 8, 18, 8), (92, 130, 130, 255))
    line(d, (12, 9, 12, 22), (73, 102, 104, 255))
    line(d, (19, 9, 19, 22), (17, 24, 25, 255))

    fill_bottom = 22
    fill_top = [22, 21, 19, 18, 16, 14, 12, 10, 9, 8][phase % 10]
    rect(d, (13, fill_top, 18, fill_bottom), (19, 145, 207, 255))
    rect(d, (13, fill_top, 18, fill_top), "cyan_soft")
    if fill_top + 3 <= fill_bottom:
        line(d, (14, fill_top + 3, 17, fill_top + 3), "cyan", 1)
    if fill_top + 7 <= fill_bottom:
        line(d, (14, fill_top + 7, 17, fill_top + 7), (80, 214, 230, 255), 1)
    for bx, by in ((14, 20), (17, 16), (15, 12)):
        if fill_top <= by <= fill_bottom and (phase + bx + by) % 3 == 0:
            rect(d, (bx, by, bx, by), "cyan_soft")
    return image


def generate_fluid_window() -> Image.Image:
    frames = [fluid_frame(i) for i in range(10)]
    strip = img((32, 32 * len(frames)))
    for i, frame in enumerate(frames):
        strip.alpha_composite(frame, (0, i * 32))
    return strip


def energy_frame(phase: int) -> Image.Image:
    image = img((32, 32))
    d = draw_module_shell(image)
    line(d, (5, 16, 9, 16), "amber", 2)
    line(d, (22, 16, 26, 16), "amber", 2)
    rect(d, (8, 8, 23, 23), (12, 16, 17, 255), "black")
    rect(d, (10, 10, 21, 21), (34, 45, 45, 255), "iron2")
    rect(d, (12, 11, 19, 20), (18, 65, 75, 255), "cyan")
    yoff = [-2, -1, 0, 1, 2, 1, 0, -1][phase % 8]
    bolt = [(18, 9 + yoff), (13, 15 + yoff), (16, 15 + yoff),
            (13, 22 + yoff), (20, 13 + yoff), (17, 13 + yoff)]
    poly(d, [(x + 1, y + 1) for x, y in bolt], (70, 45, 16, 160))
    poly(d, bolt, "amber")
    line(d, (17, 11 + yoff, 14, 14 + yoff, 17, 14 + yoff), "brass_hi", 1)
    return image


def generate_energy_core() -> Image.Image:
    frames = [energy_frame(i) for i in range(8)]
    strip = img((32, 32 * len(frames)))
    for i, frame in enumerate(frames):
        strip.alpha_composite(frame, (0, i * 32))
    return strip


def bearing_ring_frame(phase: int) -> Image.Image:
    image = img((32, 32))
    d = ImageDraw.Draw(image)
    rect(d, (0, 0, 31, 31), (33, 37, 36, 255))
    rect(d, (1, 1, 30, 30), (50, 56, 54, 255), (20, 23, 23, 255))
    rect(d, (3, 3, 28, 28), (72, 79, 75, 255), (48, 53, 51, 255))
    rect(d, (5, 5, 26, 26), (43, 49, 48, 255), (22, 25, 25, 255))
    rect(d, (7, 7, 24, 24), (54, 61, 58, 255))
    for i in range(9, 24, 4):
        line(d, (8, i, 23, i), (78, 86, 82, 255))
        line(d, (i, 8, i, 23), (37, 42, 41, 255))
    rect(d, (10, 10, 21, 21), (45, 51, 50, 255), (24, 27, 27, 255))
    rect(d, (12, 12, 19, 19), (86, 93, 88, 255), (58, 64, 61, 255))
    rect(d, (14, 14, 17, 17), (48, 55, 53, 255), (25, 29, 29, 255))
    line(d, (3, 3, 28, 3), (94, 101, 95, 255))
    line(d, (3, 3, 3, 28), (86, 94, 89, 255))
    line(d, (3, 28, 28, 28), (27, 31, 31, 255))
    line(d, (28, 3, 28, 28), (27, 31, 31, 255))
    line(d, (6, 6, 25, 6), (90, 99, 94, 255))
    line(d, (6, 25, 25, 25), (29, 33, 33, 255))

    path = [
        (9, 11), (13, 11), (17, 11), (21, 11),
        (21, 15), (21, 19),
        (17, 21), (13, 21), (9, 21),
        (9, 17), (9, 13),
    ]
    for offset in (0, 2):
        x, y = path[(phase + offset) % len(path)]
        c = (96, 106, 101, 255) if offset == 0 else (78, 86, 82, 255)
        rect(d, (x, y, x + 1, y + 1), c)
    return image


def generate_bearing_ring() -> Image.Image:
    frames = [bearing_ring_frame(i) for i in range(8)]
    strip = img((32, 32 * len(frames)))
    for i, frame in enumerate(frames):
        strip.alpha_composite(frame, (0, i * 32))
    return strip


def generate_bearing_shaft() -> Image.Image:
    image = img((16, 16))
    d = ImageDraw.Draw(image)
    for y in range(16):
        for x in range(16):
            d.point((x, y), fill=(0, 0, 0, 0))

    axis_path = ROOT / "referance/Create/src/main/resources/assets/create/textures/block/axis.png"
    axis_top_path = ROOT / "referance/Create/src/main/resources/assets/create/textures/block/axis_top.png"
    if axis_path.exists() and axis_top_path.exists():
        axis = Image.open(axis_path).convert("RGBA")
        axis_top = Image.open(axis_top_path).convert("RGBA")
        image.alpha_composite(axis.crop((6, 0, 10, 16)), (0, 0))
        image.alpha_composite(axis_top.crop((6, 6, 10, 10)), (6, 6))
    else:
        shaft = (116, 122, 116, 255)
        for y in range(16):
            for x in range(4):
                d.point((x, y), fill=shaft)
        rect(d, (6, 6, 9, 9), shaft)
    return image


CARD_PAPER = (235, 238, 226, 255)
CARD_PAPER_HI = (255, 255, 242, 255)
CARD_PAPER_SHADOW = (176, 182, 166, 255)
CARD_INK = (18, 21, 22, 255)
CARD_MUTED = (116, 124, 111, 255)
CARD_FRAME = 64
AA_SCALE = 4


def fill_rgba(value: str | tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    return color(value) if isinstance(value, str) else value


def scaled_box(xy, scale: int = AA_SCALE):
    return tuple(int(round(v * scale)) for v in xy)


def scaled_points(points, scale: int = AA_SCALE):
    return [(int(round(x * scale)), int(round(y * scale))) for x, y in points]


def paint_aa(base: Image.Image, painter, scale: int = AA_SCALE) -> None:
    layer = Image.new("RGBA", (base.width * scale, base.height * scale), (0, 0, 0, 0))
    painter(ImageDraw.Draw(layer), scale)
    base.alpha_composite(layer.resize(base.size, Image.Resampling.LANCZOS))


def draw_upgrade_card(accent: str | tuple[int, int, int, int], phase: int = 0) -> Image.Image:
    image = img((CARD_FRAME, CARD_FRAME))
    d = ImageDraw.Draw(image)
    accent_rgba = fill_rgba(accent)
    accent_dark = adjust(accent_rgba, -64)
    accent_soft = adjust(accent_rgba, -18)
    glint = [0, 10, 24, 10][phase % 4]

    outer = [(15, 4), (49, 4), (53, 8), (53, 56), (49, 60), (15, 60), (11, 56), (11, 8)]
    brass_shadow = [(16, 5), (48, 5), (52, 9), (52, 55), (48, 59), (16, 59), (12, 55), (12, 9)]
    brass = [(17, 6), (47, 6), (51, 10), (51, 54), (47, 58), (17, 58), (13, 54), (13, 10)]
    paper = [(19, 9), (45, 9), (48, 12), (48, 52), (45, 55), (19, 55), (16, 52), (16, 12)]

    poly(d, outer, CARD_INK)
    poly(d, brass_shadow, "brass_dark")
    poly(d, brass, (196, 143, 48, 255))
    poly(d, paper, CARD_PAPER)

    line(d, (20, 10, 44, 10), CARD_PAPER_HI, 1)
    line(d, (17, 13, 17, 51), CARD_PAPER_HI, 1)
    line(d, (20, 54, 44, 54), CARD_PAPER_SHADOW, 1)
    line(d, (47, 13, 47, 51), CARD_PAPER_SHADOW, 1)

    rect(d, (21, 13, 26, 18), CARD_INK)
    rect(d, (22, 14, 25, 17), adjust(accent_rgba, glint))
    rect(d, (38, 46, 43, 51), CARD_INK)
    rect(d, (39, 47, 42, 50), accent_dark)
    rect(d, (28, 14, 40, 15), accent_soft)
    rect(d, (24, 49, 36, 50), accent_dark)
    return image


def make_upgrade_strip(frame_fn, frames: int = 4) -> Image.Image:
    first = frame_fn(0)
    w, h = first.size
    strip = img((w, h * frames))
    strip.alpha_composite(first, (0, 0))
    for i in range(1, frames):
        strip.alpha_composite(frame_fn(i), (0, i * h))
    return strip


def generate_upgrade_card_base() -> Image.Image:
    image = draw_upgrade_card("cyan", 0)
    def paint(d: ImageDraw.ImageDraw, s: int) -> None:
        d.rounded_rectangle(scaled_box((27.0, 27.0, 37.0, 37.0), s), radius=2 * s, fill=(207, 216, 204, 255), outline=CARD_PAPER_SHADOW, width=max(1, int(1.2 * s)))
        d.line(scaled_points([(29.8, 32.0), (34.2, 32.0)], s), fill=color("brass"), width=max(1, int(1.4 * s)))
        d.line(scaled_points([(32.0, 29.8), (32.0, 34.2)], s), fill=color("brass"), width=max(1, int(1.4 * s)))

    paint_aa(image, paint)
    return image


def storage_upgrade_frame(phase: int) -> Image.Image:
    image = draw_upgrade_card("amber", phase)
    pulse = [0, 8, 20, 8][phase % 4]
    cells = ((22.0, 22.0, 31.0, 31.0), (33.0, 22.0, 42.0, 31.0), (22.0, 34.0, 31.0, 43.0), (33.0, 34.0, 42.0, 43.0))

    def paint(d: ImageDraw.ImageDraw, s: int) -> None:
        d.rounded_rectangle(scaled_box((20.8, 20.8, 43.2, 44.2), s), radius=3.0 * s, fill=(0, 0, 0, 34))
        for idx, box in enumerate(cells):
            fill = adjust((220, 157, 58, 255), pulse - idx * 9)
            d.rounded_rectangle(scaled_box(box, s), radius=1.9 * s, fill=CARD_INK)
            d.rounded_rectangle(scaled_box((box[0] + 1.1, box[1] + 1.1, box[2] - 1.1, box[3] - 1.1), s), radius=1.2 * s, fill=fill)
            d.line(scaled_points([(box[0] + 2.0, box[1] + 2.0), (box[2] - 2.0, box[1] + 2.0)], s), fill=adjust(color("brass_hi"), pulse), width=max(1, int(1.0 * s)))
            d.rounded_rectangle(scaled_box((box[0] + 3.0, box[1] + 5.3, box[2] - 3.0, box[1] + 6.7), s), radius=0.5 * s, fill=(88, 59, 26, 255))

        d.line(scaled_points([(31.9, 21.5), (31.9, 43.5)], s), fill=(90, 65, 34, 210), width=max(1, int(0.8 * s)))
        d.line(scaled_points([(21.5, 32.0), (42.5, 32.0)], s), fill=(90, 65, 34, 210), width=max(1, int(0.8 * s)))

    paint_aa(image, paint)
    return image


def generate_storage_upgrade() -> Image.Image:
    return make_upgrade_strip(storage_upgrade_frame)


def fluid_upgrade_frame(phase: int) -> Image.Image:
    image = draw_upgrade_card("blue", phase)
    yoff = [0.0, 0.8, 0.0, -0.8][phase % 4]
    wave = [0.0, 1.0, 0.0, -1.0][phase % 4]

    def paint(d: ImageDraw.ImageDraw, s: int) -> None:
        d.polygon(scaled_points([(32.0, 16.8 + yoff), (21.0, 34.2 + yoff), (43.0, 34.2 + yoff)], s), fill=CARD_INK)
        d.ellipse(scaled_box((20.8, 26.8 + yoff, 43.2, 49.2 + yoff), s), fill=CARD_INK)
        d.polygon(scaled_points([(32.0, 19.0 + yoff), (24.2, 34.5 + yoff), (39.8, 34.5 + yoff)], s), fill=(35, 117, 222, 255))
        d.ellipse(scaled_box((23.7, 28.8 + yoff, 40.3, 46.8 + yoff), s), fill=(31, 157, 224, 255))
        d.ellipse(scaled_box((26.2, 30.8 + yoff, 37.8, 44.2 + yoff), s), fill=(48, 191, 232, 225))
        d.line(scaled_points([(27.0, 37.5 + wave), (30.2, 36.0 - wave), (34.2, 39.0 + wave), (38.5, 37.2 - wave)], s), fill=(225, 255, 255, 225), width=max(1, int(1.5 * s)))
        d.line(scaled_points([(28.4, 31.4 + yoff), (31.0, 25.8 + yoff)], s), fill=(213, 255, 255, 190), width=max(1, int(1.3 * s)))

    paint_aa(image, paint)
    return image


def generate_fluid_upgrade() -> Image.Image:
    return make_upgrade_strip(fluid_upgrade_frame)


def network_upgrade_frame(phase: int) -> Image.Image:
    image = draw_upgrade_card("green", phase)
    pulse = [0, 12, 28, 12][phase % 4]

    def paint(d: ImageDraw.ImageDraw, s: int) -> None:
        arcs = ((17.4, 18.8, 46.6, 48.0), (22.7, 26.4, 41.3, 45.0), (28.0, 34.1, 36.0, 42.1))
        for idx, box in enumerate(arcs):
            d.arc(scaled_box((box[0], box[1] + 1.0, box[2], box[3] + 1.0), s), 205, 335, fill=CARD_INK, width=max(1, int(4.8 * s)))
            d.arc(scaled_box(box, s), 205, 335, fill=adjust(color("green"), pulse - idx * 16), width=max(1, int(3.5 * s)))
            d.arc(scaled_box((box[0] + 0.8, box[1] + 0.6, box[2] - 0.8, box[3] - 0.6), s), 220, 320, fill=adjust(color("cyan_soft"), pulse - idx * 20), width=max(1, int(0.9 * s)))
        d.ellipse(scaled_box((28.1, 39.1, 35.9, 46.9), s), fill=CARD_INK)
        d.ellipse(scaled_box((29.6, 40.0, 34.4, 44.8), s), fill=adjust(color("green"), pulse))

    paint_aa(image, paint)
    return image


def generate_network_upgrade() -> Image.Image:
    return make_upgrade_strip(network_upgrade_frame)


def energy_upgrade_frame(phase: int) -> Image.Image:
    image = draw_upgrade_card("amber", phase)
    glow = [12, 30, 52, 30][phase]
    bolt = [(37.0, 17.0), (26.0, 30.5), (32.5, 31.2), (26.8, 47.0), (40.2, 29.3), (34.0, 28.4)]

    def paint(d: ImageDraw.ImageDraw, s: int) -> None:
        d.polygon(scaled_points([(x + 1.2, y + 1.3) for x, y in bolt], s), fill=(88, 54, 12, 185))
        d.polygon(scaled_points(bolt, s), fill=CARD_INK)
        inset = [(36.0, 19.0), (28.7, 29.0), (35.0, 30.0), (29.4, 43.5), (38.0, 31.0), (31.6, 30.2)]
        d.polygon(scaled_points(inset, s), fill=adjust(color("amber"), glow))
        d.line(scaled_points([(34.8, 20.8), (29.4, 28.5), (34.7, 29.0), (30.7, 39.8)], s), fill=color("brass_hi"), width=max(1, int(1.0 * s)))

    paint_aa(image, paint)
    return image


def generate_energy_upgrade() -> Image.Image:
    return make_upgrade_strip(energy_upgrade_frame)


def stress_upgrade_frame(phase: int) -> Image.Image:
    image = draw_upgrade_card("brass_hi", phase)
    spin = phase * 18

    def paint(d: ImageDraw.ImageDraw, s: int) -> None:
        d.rounded_rectangle(scaled_box((20.0, 29.5, 44.0, 36.5), s), radius=2.3 * s, fill=CARD_INK)
        d.rounded_rectangle(scaled_box((22.0, 30.8, 42.0, 35.2), s), radius=1.5 * s, fill=color("iron3"))
        d.line(scaled_points([(23.0, 31.5), (41.0, 31.5)], s), fill=(119, 122, 115, 255), width=max(1, int(0.9 * s)))

        d.ellipse(scaled_box((20.4, 20.4, 43.6, 43.6), s), fill=CARD_INK)
        d.ellipse(scaled_box((22.4, 22.4, 41.6, 41.6), s), fill=(130, 84, 30, 255))
        d.ellipse(scaled_box((24.4, 24.4, 39.6, 39.6), s), fill=color("brass2"))
        d.ellipse(scaled_box((27.8, 27.8, 36.2, 36.2), s), fill=CARD_INK)
        d.ellipse(scaled_box((29.4, 29.4, 34.6, 34.6), s), fill=color("iron2"))
        for angle in (35, 145, 215, 325):
            a = math.radians(angle + spin)
            x = 32.0 + math.cos(a) * 7.2
            y = 32.0 + math.sin(a) * 7.2
            d.ellipse(scaled_box((x - 1.4, y - 1.4, x + 1.4, y + 1.4), s), fill=CARD_INK)
            d.ellipse(scaled_box((x - 0.7, y - 0.7, x + 0.7, y + 0.7), s), fill=color("brass_hi"))
        d.line(scaled_points([(25.5, 25.7), (30.0, 23.8)], s), fill=(255, 231, 143, 190), width=max(1, int(1.1 * s)))

    paint_aa(image, paint)
    return image


def generate_stress_upgrade() -> Image.Image:
    return make_upgrade_strip(stress_upgrade_frame)


def generate_tablet() -> Image.Image:
    image = img((64, 64))
    d = ImageDraw.Draw(image)
    poly(d, [(12, 3), (51, 3), (60, 12), (60, 51), (51, 60), (12, 60), (3, 51), (3, 12)], "brass_shadow")
    poly(d, [(13, 5), (50, 5), (58, 13), (58, 50), (50, 58), (13, 58), (5, 50), (5, 13)], "brass")
    poly(d, [(16, 9), (47, 9), (54, 16), (54, 47), (47, 54), (16, 54), (9, 47), (9, 16)], "black")
    rect(d, (14, 14, 49, 44), "jade_dark", "cyan")
    rect(d, (17, 17, 46, 41), (7, 29, 34, 255))
    for y in range(20, 40, 5):
        line(d, (18, y, 45, y), (14, 83, 91, 255))
    for x in range(21, 45, 6):
        line(d, (x, 18, x, 40), (13, 63, 69, 255))
    ellipse(d, (24, 20, 39, 35), (11, 88, 74, 255), "jade")
    ellipse(d, (28, 24, 35, 31), "jade")
    line(d, (31, 18, 31, 37), "cyan", 1)
    line(d, (22, 28, 42, 28), "cyan", 1)
    for angle in range(0, 360, 60):
        x = 32 + int(math.cos(math.radians(angle)) * 12)
        y = 28 + int(math.sin(math.radians(angle)) * 8)
        line(d, (32, 28, x, y), "jade", 1)
    rect(d, (16, 48, 21, 52), "cyan", "black")
    rect(d, (25, 48, 30, 52), "green", "black")
    rect(d, (34, 48, 39, 52), "amber", "black")
    rect(d, (43, 48, 48, 52), "red", "black")
    draw_brass_corner(d, 13, 13, 1, 1)
    draw_brass_corner(d, 50, 13, -1, 1)
    draw_brass_corner(d, 13, 50, 1, -1)
    draw_brass_corner(d, 50, 50, -1, -1)
    line(d, (13, 5, 50, 5), "brass_hi")
    line(d, (5, 13, 5, 50), "brass_hi")
    add_noise(image, 5, 3)
    return image


def face(texture: str, uv=None):
    return {"uv": uv or [0, 0, 16, 16], "texture": texture}


def element_face_uv(frm, to, side: str) -> list[float]:
    if side in ("north", "south"):
        width = to[0] - frm[0]
        height = to[1] - frm[1]
    elif side in ("east", "west"):
        width = to[2] - frm[2]
        height = to[1] - frm[1]
    else:
        width = to[0] - frm[0]
        height = to[2] - frm[2]
    return [0, 0, width, height]


def cube_element(name: str, frm, to, texture: str):
    return {
        "name": name,
        "from": frm,
        "to": to,
        "faces": {side: face(texture, element_face_uv(frm, to, side)) for side in ("north", "east", "south", "west", "up", "down")},
    }


def plane_element(name: str, frm, to, side: str, texture: str):
    return {
        "name": name,
        "from": frm,
        "to": to,
        "faces": {side: face(texture)},
    }


def generate_chest_model() -> dict:
    return {
        "credit": "Pocket Homestead - brass mechanical side-configurable chest",
        "textures": {
            "side": "pockethomestead:block/chest_face_side",
            "cap": "pockethomestead:block/chest_face_cap",
            "item_port": "pockethomestead:block/chest_item_port",
            "fluid_window": "pockethomestead:block/chest_fluid_window",
            "energy_core": "pockethomestead:block/chest_energy_core",
            "bearing_ring": "pockethomestead:block/chest_bearing_ring",
            "bearing_shaft": "pockethomestead:block/chest_bearing_shaft",
            "particle": "pockethomestead:block/chest_face_side",
        },
        "elements": [
            {
                "name": "body",
                "from": [0, 0, 0],
                "to": [16, 16, 16],
                "faces": {
                    "north": face("#side"),
                    "south": face("#side"),
                    "west": face("#side"),
                    "east": face("#side"),
                    "up": face("#cap"),
                    "down": face("#cap"),
                },
            }
        ],
        "display": {
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 2], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [0, 4, 0], "scale": [0.4, 0.4, 0.4]},
            "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
            "head": {"rotation": [0, 180, 0]},
            "fixed": {"scale": [0.5, 0.5, 0.5]},
        },
    }


def write_mcmeta() -> None:
    (BLOCK / "chest_item_port.png.mcmeta").write_text(
        json.dumps({"animation": {"frametime": 4, "frames": list(range(12))}}, indent=2) + "\n",
        encoding="utf-8",
    )
    (BLOCK / "chest_energy_core.png.mcmeta").write_text(
        json.dumps({"animation": {"frametime": 5, "frames": [0, 1, 2, 3, 4, 5, 6, 7]}}, indent=2) + "\n",
        encoding="utf-8",
    )
    (BLOCK / "chest_fluid_window.png.mcmeta").write_text(
        json.dumps({"animation": {"frametime": 8, "frames": list(range(10))}}, indent=2) + "\n",
        encoding="utf-8",
    )
    (BLOCK / "chest_bearing_ring.png.mcmeta").write_text(
        json.dumps({"animation": {"frametime": 4, "frames": list(range(8))}}, indent=2) + "\n",
        encoding="utf-8",
    )
    for name in (
        "storage_upgrade",
        "fluid_upgrade",
        "network_upgrade",
        "energy_transfer_upgrade",
        "stress_upgrade",
    ):
        (ITEM / f"{name}.png.mcmeta").write_text(
            json.dumps({"animation": {"frametime": 7, "frames": [0, 1, 2, 3]}}, indent=2) + "\n",
            encoding="utf-8",
        )


def make_preview(paths: list[Path]) -> None:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    scale = 4
    pad = 16
    cell_w = 220
    cell_h = 300
    cols = 3
    rows = math.ceil(len(paths) / cols)
    sheet = Image.new("RGBA", (cols * cell_w, rows * cell_h), (26, 28, 30, 255))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for idx, path in enumerate(paths):
        source = Image.open(path).convert("RGBA")
        if source.height > source.width and source.height % source.width == 0:
            source = source.crop((0, 0, source.width, source.width))
        preview = source.resize((source.width * scale, source.height * scale), Image.Resampling.NEAREST)
        x = (idx % cols) * cell_w + pad
        y = (idx // cols) * cell_h + pad
        checker = Image.new("RGBA", preview.size, (0, 0, 0, 0))
        cd = ImageDraw.Draw(checker)
        tile = 8
        for cy in range(0, preview.height, tile):
            for cx in range(0, preview.width, tile):
                c = (62, 66, 69, 255) if ((cx // tile + cy // tile) % 2) else (43, 46, 49, 255)
                cd.rectangle((cx, cy, cx + tile - 1, cy + tile - 1), fill=c)
        checker.alpha_composite(preview)
        sheet.alpha_composite(checker, (x, y))
        draw.text((x, y + preview.height + 5), path.name, fill=(230, 230, 220, 255), font=font)
    save(sheet, PREVIEW / "pockethomestead_art_sheet.png")


def main() -> None:
    outputs = {
        BLOCK / "chest_frame.png": generate_chest_frame(),
        BLOCK / "chest_panel.png": generate_chest_panel(),
        BLOCK / "chest_panel_top.png": generate_chest_panel_top(),
        BLOCK / "chest_face_side.png": generate_chest_face_side(),
        BLOCK / "chest_face_cap.png": generate_chest_face_cap(),
        BLOCK / "chest_item_port.png": generate_item_port(),
        BLOCK / "chest_fluid_window.png": generate_fluid_window(),
        BLOCK / "chest_energy_core.png": generate_energy_core(),
        BLOCK / "chest_bearing_ring.png": generate_bearing_ring(),
        BLOCK / "chest_bearing_shaft.png": generate_bearing_shaft(),
        ITEM / "upgrade_card_base.png": generate_upgrade_card_base(),
        ITEM / "storage_upgrade.png": generate_storage_upgrade(),
        ITEM / "fluid_upgrade.png": generate_fluid_upgrade(),
        ITEM / "network_upgrade.png": generate_network_upgrade(),
        ITEM / "energy_transfer_upgrade.png": generate_energy_upgrade(),
        ITEM / "stress_upgrade.png": generate_stress_upgrade(),
        ITEM / "homestead_tablet.png": generate_tablet(),
    }
    for path, image in outputs.items():
        save(image, path)
    write_mcmeta()
    MODEL_BLOCK.mkdir(parents=True, exist_ok=True)
    (MODEL_BLOCK / "homestead_chest.json").write_text(
        json.dumps(generate_chest_model(), indent=2) + "\n",
        encoding="utf-8",
    )
    make_preview(list(outputs.keys()))


if __name__ == "__main__":
    main()
