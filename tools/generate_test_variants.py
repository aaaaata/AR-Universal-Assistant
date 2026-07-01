from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageOps


def save(image: Image.Image, output_dir: Path, name: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    image.save(output_dir / name, quality=95)


def crop_center(image: Image.Image, scale: float) -> Image.Image:
    width, height = image.size
    crop_w = int(width * scale)
    crop_h = int(height * scale)
    left = (width - crop_w) // 2
    top = (height - crop_h) // 2
    return image.crop((left, top, left + crop_w, top + crop_h)).resize((width, height), Image.Resampling.LANCZOS)


def add_border(image: Image.Image, border: int, color: tuple[int, int, int]) -> Image.Image:
    return ImageOps.expand(image, border=border, fill=color).resize(image.size, Image.Resampling.LANCZOS)


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate test image variants for AR Mahjong recognition.")
    parser.add_argument("input", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()

    base = Image.open(args.input).convert("RGB")

    variants: list[tuple[str, Image.Image]] = []
    variants.append(("sample_original.jpg", base))
    variants.append(("sample_bright.jpg", ImageEnhance.Brightness(base).enhance(1.18)))
    variants.append(("sample_dark.jpg", ImageEnhance.Brightness(base).enhance(0.78)))
    variants.append(("sample_high_contrast.jpg", ImageEnhance.Contrast(base).enhance(1.28)))
    variants.append(("sample_low_contrast.jpg", ImageEnhance.Contrast(base).enhance(0.8)))
    variants.append(("sample_warm.jpg", ImageEnhance.Color(base).enhance(1.18)))
    variants.append(("sample_desaturated.jpg", ImageEnhance.Color(base).enhance(0.72)))
    variants.append(("sample_soft_blur.jpg", base.filter(ImageFilter.GaussianBlur(radius=1.2))))
    variants.append(("sample_sharp.jpg", ImageEnhance.Sharpness(base).enhance(1.6)))
    variants.append(("sample_zoomed.jpg", crop_center(base, 0.88)))
    variants.append(("sample_zoomed_dark.jpg", ImageEnhance.Brightness(crop_center(base, 0.9)).enhance(0.82)))
    variants.append(("sample_screen_like.jpg", add_border(base, border=36, color=(12, 12, 18))))
    variants.append(("sample_screen_glare.jpg", ImageEnhance.Brightness(add_border(base, 54, (250, 250, 250))).enhance(1.06)))

    grayscale = ImageOps.grayscale(base).convert("RGB")
    variants.append(("sample_grayscale.jpg", grayscale))

    for name, image in variants:
        save(image, args.output_dir, name)


if __name__ == "__main__":
    main()
