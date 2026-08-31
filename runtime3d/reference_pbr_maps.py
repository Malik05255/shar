#!/usr/bin/env python3
"""Derive deterministic zero-cost PBR maps from the approved hero reference.

The source identity remains the approved reference. Background pixels are removed with a
row-adaptive gray-background model and replaced by nearest subject colors so planar projection
cannot paint the 3D side surfaces with the studio backdrop.
"""
from __future__ import annotations

import argparse
import json
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def background_plane(rgb: np.ndarray) -> np.ndarray:
    """Estimate the neutral studio backdrop from left/right edge strips for every row."""
    h, w, _ = rgb.shape
    edge = max(8, min(32, w // 24))
    left = np.median(rgb[:, :edge, :].astype(np.float32), axis=1)
    right = np.median(rgb[:, -edge:, :].astype(np.float32), axis=1)
    x = np.linspace(0.0, 1.0, w, dtype=np.float32)[None, :, None]
    return left[:, None, :] * (1.0 - x) + right[:, None, :] * x


def center_component(mask: np.ndarray) -> np.ndarray:
    """Keep the connected foreground component nearest image center; discard backdrop noise."""
    h, w = mask.shape
    ys, xs = np.where(mask)
    if len(xs) < 500:
        raise RuntimeError("Could not isolate the hero from the reference background")
    cy, cx = h // 2, w // 2
    score = ((ys - cy) / max(h, 1)) ** 2 + ((xs - cx) / max(w, 1)) ** 2
    seed = (int(ys[int(score.argmin())]), int(xs[int(score.argmin())]))

    seen = np.zeros(mask.shape, dtype=np.uint8)
    q: deque[tuple[int, int]] = deque([seed])
    seen[seed] = 1
    while q:
        y, x = q.popleft()
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dy == 0 and dx == 0:
                    continue
                yy, xx = y + dy, x + dx
                if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not seen[yy, xx]:
                    seen[yy, xx] = 1
                    q.append((yy, xx))
    component = seen.astype(bool)
    if int(component.sum()) < 20_000:
        raise RuntimeError("Isolated hero component is unexpectedly small")
    return component


def isolate_and_edge_extend(image: Image.Image) -> tuple[Image.Image, dict]:
    """Crop the hero and replace every backdrop texel with a nearby subject texel."""
    source = image.convert("RGB")
    rgb = np.asarray(source, dtype=np.float32)
    bg = background_plane(rgb)
    lum = rgb.mean(axis=2)
    bg_lum = bg.mean(axis=2)
    chroma = rgb.max(axis=2) - rgb.min(axis=2)

    # The approved reference is a dark/navy and brown subject on a neutral gray studio backdrop.
    # Use a conservative foreground mask, then morphologically bridge fur/anti-aliased edges.
    raw = ((bg_lum - lum) > 60.0) | (chroma > 28.0)
    m = Image.fromarray((raw * 255).astype(np.uint8))
    m = m.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(3))
    component = center_component(np.asarray(m) > 127)

    # A stricter interior mask provides colors for backdrop replacement. A small dilation preserves
    # eyes, badges and anti-aliased silhouette detail without using gray backdrop pixels as fill.
    core = (((bg_lum - lum) > 70.0) | (chroma > 35.0)) & component
    core_dilated = np.asarray(
        Image.fromarray((core * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))
    ) > 127
    safe_subject = component & core_dilated

    ys, xs = np.where(component)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    padx = max(3, int((x1 - x0 + 1) * 0.020))
    pady = max(3, int((y1 - y0 + 1) * 0.012))
    x0, x1 = max(0, x0 - padx), min(source.width - 1, x1 + padx)
    y0, y1 = max(0, y0 - pady), min(source.height - 1, y1 + pady)

    crop_rgb = np.asarray(source)[y0 : y1 + 1, x0 : x1 + 1].copy()
    crop_safe = safe_subject[y0 : y1 + 1, x0 : x1 + 1]
    crop_core = core[y0 : y1 + 1, x0 : x1 + 1]
    out = crop_rgb.copy()
    h, w = crop_safe.shape
    valid_rows = np.where(crop_core.any(axis=1))[0]
    if len(valid_rows) == 0:
        raise RuntimeError("Hero core mask is empty")

    idx = np.arange(w)
    for y in range(h):
        row_core = crop_core[y]
        if not row_core.any():
            continue
        left = np.maximum.accumulate(np.where(row_core, idx, -w))
        right = np.minimum.accumulate(np.where(row_core, idx, w)[::-1])[::-1]
        nearest = np.where((idx - left) <= (right - idx), left, right)
        nearest = np.where(left < 0, right, nearest)
        nearest = np.where(right >= w, left, nearest)
        fill_x = np.where(~crop_safe[y])[0]
        out[y, fill_x] = crop_rgb[y, nearest[fill_x]]

    # Only padded top/bottom rows can lack core pixels; copy the nearest valid processed row.
    for y in range(h):
        if crop_core[y].any():
            continue
        nearest_y = int(valid_rows[np.argmin(np.abs(valid_rows - y))])
        out[y] = out[nearest_y]

    bg_samples = np.concatenate(
        [rgb[:, :8, :].reshape(-1, 3), rgb[:, -8:, :].reshape(-1, 3)], axis=0
    )
    return Image.fromarray(out, "RGB"), {
        "method": "row-adaptive-background-rejection+center-component+subject-edge-extension",
        "crop": [x0, y0, x1 + 1, y1 + 1],
        "sourceSize": [source.width, source.height],
        "componentPixels": int(component.sum()),
        "corePixels": int(core.sum()),
        "safeSubjectPixels": int(safe_subject.sum()),
        "borderMedianRgb": [round(float(x), 2) for x in np.median(bg_samples, axis=0)],
        "backgroundRemoved": True,
    }


def contain_resize(image: Image.Image, size: int) -> Image.Image:
    """Preserve aspect ratio and extend already-clean edge texels to a square texture."""
    src = image.convert("RGB")
    scale = min(size / src.width, size / src.height)
    nw, nh = max(1, int(src.width * scale)), max(1, int(src.height * scale))
    resized = src.resize((nw, nh), Image.Resampling.LANCZOS)
    arr = np.asarray(resized)
    canvas = np.empty((size, size, 3), dtype=np.uint8)
    ox, oy = (size - nw) // 2, (size - nh) // 2
    canvas[oy : oy + nh, ox : ox + nw] = arr
    if ox:
        canvas[oy : oy + nh, :ox] = arr[:, :1]
        canvas[oy : oy + nh, ox + nw :] = arr[:, -1:]
    if oy:
        canvas[:oy, :] = canvas[oy : oy + 1, :]
        canvas[oy + nh :, :] = canvas[oy + nh - 1 : oy + nh, :]
    return Image.fromarray(canvas, "RGB")


def derive_roughness(base: np.ndarray) -> np.ndarray:
    f = base.astype(np.float32) / 255.0
    lum = f[..., 0] * 0.2126 + f[..., 1] * 0.7152 + f[..., 2] * 0.0722
    mx, mn = f.max(axis=2), f.min(axis=2)
    sat = (mx - mn) / np.maximum(mx, 1e-4)
    rough = 0.80 + 0.10 * sat
    rough = np.where(lum < 0.23, 0.60 + 0.10 * sat, rough)
    rough = np.where(lum > 0.82, 0.76, rough)
    return np.clip(rough * 255.0, 0, 255).astype(np.uint8)


def derive_normal(base: np.ndarray) -> np.ndarray:
    f = base.astype(np.float32) / 255.0
    gray = f[..., 0] * 0.2126 + f[..., 1] * 0.7152 + f[..., 2] * 0.0722
    blur_img = Image.fromarray((gray * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3.2))
    detail = gray - np.asarray(blur_img, dtype=np.float32) / 255.0
    dy, dx = np.gradient(detail)
    nx, ny, nz = -dx * 6.0, -dy * 6.0, np.ones_like(dx)
    length = np.sqrt(nx * nx + ny * ny + nz * nz)
    normal = np.stack(
        [(nx / length) * 0.5 + 0.5, (ny / length) * 0.5 + 0.5, (nz / length) * 0.5 + 0.5],
        axis=2,
    )
    return np.clip(normal * 255.0, 0, 255).astype(np.uint8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    ap.add_argument("--size", type=int, default=2048)
    args = ap.parse_args()
    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Reference image missing or too small")
    if not 512 <= args.size <= 4096:
        raise SystemExit("Texture size must be between 512 and 4096")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    isolated, isolate_meta = isolate_and_edge_extend(Image.open(args.reference))
    base_img = contain_resize(isolated, args.size)
    base = np.asarray(base_img)
    rough, normal = derive_roughness(base), derive_normal(base)

    base_path = args.output_dir / "police_dog.reference_basecolor.png"
    rough_path = args.output_dir / "police_dog.reference_roughness.png"
    normal_path = args.output_dir / "police_dog.reference_normal.png"
    base_img.save(base_path, optimize=True)
    Image.fromarray(rough).save(rough_path, optimize=True)
    Image.fromarray(normal, "RGB").save(normal_path, optimize=True)

    report = {
        "freeOnly": True,
        "cloudProviderUsed": False,
        "sourceReference": args.reference.name,
        "sourceBytes": args.reference.stat().st_size,
        "textureSize": [args.size, args.size],
        "baseColorSource": "approved reference with deterministic backdrop rejection and edge extension",
        "roughnessSource": "deterministic luminance/saturation derivation",
        "normalSource": "deterministic high-frequency luminance gradients",
        "metallic": 0.0,
        "isolation": isolate_meta,
        "outputs": {
            "baseColor": base_path.name,
            "roughness": rough_path.name,
            "normal": normal_path.name,
        },
        "productionReady": False,
        "productionGate": "CLOSED",
    }
    (args.output_dir / "reference-pbr-maps.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, indent=2))
    print("REFERENCE_PBR_MAPS_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
