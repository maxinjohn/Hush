#!/usr/bin/env python3
"""Regenerate Hush launcher and branding PNGs from the brand sheet."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SHEET = ROOT / "branding" / "hush_brand_sheet_source.png"
OUT_BRAND = ROOT / "branding" / "hush_correct_logo_assets"
APP_RES = ROOT / "app" / "src" / "main" / "res"

BG_COLOR = (18, 14, 28, 255)
# Adaptive-icon safe zone is the center 66dp circle inside a 108dp canvas (~61%).
FOREGROUND_SCALE = 0.30
LAUNCHER_ICON_SCALE = 0.48
LOGO_MARK_SCALE = 0.54
IN_APP_ICON_SCALE = 0.50
ICON_MARK_PAD = 14
ICON_MARK_TOP_BIAS = 6


def trim_alpha(im: Image.Image, pad: int = 8) -> Image.Image:
    bbox = im.getbbox()
    if not bbox:
        return im
    left, top, right, bottom = bbox
    left = max(0, left - pad)
    top = max(0, top - pad)
    right = min(im.width, right + pad)
    bottom = min(im.height, bottom + pad)
    return im.crop((left, top, right, bottom))


def extract_icon_mark(sheet: Image.Image) -> Image.Image:
    icon = sheet.crop((34, 776, 138, 904)).convert("RGBA")
    icon = trim_alpha(icon, 8)
    pixels = icon.load()
    for y in range(icon.height):
        for x in range(icon.width):
            red, green, blue, alpha = pixels[x, y]
            if red < 28 and green < 28 and blue < 40:
                pixels[x, y] = (red, green, blue, 0)
    icon = trim_alpha(icon, 6)
    padded = Image.new(
        "RGBA",
        (icon.width + ICON_MARK_PAD * 2, icon.height + ICON_MARK_PAD * 2 + ICON_MARK_TOP_BIAS),
        (0, 0, 0, 0),
    )
    padded.paste(icon, (ICON_MARK_PAD, ICON_MARK_PAD + ICON_MARK_TOP_BIAS))
    return padded


def rounded_mask(size: int, radius_ratio: float = 0.22) -> Image.Image:
    radius = int(size * radius_ratio)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def paste_centered(canvas: Image.Image, icon: Image.Image, scale: float) -> None:
    width, height = icon.size
    target = int(canvas.width * scale)
    ratio = target / max(width, height)
    resized = icon.resize((max(1, int(width * ratio)), max(1, int(height * ratio))), Image.Resampling.LANCZOS)
    x = (canvas.width - resized.width) // 2
    y = (canvas.height - resized.height) // 2
    canvas.alpha_composite(resized, (x, y))


def compose_launcher(size: int, icon: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), BG_COLOR)
    paste_centered(canvas, icon, LAUNCHER_ICON_SCALE)
    mask = rounded_mask(size)
    output = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    output.paste(canvas, (0, 0), mask)
    return output


def compose_foreground(size: int, icon: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    paste_centered(canvas, icon, FOREGROUND_SCALE)
    return canvas


def make_transparent_background(im: Image.Image, pad: int = 10) -> Image.Image:
    im = im.convert("RGBA")
    pixels = im.load()
    for y in range(im.height):
        for x in range(im.width):
            red, green, blue, alpha = pixels[x, y]
            if red < 30 and green < 30 and blue < 45:
                pixels[x, y] = (red, green, blue, 0)
    return trim_alpha(im, pad)


def export_logo_mark(icon: Image.Image, size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    paste_centered(canvas, icon, LOGO_MARK_SCALE)
    return canvas


def compose_in_app_icon(size: int, icon: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), BG_COLOR)
    paste_centered(canvas, icon, IN_APP_ICON_SCALE)
    mask = rounded_mask(size)
    output = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    output.paste(canvas, (0, 0), mask)
    return output


def compose_background(size: int) -> Image.Image:
    # Adaptive-icon backgrounds must be full-bleed; the launcher applies the mask.
    return Image.new("RGBA", (size, size), BG_COLOR)


def main() -> None:
    sheet = Image.open(SHEET).convert("RGBA")
    OUT_BRAND.mkdir(parents=True, exist_ok=True)

    wordmark = make_transparent_background(sheet.crop((30, 768, 348, 912)), 10)
    wordmark.save(OUT_BRAND / "hush_wordmark_tagline.png")

    icon = extract_icon_mark(sheet)
    logo_mark = export_logo_mark(icon, 256)
    logo_mark.save(OUT_BRAND / "hush_logo_mark.png")

    for size, name in ((1024, "hush_icon_1024.png"), (512, "hush_icon_512.png"), (256, "hush_icon_256.png")):
        compose_launcher(size, icon).save(OUT_BRAND / name)

    compose_foreground(432, icon).save(OUT_BRAND / "ic_launcher_foreground.png")
    compose_background(432).save(OUT_BRAND / "ic_launcher_background.png")

    launcher_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    adaptive_sizes = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }

    for folder, size in launcher_sizes.items():
        out_dir = OUT_BRAND / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        launcher = compose_launcher(size, icon)
        launcher.save(out_dir / "ic_launcher.png")
        launcher.save(out_dir / "ic_launcher_round.png")

    compose_in_app_icon(256, icon).save(APP_RES / "drawable-nodpi" / "hush_app_icon.png")
    logo_mark.save(APP_RES / "drawable-nodpi" / "hush_logo_mark.png")
    wordmark.save(APP_RES / "drawable-nodpi" / "hush_wordmark_tagline.png")

    for folder, size in launcher_sizes.items():
        launcher = compose_launcher(size, icon)
        target = APP_RES / folder
        launcher.save(target / "ic_launcher.png")
        launcher.save(target / "ic_launcher_round.png")

    for folder, size in adaptive_sizes.items():
        target = APP_RES / folder
        foreground = compose_foreground(size, icon)
        foreground.save(target / "ic_launcher_foreground.png")
        foreground.save(target / "ic_launcher_monochrome.png")
        compose_background(size).save(target / "ic_launcher_background.png")


if __name__ == "__main__":
    main()
