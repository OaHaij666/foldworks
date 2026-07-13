from __future__ import annotations

from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "foldworks" / "textures" / "gui" / "chest.png"
MCMETA = OUT.with_suffix(OUT.suffix + ".mcmeta")

# Java keeps using a 256x256 logical atlas. The generated PNG is larger so
# Minecraft can sample smooth edges while all widget coordinates remain stable.
LOGICAL_ATLAS = 256
SCALE = 8
ATLAS = LOGICAL_ATLAS * SCALE

TRANSPARENT_RGB = (248, 253, 255, 0)
WHITE = (255, 255, 255, 255)
INK = (48, 63, 80, 255)
SURFACE = (248, 253, 255, 255)
SURFACE_SOFT = (239, 248, 255, 255)
SURFACE_SUNK = (224, 238, 248, 255)
SLOT = (218, 234, 246, 255)
SLOT_HOVER = (207, 232, 249, 255)
SLOT_SELECTED = (211, 239, 229, 255)
SLOT_LOCKED = (207, 218, 228, 255)
BORDER = (196, 218, 235, 255)
BORDER_SOFT = (216, 232, 244, 255)
BORDER_STRONG = (132, 181, 220, 255)
BLUE = (76, 139, 204, 255)
BLUE_SOFT = (222, 242, 255, 255)
BLUE_HOVER = (211, 237, 255, 255)
CYAN = (65, 179, 199, 255)
GREEN = (77, 185, 128, 255)
GOLD = (224, 174, 66, 255)
BRASS = (176, 132, 72, 255)
PINK = (241, 126, 144, 255)
DISABLED = (218, 228, 236, 255)


def mix(a: tuple[int, int, int, int], b: tuple[int, int, int, int], t: float) -> tuple[int, int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))  # type: ignore[return-value]


class D:
    def __init__(self, image: Image.Image):
        self.image = image
        self.draw = ImageDraw.Draw(image)

    def p(self, value):
        if isinstance(value, (tuple, list)):
            return tuple(self.p(v) for v in value)
        return round(value * SCALE)

    def rect(self, xy, fill=None, outline=None, width: int = 1) -> None:
        self.draw.rectangle(self.p(xy), fill=fill, outline=outline, width=max(1, self.p(width)))

    def rr(self, xy, r: float, fill=None, outline=None, width: int = 1) -> None:
        self.draw.rounded_rectangle(self.p(xy), radius=self.p(r), fill=fill, outline=outline, width=max(1, self.p(width)))

    def line(self, xy, fill=None, width: int = 1, joint: str | None = None) -> None:
        self.draw.line(self.p(xy), fill=fill, width=max(1, self.p(width)), joint=joint)

    def ellipse(self, xy, fill=None, outline=None, width: int = 1) -> None:
        self.draw.ellipse(self.p(xy), fill=fill, outline=outline, width=max(1, self.p(width)))

    def arc(self, xy, start: int, end: int, fill=None, width: int = 1) -> None:
        self.draw.arc(self.p(xy), start, end, fill=fill, width=max(1, self.p(width)))

    def polygon(self, pts: Iterable[tuple[float, float]], fill=None) -> None:
        self.draw.polygon([self.p(p) for p in pts], fill=fill)


def draw_soft_box(d: D, x: int, y: int, w: int, h: int, r: int, fill, border=BORDER, *, inset=False) -> None:
    if inset:
        d.rr((x, y, x + w - 1, y + h - 1), r, fill, mix(border, INK, 0.10), 1)
    else:
        d.rr((x, y, x + w - 1, y + h - 1), r, fill, border, 1)


def draw_panel_sprite(d: D, x: int, y: int, fill) -> None:
    draw_soft_box(d, x, y, 16, 16, 4, fill, BORDER)
    d.rr((x + 3, y + 3, x + 12, y + 12), 2, mix(fill, WHITE, 0.32))


def draw_panel32_sprite(d: D, x: int, y: int, fill, *, inset: bool = False) -> None:
    draw_soft_box(d, x, y, 32, 32, 8, fill, BORDER, inset=inset)
    if inset:
        d.rr((x + 5, y + 5, x + 26, y + 26), 6, mix(fill, WHITE, 0.12))
    else:
        d.rr((x + 5, y + 5, x + 26, y + 26), 6, mix(fill, WHITE, 0.24))


def draw_inset_sprite(d: D, x: int, y: int) -> None:
    draw_soft_box(d, x, y, 16, 16, 4, SURFACE_SUNK, BORDER, inset=True)
    d.rr((x + 3, y + 3, x + 12, y + 12), 2, mix(SURFACE_SUNK, WHITE, 0.14))


def draw_button_sprite(d: D, x: int, y: int, fill, border=BORDER, *, selected=False) -> None:
    draw_soft_box(d, x, y, 18, 18, 5, fill, border, inset=selected)


def draw_slot_sprite(d: D, x: int, y: int, fill, border=BORDER_STRONG, locked: bool = False) -> None:
    draw_soft_box(d, x, y, 18, 18, 4, fill, border, inset=True)
    d.rr((x + 3, y + 3, x + 14, y + 14), 3, mix(fill, WHITE, 0.16))
    if locked:
        d.rr((x + 6, y + 9, x + 13, y + 14), 1, mix(BORDER, INK, 0.08))
        d.arc((x + 6, y + 5, x + 13, y + 13), 180, 360, mix(BORDER, INK, 0.08), 2)


def draw_scrollbar(d: D) -> None:
    draw_soft_box(d, 0, 72, 7, 16, 3, SURFACE_SUNK, BORDER_SOFT, inset=True)
    d.rr((2, 74, 4, 85), 1, mix(SURFACE_SUNK, WHITE, 0.20))
    draw_soft_box(d, 8, 72, 7, 16, 3, SURFACE_SOFT, BORDER_STRONG)


def draw_switch(d: D) -> None:
    draw_soft_box(d, 24, 72, 34, 14, 7, SURFACE_SUNK, BORDER, inset=True)
    d.rr((64, 75, 74, 82), 4, GREEN)
    d.line((66, 75, 72, 75), mix(GREEN, WHITE, 0.35), 1)
    d.rr((80, 75, 90, 82), 4, mix(BORDER, WHITE, 0.12))
    d.line((82, 75, 88, 75), WHITE, 1)


def draw_chevrons(d: D) -> None:
    d.line((113, 50, 115, 53, 118, 50), BORDER_STRONG, 1, "curve")
    d.line((121, 54, 123, 51, 126, 54), BORDER_STRONG, 1, "curve")


def icon_image(base_rgb: tuple[int, int, int]) -> tuple[Image.Image, D]:
    img = Image.new("RGBA", (128 * SCALE, 128 * SCALE), (*base_rgb, 0))
    return img, D(img)


def paste_icon(image: Image.Image, x: int, y: int, kind: str) -> None:
    base = {
        "storage": BLUE[:3],
        "fluid": CYAN[:3],
        "network": GREEN[:3],
        "energy": GOLD[:3],
        "stress": BRASS[:3],
        "suite": PINK[:3],
    }[kind]
    icon, d = icon_image(base)
    if kind == "storage":
        d.rr((28, 38, 100, 94), 10, BLUE)
        d.rect((36, 48, 92, 57), WHITE)
        d.rr((53, 64, 75, 81), 3, WHITE)
    elif kind == "fluid":
        d.ellipse((38, 48, 90, 102), CYAN)
        d.polygon(((64, 20), (39, 67), (89, 67)), CYAN)
    elif kind == "network":
        d.arc((24, 32, 104, 112), 220, 320, GREEN, 10)
        d.arc((40, 52, 88, 100), 220, 320, GREEN, 9)
        d.arc((54, 70, 74, 90), 220, 320, GREEN, 7)
        d.ellipse((58, 94, 70, 106), GREEN)
    elif kind == "energy":
        d.polygon(((73, 14), (36, 61), (59, 61), (47, 114), (96, 52), (68, 52)), GOLD)
    elif kind == "stress":
        d.line((34, 64, 94, 64), BRASS, 13)
        d.line((64, 34, 64, 94), BRASS, 13)
        d.ellipse((45, 45, 83, 83), BRASS)
        d.ellipse((56, 56, 72, 72), WHITE)
    elif kind == "suite":
        d.line((43, 84, 84, 43), PINK, 13)
        d.line((47, 88, 88, 47), INK, 4)
        d.polygon(((31, 36), (42, 25), (55, 38), (48, 45), (42, 39), (38, 43)), PINK)
        d.ellipse((72, 72, 96, 96), PINK)
        d.ellipse((78, 78, 90, 90), WHITE)
    icon = icon.resize((32 * SCALE, 32 * SCALE), Image.Resampling.LANCZOS)
    image.alpha_composite(icon, (x * SCALE, y * SCALE))


def draw_atlas() -> Image.Image:
    image = Image.new("RGBA", (ATLAS, ATLAS), TRANSPARENT_RGB)
    d = D(image)

    draw_panel_sprite(d, 0, 0, SURFACE_SOFT)
    draw_panel_sprite(d, 16, 0, SURFACE)
    draw_panel_sprite(d, 32, 0, WHITE)
    draw_inset_sprite(d, 48, 0)

    draw_button_sprite(d, 0, 24, WHITE)
    draw_button_sprite(d, 18, 24, BLUE_HOVER, BORDER_STRONG)
    draw_button_sprite(d, 36, 24, BLUE_SOFT, BORDER_STRONG, selected=True)
    draw_button_sprite(d, 54, 24, DISABLED, BORDER)
    draw_button_sprite(d, 72, 24, (255, 246, 226, 255), (230, 190, 96, 255))
    draw_button_sprite(d, 90, 24, (255, 235, 240, 255), PINK)

    draw_slot_sprite(d, 0, 48, SLOT)
    draw_slot_sprite(d, 18, 48, SLOT_HOVER)
    draw_slot_sprite(d, 36, 48, SLOT_SELECTED, (128, 200, 163, 255))
    draw_slot_sprite(d, 54, 48, SLOT_LOCKED, BORDER, True)

    draw_scrollbar(d)
    draw_switch(d)
    draw_chevrons(d)

    # Dividers and 1px utility pixels. Keep alpha opaque where Java tints it.
    d.rect((0, 96, 15, 96), BORDER_SOFT)
    d.rect((0, 98, 15, 98), (120, 120, 120, 60))
    d.rect((0, 100, 0, 100), WHITE)
    d.rect((0, 102, 15, 102), BORDER_STRONG)
    d.rect((18, 96, 18, 111), BORDER_SOFT)
    d.rect((20, 96, 20, 111), WHITE)

    paste_icon(image, 0, 128, "storage")
    paste_icon(image, 32, 128, "fluid")
    paste_icon(image, 64, 128, "network")
    paste_icon(image, 96, 128, "energy")
    paste_icon(image, 128, 128, "stress")
    paste_icon(image, 160, 128, "suite")

    # Larger 32x32 nine-slice sources for panels. The old 16x16 panel tiles
    # are intentionally left in place for compatibility, but large windows and
    # cards should use these to avoid distorted edges.
    draw_panel32_sprite(d, 0, 168, SURFACE_SOFT)
    draw_panel32_sprite(d, 32, 168, SURFACE)
    draw_panel32_sprite(d, 64, 168, WHITE)
    draw_panel32_sprite(d, 96, 168, SURFACE_SUNK, inset=True)
    return image


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    draw_atlas().save(OUT)
    MCMETA.write_text('{\n  "texture": {\n    "blur": true,\n    "clamp": false\n  }\n}\n', encoding="utf-8")
    print(OUT)


if __name__ == "__main__":
    main()
