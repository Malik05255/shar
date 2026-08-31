#!/usr/bin/env python3
"""Derive deterministic candidate PBR maps from the approved hero reference.

This is a zero-cost fallback when cloud texture generators are unavailable. It does not invent a
replacement identity: base color comes from the exact approved reference, while roughness and a
micro-normal candidate are derived deterministically from the same pixels. Production acceptance
still requires multi-view/fur/facial/device review.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def border_background(rgb: np.ndarray) -> np.ndarray:
    h, w, _ = rgb.shape
    samples = np.concatenate([
        rgb[0, ::max(1, w // 120)], :], rgb[-1, ::max(1, w // 120)], :],
        rgb[::max(1, h // 160), 0, :], rgb[::max(1, h // 160), -1, :],
    ], axis=0)
    return np.median(samples.astype(np.float32), axis=0)


def foreground_crop(image: Image.Image) -> tuple[Image.Image, dict]:
    rgb = np.asarray(image.convert("RGB"), dtype=np.float32)
    bg = border_background(rgb)
    dist = np.linalg.norm(rgb - bg[None, None, :], axis=2)
    mask = dist > 24.0
    # Close pinholes and expand the silhouette slightly so edge texels do not sample the backdrop.
    m = Image.fromarray((mask * 255).astype(np.uint8), "L")
    m = m.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(5))
    mask = np.asarray(m) > 20
    ys, xs = np.where(mask)
    if len(xs) < 500:
        raise RuntimeError("Could not isolate the hero from the reference background")
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    padx = max(3, int((x1 - x0 + 1) * 0.035))
    pady = max(3, int((y1 - y0 + 1) * 0.025))
    x0 = max(0, x0 - padx); x1 = min(image.width - 1, x1 + padx)
    y0 = max(0, y0 - pady); y1 = min(image.height - 1, y1 + pady)
    return image.crop((x0, y0, x1 + 1, y1 + 1)).convert("RGB"), {
        "backgroundEstimateRgb": [round(float(x), 2) for x in bg],
        "crop": [int(x0), int(y0), int(x1 + 1), int(y1 + 1)],
        "foregroundPixels": int(mask.sum()),
        "sourceSize": [image.width, image.height],
    }


def contain_resize(image: Image.Image, size: int) -> Image.Image:
    # Preserve identity proportions. Extend edge pixels instead of adding a white/black border.
    src = image.convert("RGB")
    scale = min(size / src.width, size / src.height)
    nw, nh = max(1, int(src.width * scale)), max(1, int(src.height * scale))
    resized = src.resize((nw, nh), Image.Resampling.LANCZOS)
    arr = np.asarray(resized)
    canvas = np.empty((size, size, 3), dtype=np.uint8)
    ox, oy = (size - nw) // 2, (size - nh) // 2
    canvas[oy:oy+nh, ox:ox+nw] = arr
    # Edge-extend horizontally and vertically to avoid background seams on the mesh.
    if ox:
        canvas[oy:oy+nh, :ox] = arr[:, :1]
        canvas[oy:oy+nh, ox+nw:] = arr[:, -1:]
    if oy:
        canvas[:oy, :] = canvas[oy:oy+1, :]
        canvas[oy+nh:, :] = canvas[oy+nh-1:oy+nh, :]
    return Image.fromarray(canvas, "RGB")


def derive_roughness(base: np.ndarray) -> np.ndarray:
    f = base.astype(np.float32) / 255.0
    lum = f[..., 0] * 0.2126 + f[..., 1] * 0.7152 + f[..., 2] * 0.0722
    mx, mn = f.max(axis=2), f.min(axis=2)
    sat = (mx - mn) / np.maximum(mx, 1e-4)
    # Dark uniform/leather gets a little tighter response; colored/tan fur stays rougher.
    rough = 0.80 + 0.10 * sat
    rough = np.where(lum < 0.23, 0.60 + 0.10 * sat, rough)
    rough = np.where(lum > 0.82, 0.76, rough)
    return np.clip(rough * 255.0, 0, 255).astype(np.uint8)


def derive_normal(base: np.ndarray) -> np.ndarray:
    f = base.astype(np.float32) / 255.0
    gray = f[..., 0] * 0.2126 + f[..., 1] * 0.7152 + f[..., 2] * 0.0722
    # High-frequency detail only: avoid turning broad lighting gradients into fake large dents.
    blur = np.asarray(Image.fromarray((gray * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(3.2)), dtype=np.float32) / 255.0
    detail = gray - blur
    dy, dx = np.gradient(detail)
    strength = 6.0
    nx, ny = -dx * strength, -dy * strength
    nz = np.ones_like(nx)
    length = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / length, ny / length, nz / length
    normal = np.stack([(nx * 0.5 + 0.5), (ny * 0.5 + 0.5), (nz * 0.5 + 0.5)], axis=2)
    return np.clip(normal * 255.0, 0, 255).astype(np.uint8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    ap.add_argument("--size", type=int, default=2048)
    args = ap.parse_args()
    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Reference image missing or too small")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    source = Image.open(args.reference)
    cropped, crop_meta = foreground_crop(source)
    base_img = contain_resize(cropped, args.size)
    base = np.asarray(base_img)
    rough = derive_roughness(base)
    normal = derive_normal(base)

    base_path = args.output_dir / "police_dog.reference_basecolor.png"
    rough_path = args.output_dir / "police_dog.reference_roughness.png"
    normal_path = args.output_dir / "police_dog.reference_normal.png"
    base_img.save(base_path, optimize=True)
    Image.fromarray(rough, "L").save(rough_path, optimize=True)
    Image.fromarray(normal, "RGB").save(normal_path, optimize=True)

    report = {
        "freeOnly": True,
        "cloudProviderUsed": False,
        "sourceReference": args.reference.name,
        "sourceBytes": args.reference.stat().st_size,
        "textureSize": [args.size, args.size],
        "baseColorSource": "exact approved reference crop with edge extension",
        "roughnessSource": "deterministic luminance/saturation derivation",
        "normalSource": "deterministic high-frequency luminance gradients",
        "metallic": 0.0,
        "crop": crop_meta,
        "outputs": {
            "baseColor": base_path.name,
            "roughness": rough_path.name,
            "normal": normal_path.name,
        },
        "productionReady": False,
        "productionGate": "CLOSED",
    }
    (args.output_dir / "reference-pbr-maps.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("REFERENCE_PBR_MAPS_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
