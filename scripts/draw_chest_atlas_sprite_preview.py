from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ATLAS = ROOT / "src" / "main" / "resources" / "assets" / "pockethomestead" / "textures" / "gui" / "chest.png"
OUT = ROOT / "docs" / "client-ui" / "previews" / "chest-atlas-sprites-v1.png"
FONT_PATH = ROOT / "src" / "main" / "resources" / "assets" / "pockethomestead" / "font" / "notosanssc-regular.ttf"

LOGICAL = 256
DISPLAY_SCALE = 4


def font(size: int):
    return ImageFont.truetype(str(FONT_PATH), size) if FONT_PATH.exists() else ImageFont.load_default()


def crop(atlas: Image.Image, u: int, v: int, w: int, h: int) -> Image.Image:
    s = atlas.width // LOGICAL
    return atlas.crop((u * s, v * s, (u + w) * s, (v + h) * s))


def paste_sprite(canvas: Image.Image, atlas: Image.Image, xy: tuple[int, int], box: tuple[int, int, int, int], label: str) -> None:
    u, v, w, h = box
    sprite = crop(atlas, u, v, w, h)
    sprite = sprite.resize((w * DISPLAY_SCALE, h * DISPLAY_SCALE), Image.Resampling.LANCZOS)
    canvas.alpha_composite(sprite, xy)
    d = ImageDraw.Draw(canvas)
    d.text((xy[0], xy[1] + h * DISPLAY_SCALE + 6), label, fill=(88, 108, 128, 255), font=font(13))


def main() -> None:
    atlas = Image.open(ATLAS).convert("RGBA")
    canvas = Image.new("RGBA", (980, 620), (244, 250, 255, 255))
    d = ImageDraw.Draw(canvas)
    d.text((28, 24), "Chest Atlas Sprites v1 - direct crop from generated PNG", fill=(48, 63, 80, 255), font=font(22))

    x, y = 32, 75
    for label, box in [
        ("panel", (0, 168, 32, 32)),
        ("panel light", (32, 168, 32, 32)),
        ("panel white", (64, 168, 32, 32)),
        ("inset", (48, 0, 16, 16)),
    ]:
        paste_sprite(canvas, atlas, (x, y), box, label)
        x += 175

    x, y = 32, 250
    for label, box in [
        ("normal", (0, 24, 18, 18)),
        ("hover", (18, 24, 18, 18)),
        ("selected", (36, 24, 18, 18)),
        ("disabled", (54, 24, 18, 18)),
        ("gold", (72, 24, 18, 18)),
        ("danger", (90, 24, 18, 18)),
    ]:
        paste_sprite(canvas, atlas, (x, y), box, label)
        x += 105

    x, y = 32, 360
    for label, box in [
        ("slot", (0, 48, 18, 18)),
        ("slot hover", (18, 48, 18, 18)),
        ("slot sel", (36, 48, 18, 18)),
        ("slot lock", (54, 48, 18, 18)),
        ("scroll", (0, 72, 7, 16)),
        ("thumb", (8, 72, 7, 16)),
        ("switch", (24, 72, 34, 14)),
    ]:
        paste_sprite(canvas, atlas, (x, y), box, label)
        x += 95 if box[2] < 20 else 155

    x, y = 32, 480
    d.text((x, y - 34), "Upgrade glyphs", fill=(48, 63, 80, 255), font=font(18))
    for label, box in [
        ("storage", (0, 128, 32, 32)),
        ("fluid", (32, 128, 32, 32)),
        ("network", (64, 128, 32, 32)),
        ("energy", (96, 128, 32, 32)),
        ("stress", (128, 128, 32, 32)),
    ]:
        paste_sprite(canvas, atlas, (x, y), box, label)
        x += 145

    OUT.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
