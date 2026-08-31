#!/usr/bin/env python3
"""Target-owned hero PBR V3: persist deterministic PNG pixels before GLB export.

V2 proved the seven semantic material regions and topology contract, but Blender 4.0 exported
all generated Image datablocks as black PNGs even though their texture/image references existed.
This entrypoint keeps the V2 semantic/material-slot fix and replaces generated Image datablocks
with real PNG files written from NumPy, loaded back into Blender, then packed.

It also grades the very dark procedural palette into a physically plausible cinematic working
range while preserving the navy uniform / brown fur / black leather separation.
"""
from __future__ import annotations

import binascii
import struct
import sys
import tempfile
import zlib
from pathlib import Path

import bpy
import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_author_target_pbr_v2 as v2

authored = v2.authored
_original_texture_fields = authored.texture_fields


def graded_texture_fields(kind: str, size: int):
    rgb, height, roughness = _original_texture_fields(kind, size)
    # The source palette was intentionally near-black; these gains move it into a visible
    # cinematic range without turning black duty gear grey or the navy uniform electric blue.
    gains = {
        "fur": np.array([2.25, 2.20, 2.05], dtype=np.float32),
        "muzzle": np.array([2.8, 2.6, 2.4], dtype=np.float32),
        "eye": np.array([4.4, 4.0, 3.6], dtype=np.float32),
        "uniform": np.array([3.0, 2.7, 2.55], dtype=np.float32),
        "belt": np.array([2.7, 2.7, 2.7], dtype=np.float32),
        "glove": np.array([2.55, 2.55, 2.55], dtype=np.float32),
        "boot": np.array([2.65, 2.65, 2.65], dtype=np.float32),
    }
    floor = {
        "fur": np.array([0.055, 0.020, 0.006], dtype=np.float32),
        "muzzle": np.array([0.035, 0.022, 0.015], dtype=np.float32),
        "eye": np.array([0.018, 0.010, 0.004], dtype=np.float32),
        "uniform": np.array([0.018, 0.050, 0.115], dtype=np.float32),
        "belt": np.array([0.018, 0.021, 0.025], dtype=np.float32),
        "glove": np.array([0.022, 0.026, 0.030], dtype=np.float32),
        "boot": np.array([0.018, 0.022, 0.028], dtype=np.float32),
    }[kind]
    rgb = np.maximum(rgb * gains[kind][None, None, :], floor[None, None, :])
    rgb = np.clip(rgb, 0.002, 0.82).astype(np.float32)
    return rgb, height, roughness


def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)


def write_rgb_png(path: Path, rgb: np.ndarray) -> None:
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise RuntimeError(f"Expected HxWx3 RGB array, got {rgb.shape}")
    h, w, _ = rgb.shape
    pixels = np.clip(np.rint(rgb * 255.0), 0, 255).astype(np.uint8)
    # PNG filter method 0 for every scanline. This is deterministic and needs only stdlib zlib.
    raw = b"".join(b"\x00" + pixels[row].tobytes() for row in range(h))
    signature = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    data = signature + _png_chunk(b"IHDR", ihdr) + _png_chunk(b"IDAT", zlib.compress(raw, 9)) + _png_chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    if path.stat().st_size < 256:
        raise RuntimeError(f"Generated PNG unexpectedly small: {path}")


_IMAGE_DIR = Path(tempfile.mkdtemp(prefix="alshorti-target-pbr-v3-"))


def persistent_make_image(name: str, rgb: np.ndarray, *, non_color: bool) -> bpy.types.Image:
    # Sanitized deterministic filenames keep Blender's packed source resolvable throughout export.
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in name)
    path = _IMAGE_DIR / f"{safe}.png"
    write_rgb_png(path, rgb)
    image = bpy.data.images.load(str(path), check_existing=False)
    image.name = name
    try:
        image.colorspace_settings.name = "Non-Color" if non_color else "sRGB"
    except Exception:
        pass
    image.pack()
    # Fail immediately if Blender did not retain the source bytes.
    if not image.packed_file and not image.packed_files:
        raise RuntimeError(f"Failed to pack generated image {name}")
    return image


authored.texture_fields = graded_texture_fields
authored.make_image = persistent_make_image

if __name__ == "__main__":
    raise SystemExit(authored.base.main())
