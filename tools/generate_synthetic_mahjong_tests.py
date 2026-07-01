from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont


SCENARIOS = [
    {
        "name": "scenario_01_balanced_wait",
        "label": "均衡手牌",
        "melded_tiles": ["3m", "4m", "5m", "7p", "8p", "9p"],
        "user_hand": ["2s", "3s", "4s", "5s", "6s", "7s", "8s", "2m"],
    },
    {
        "name": "scenario_02_after_draw",
        "label": "摸牌后",
        "melded_tiles": ["3m", "4m", "5m", "7p", "8p", "9p"],
        "user_hand": ["2s", "3s", "4s", "5s", "6s", "7s", "8s", "2m", "9s"],
    },
    {
        "name": "scenario_03_pin_focus",
        "label": "筒子偏重",
        "melded_tiles": ["2m", "3m", "4m", "6s", "7s", "8s"],
        "user_hand": ["1p", "2p", "3p", "4p", "5p", "6p", "7p", "9p"],
    },
    {
        "name": "scenario_04_honor_mix",
        "label": "字牌混合",
        "melded_tiles": ["1z", "1z", "1z", "7z", "7z", "7z"],
        "user_hand": ["2m", "3m", "4m", "5m", "6m", "6m", "5z", "5z"],
    },
    {
        "name": "scenario_05_sou_flush",
        "label": "索子清一色倾向",
        "melded_tiles": ["2s", "3s", "4s", "6s", "7s", "8s"],
        "user_hand": ["1s", "1s", "4s", "5s", "5s", "7s", "8s", "9s"],
    },
    {
        "name": "scenario_06_pair_choice",
        "label": "对子取舍",
        "melded_tiles": ["4p", "5p", "6p", "2m", "3m", "4m"],
        "user_hand": ["1s", "2s", "3s", "5s", "6s", "7s", "9m", "9m"],
    },
]


RAW_TILE_NAME_MAP = {
    **{f"{i}m": f"Man{i}.png" for i in range(1, 10)},
    **{f"{i}s": f"Sou{i}.png" for i in range(1, 10)},
    **{f"{i}p": f"Pin{i}.png" for i in range(1, 10)},
    "1z": "Ton.png",
    "2z": "Nan.png",
    "3z": "Shaa.png",
    "4z": "Pei.png",
    "5z": "Haku.png",
    "6z": "Hatsu.png",
    "7z": "Chun.png",
}


def build_tile_image(source_dir: Path, tile_code: str, size: tuple[int, int]) -> Image.Image:
    front = Image.open(source_dir / "Front.png").convert("RGBA")
    face = Image.open(source_dir / RAW_TILE_NAME_MAP[tile_code]).convert("RGBA")
    if face.size != front.size:
        face = face.resize(front.size, Image.Resampling.LANCZOS)
    tile = Image.alpha_composite(front, face)
    return tile.resize(size, Image.Resampling.LANCZOS)


def paste_tile(
    canvas: Image.Image,
    tile: Image.Image,
    position: tuple[int, int],
    rng: random.Random,
    tilt_range: float,
) -> None:
    angle = rng.uniform(-tilt_range, tilt_range)
    rotated = tile.rotate(angle, resample=Image.Resampling.BICUBIC, expand=True)
    shadow = Image.new("RGBA", rotated.size, (0, 0, 0, 0))
    shadow_draw = Image.new("RGBA", rotated.size, (0, 0, 0, 70))
    shadow.alpha_composite(shadow_draw)
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius=6))
    shadow_pos = (position[0] + 6, position[1] + 8)
    canvas.alpha_composite(shadow, shadow_pos)
    canvas.alpha_composite(rotated, position)


def draw_row(
    canvas: Image.Image,
    source_dir: Path,
    tiles: list[str],
    start: tuple[int, int],
    tile_size: tuple[int, int],
    gap: int,
    rng: random.Random,
    tilt_range: float,
) -> None:
    x, y = start
    for tile_code in tiles:
        tile = build_tile_image(source_dir, tile_code, tile_size)
        paste_tile(canvas, tile, (x, y), rng, tilt_range)
        x += tile_size[0] + gap


def draw_label(draw: ImageDraw.ImageDraw, text: str, x: int, y: int, color: tuple[int, int, int]) -> None:
    font = ImageFont.load_default()
    draw.text((x, y), text, fill=color, font=font)


def create_scene(
    source_dir: Path,
    scenario: dict,
    output_path: Path,
    rng: random.Random,
) -> None:
    width, height = 1600, 900
    canvas = Image.new("RGBA", (width, height), (16, 63, 46, 255))

    vignette = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    vignette_draw = ImageDraw.Draw(vignette)
    vignette_draw.rectangle((0, 0, width, height), fill=(0, 0, 0, 0))
    vignette = vignette.filter(ImageFilter.GaussianBlur(radius=22))
    canvas.alpha_composite(vignette)

    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((60, 70, width - 60, height - 70), radius=32, outline=(33, 95, 72), width=4)

    draw_label(draw, scenario["label"], 90, 90, (224, 246, 233))
    draw_label(draw, "melded", 90, 170, (173, 220, 200))
    draw_label(draw, "hand", 90, 480, (173, 220, 200))

    draw_row(
        canvas,
        source_dir,
        scenario["melded_tiles"],
        start=(180, 210),
        tile_size=(120, 164),
        gap=14,
        rng=rng,
        tilt_range=2.5,
    )
    draw_row(
        canvas,
        source_dir,
        scenario["user_hand"],
        start=(150, 540),
        tile_size=(138, 188),
        gap=10,
        rng=rng,
        tilt_range=1.8,
    )

    footer = "synthetic test | open on phone or monitor and capture with glasses"
    draw_label(draw, footer, 90, height - 110, (182, 229, 211))

    canvas.convert("RGB").save(output_path, quality=95)


def make_capture_variant(
    image: Image.Image,
    rng: random.Random,
    variant: str,
) -> Image.Image:
    base = image.convert("RGB")

    if variant == "flat":
        return base

    if variant == "screen":
        framed = Image.new("RGB", (base.width + 180, base.height + 180), (18, 18, 24))
        framed.paste(base, (90, 90))
        return ImageEnhance.Brightness(framed).enhance(1.03)

    framed = Image.new("RGB", (base.width + 220, base.height + 220), (14, 14, 18))
    framed.paste(base, (110, 110))
    w, h = framed.size

    if variant == "perspective":
        quad = [
            (50, 35),
            (w - 10, 80),
            (w - 60, h - 25),
            (25, h - 70),
        ]
    else:
        quad = [
            (70, 45),
            (w - 40, 110),
            (w - 85, h - 20),
            (30, h - 90),
        ]
    transformed = framed.transform(
        (w, h),
        Image.Transform.QUAD,
        data=(
            quad[0][0], quad[0][1],
            quad[1][0], quad[1][1],
            quad[2][0], quad[2][1],
            quad[3][0], quad[3][1],
        ),
        resample=Image.Resampling.BICUBIC,
    )

    if variant == "perspective":
        return transformed.filter(ImageFilter.GaussianBlur(radius=0.8))

    glare = Image.new("RGBA", transformed.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(glare)
    draw.polygon(
        [
            (w * 0.12, h * 0.05),
            (w * 0.42, h * 0.02),
            (w * 0.7, h * 0.6),
            (w * 0.4, h * 0.67),
        ],
        fill=(255, 255, 255, 52),
    )
    composited = Image.alpha_composite(transformed.convert("RGBA"), glare).convert("RGB")
    composited = ImageEnhance.Contrast(composited).enhance(0.95)
    composited = ImageEnhance.Brightness(composited).enhance(1.05)
    return composited.filter(ImageFilter.GaussianBlur(radius=1.1))


def build_contact_sheet(images: list[Path], output_path: Path) -> None:
    thumbs: list[Image.Image] = []
    for image_path in images:
        image = Image.open(image_path).convert("RGB")
        image.thumbnail((440, 248), Image.Resampling.LANCZOS)
        thumbs.append(image)

    cols = 2
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (980, rows * 290 + 40), (12, 12, 12))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()

    for index, (thumb, path) in enumerate(zip(thumbs, images)):
        row = index // cols
        col = index % cols
        x = 30 + col * 470
        y = 30 + row * 290
        sheet.paste(thumb, (x, y))
        draw.text((x, y + 255), path.stem, fill=(240, 240, 240), font=font)

    sheet.save(output_path, quality=95)


def build_sequence_sheet(sequence_paths: list[Path], output_path: Path) -> None:
    items = []
    for path in sequence_paths:
        image = Image.open(path).convert("RGB")
        image.thumbnail((480, 270), Image.Resampling.LANCZOS)
        items.append((path.stem, image))

    width = 80 + len(items) * 520
    sheet = Image.new("RGB", (width, 360), (10, 10, 10))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()

    for index, (label, image) in enumerate(items):
        x = 40 + index * 520
        y = 35
        sheet.paste(image, (x, y))
        draw.text((x, 320), label, fill=(245, 245, 245), font=font)
        if index < len(items) - 1:
            draw.text((x + 485, 150), "->", fill=(120, 220, 200), font=font)

    sheet.save(output_path, quality=95)


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate synthetic mahjong test images with different tile setups.")
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=Path("temp_mahjong_tiles/Export/Regular"),
        help="Directory containing Front.png and tile PNGs.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("server/synthetic_tests"),
        help="Output directory for generated images.",
    )
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)
    generated_paths: list[Path] = []
    capture_variant_paths: list[Path] = []

    for scenario in SCENARIOS:
        output_path = args.output_dir / f"{scenario['name']}.jpg"
        create_scene(args.source_dir, scenario, output_path, rng)
        generated_paths.append(output_path)

        base_image = Image.open(output_path)
        for variant_name in ("screen", "perspective", "glare"):
            variant_path = args.output_dir / f"{scenario['name']}_{variant_name}.jpg"
            make_capture_variant(base_image, rng, variant_name).save(variant_path, quality=95)
            capture_variant_paths.append(variant_path)

    metadata = args.output_dir / "metadata.json"
    metadata.write_text(
        json.dumps(
            {
                "seed": args.seed,
                "scenarios": SCENARIOS,
                "capture_variants": ["screen", "perspective", "glare"],
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    build_contact_sheet(generated_paths, args.output_dir / "contact_sheet.jpg")
    build_contact_sheet(capture_variant_paths[:6], args.output_dir / "capture_variants_sheet.jpg")
    build_sequence_sheet(
        [
            args.output_dir / "scenario_01_balanced_wait.jpg",
            args.output_dir / "scenario_02_after_draw.jpg",
            args.output_dir / "scenario_06_pair_choice.jpg",
        ],
        args.output_dir / "sequence_sheet.jpg",
    )


if __name__ == "__main__":
    main()
