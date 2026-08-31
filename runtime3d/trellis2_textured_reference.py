#!/usr/bin/env python3
"""Generate a free geometry-aware PBR GLB with the official Microsoft TRELLIS.2 ZeroGPU Space.

The generated model is a visual transfer source only. It never replaces the animated
hero automatically, and it never calls a paid provider.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inspect_glb import read_glb_json


def sanitize(value: str) -> str:
    name = re.sub(r"\W", "_", value.strip())
    return "_" + name if name and name[0].isdigit() else name


def endpoint(client: Any, needle: str) -> tuple[str, dict[str, Any]]:
    info = client.view_api(print_info=False, return_format="dict") or {}
    named = info.get("named_endpoints") or {}
    name = next((n for n in named if needle in n), None)
    if not name:
        raise RuntimeError(f"TRELLIS.2 exposes no {needle}; endpoints={list(named)}")
    return name, named[name]


def build_foreground(source: Path, output: Path) -> dict[str, Any]:
    """Reproduce the Space's alpha-aware preprocessing locally so no rembg call is needed."""
    import numpy as np
    from PIL import Image, ImageFilter
    from reference_pbr_maps import background_plane, center_component

    image = Image.open(source).convert("RGB")
    rgb = np.asarray(image, dtype=np.float32)
    bg = background_plane(rgb)
    lum, bg_lum = rgb.mean(axis=2), bg.mean(axis=2)
    chroma = rgb.max(axis=2) - rgb.min(axis=2)
    raw = ((bg_lum - lum) > 60.0) | (chroma > 28.0)
    mask_img = Image.fromarray((raw * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(3))
    component = center_component(np.asarray(mask_img) > 127)
    ys, xs = np.where(component)
    box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)

    crop = np.asarray(image)[box[1]:box[3], box[0]:box[2]].astype(np.float32)
    alpha = component[box[1]:box[3], box[0]:box[2]].astype(np.float32)[..., None]
    # TRELLIS.2's own preprocess multiplies RGB by alpha and drops alpha before image_to_3d.
    processed = np.clip(crop * alpha, 0, 255).astype(np.uint8)
    out = Image.fromarray(processed, "RGB")
    if max(out.size) > 1024:
        scale = 1024.0 / max(out.size)
        out = out.resize((max(1, int(out.width * scale)), max(1, int(out.height * scale))), Image.Resampling.LANCZOS)
    output.parent.mkdir(parents=True, exist_ok=True)
    out.save(output, "PNG", optimize=True)
    return {"crop": list(box), "foregroundPixels": int(component.sum()), "preparedSize": list(out.size), "bytes": output.stat().st_size}


def kwargs_from_schema(info: dict[str, Any], values: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for parameter in info.get("parameters") or []:
        raw = str(parameter.get("parameter_name") or parameter.get("label") or "").strip()
        if not raw:
            continue
        name = sanitize(raw)
        key = name.lower()
        if key in values:
            result[name] = values[key]
        elif parameter.get("parameter_has_default"):
            continue
        else:
            result[name] = None
    return result


def collect_paths(value: Any, suffix: str, found: list[Path]) -> None:
    if isinstance(value, Path) and value.suffix.lower() == suffix and value.exists():
        found.append(value)
    elif isinstance(value, str):
        p = Path(value)
        if p.suffix.lower() == suffix and p.exists():
            found.append(p)
    elif isinstance(value, dict):
        for nested in value.values():
            collect_paths(nested, suffix, found)
    elif isinstance(value, (tuple, list)):
        for nested in value:
            collect_paths(nested, suffix, found)


def validate_glb(path: Path) -> dict[str, Any]:
    doc = read_glb_json(path)
    meshes = doc.get("meshes") or []
    materials = doc.get("materials") or []
    textures = doc.get("textures") or []
    images = doc.get("images") or []
    accessors = doc.get("accessors") or []
    vertices = 0
    channels: set[str] = set()
    for mesh in meshes:
        for primitive in mesh.get("primitives") or []:
            pos = (primitive.get("attributes") or {}).get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertices += int(accessors[pos].get("count") or 0)
    for material in materials:
        pbr = material.get("pbrMetallicRoughness") or {}
        if pbr.get("baseColorTexture") is not None:
            channels.add("baseColorTexture")
        if pbr.get("metallicRoughnessTexture") is not None:
            channels.add("metallicRoughnessTexture")
        if material.get("normalTexture") is not None:
            channels.add("normalTexture")
    if not meshes or vertices < 1000 or not materials or not textures or not images or "baseColorTexture" not in channels:
        raise RuntimeError(
            f"TRELLIS.2 GLB incomplete meshes={len(meshes)} vertices={vertices} materials={len(materials)} "
            f"textures={len(textures)} images={len(images)} channels={sorted(channels)}"
        )
    return {
        "meshes": len(meshes), "vertices": vertices, "materials": len(materials),
        "textures": len(textures), "images": len(images), "pbrChannels": sorted(channels),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    args = ap.parse_args()
    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Approved hero reference is missing/small")

    from gradio_client import Client, handle_file
    token = os.environ.get("HF_TOKEN", "").strip() or None
    client_kwargs: dict[str, Any] = {"verbose": False}
    if token:
        client_kwargs["hf_token"] = token
    client = Client("microsoft/TRELLIS.2", **client_kwargs)

    prepared = args.output.parent / "trellis2-reference-preprocessed.png"
    prep_meta = build_foreground(args.reference, prepared)

    generate_name, generate_info = endpoint(client, "image_to_3d")
    generate_values = {
        "image": handle_file(str(prepared)),
        "image_prompt": handle_file(str(prepared)),
        "seed": 1234,
        "resolution": "512",
        "ss_guidance_strength": 7.5,
        "ss_guidance_rescale": 0.7,
        "ss_sampling_steps": 12,
        "ss_rescale_t": 5.0,
        "shape_slat_guidance_strength": 7.5,
        "shape_slat_guidance_rescale": 0.5,
        "shape_slat_sampling_steps": 12,
        "shape_slat_rescale_t": 3.0,
        "tex_slat_guidance_strength": 1.0,
        "tex_slat_guidance_rescale": 0.0,
        "tex_slat_sampling_steps": 12,
        "tex_slat_rescale_t": 3.0,
    }
    generate_call = kwargs_from_schema(generate_info, generate_values)
    t0 = time.monotonic()
    generated = client.predict(api_name=generate_name, **generate_call)
    generation_elapsed = round(time.monotonic() - t0, 2)

    if not isinstance(generated, (tuple, list)) or len(generated) < 1:
        raise RuntimeError(f"TRELLIS.2 generation returned no state: {type(generated).__name__}")
    state = generated[0]
    if not isinstance(state, dict) or not state:
        raise RuntimeError(f"TRELLIS.2 generation state is invalid: {type(state).__name__}")

    extract_name, extract_info = endpoint(client, "extract_glb")
    extract_values = {
        "state": state,
        "output_buf": state,
        "decimation_target": 500000,
        "texture_size": 1024,
    }
    extract_call = kwargs_from_schema(extract_info, extract_values)
    t1 = time.monotonic()
    extracted = client.predict(api_name=extract_name, **extract_call)
    extraction_elapsed = round(time.monotonic() - t1, 2)

    found: list[Path] = []
    collect_paths(extracted, ".glb", found)
    found = list(dict.fromkeys(found))
    if not found:
        raise RuntimeError(f"TRELLIS.2 extract_glb returned no materialized GLB: {type(extracted).__name__}")
    source = max(found, key=lambda p: p.stat().st_size)
    structural = validate_glb(source)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, args.output)
    report = {
        "provider": "microsoft/TRELLIS.2",
        "freeOnly": True,
        "authenticatedFreeTokenUsed": bool(token),
        "paidFallbackAllowed": False,
        "tripoApiUsed": False,
        "generationEndpoint": generate_name,
        "extractEndpoint": extract_name,
        "resolution": 512,
        "textureSize": 1024,
        "decimationTarget": 500000,
        "generationElapsedSeconds": generation_elapsed,
        "extractionElapsedSeconds": extraction_elapsed,
        "referencePreprocess": prep_meta,
        **structural,
        "productionReady": False,
        "productionGate": "CLOSED",
        "nextStage": "neutral multi-angle visual QC, then appearance bake/transfer onto the existing 1.3M-vertex animated hero",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("TRELLIS2_TEXTURED_REFERENCE_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
