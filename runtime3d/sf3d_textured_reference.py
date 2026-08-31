#!/usr/bin/env python3
"""Generate a free textured GLB from a Stable Fast 3D ZeroGPU Space.

Candidate-only. The caller may choose an independent compatible Space when the
official Stability AI demo is unhealthy. No paid API endpoint is ever used.
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
    named = (client.view_api(print_info=False, return_format="dict") or {}).get("named_endpoints") or {}
    name = next((x for x in named if needle in x), None)
    if not name:
        raise RuntimeError(f"Stable Fast 3D exposes no {needle}; endpoints={list(named)}")
    return name, named[name]


def collect_glbs(value: Any, found: list[Path]) -> None:
    if isinstance(value, Path) and value.suffix.lower() == ".glb" and value.exists():
        found.append(value)
    elif isinstance(value, str):
        p = Path(value)
        if p.suffix.lower() == ".glb" and p.exists():
            found.append(p)
    elif isinstance(value, dict):
        for nested in value.values():
            collect_glbs(nested, found)
    elif isinstance(value, (tuple, list)):
        for nested in value:
            collect_glbs(nested, found)


def transparent_reference(source: Path, output: Path) -> dict[str, Any]:
    """Use the project's deterministic subject mask; no GPU is spent on rembg."""
    import numpy as np
    from PIL import Image, ImageFilter
    from reference_pbr_maps import background_plane, center_component

    image = Image.open(source).convert("RGB")
    rgb = np.asarray(image, dtype=np.float32)
    bg = background_plane(rgb)
    lum, bg_lum = rgb.mean(axis=2), bg.mean(axis=2)
    chroma = rgb.max(axis=2) - rgb.min(axis=2)
    raw = ((bg_lum - lum) > 60.0) | (chroma > 28.0)
    mask = Image.fromarray((raw * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(3))
    component = center_component(np.asarray(mask) > 127)
    alpha = Image.fromarray((component * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))
    ys, xs = np.where(component)
    if len(xs) == 0 or len(ys) == 0:
        raise RuntimeError("Subject mask is empty")
    box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    rgba = image.convert("RGBA")
    rgba.putalpha(alpha)
    output.parent.mkdir(parents=True, exist_ok=True)
    rgba.crop(box).save(output, "PNG", optimize=True)
    return {"crop": list(box), "foregroundPixels": int(component.sum()), "bytes": output.stat().st_size}


def validate(path: Path) -> dict[str, Any]:
    doc = read_glb_json(path)
    meshes, materials = doc.get("meshes") or [], doc.get("materials") or []
    textures, images = doc.get("textures") or [], doc.get("images") or []
    accessors = doc.get("accessors") or []
    vertices = 0
    channels: set[str] = set()
    for mesh in meshes:
        for prim in mesh.get("primitives") or []:
            pos = (prim.get("attributes") or {}).get("POSITION")
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
            f"SF3D payload incomplete meshes={len(meshes)} vertices={vertices} materials={len(materials)} "
            f"textures={len(textures)} images={len(images)} channels={sorted(channels)}"
        )
    return {
        "meshes": len(meshes),
        "vertices": vertices,
        "materials": len(materials),
        "textures": len(textures),
        "images": len(images),
        "pbrChannels": sorted(channels),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    ap.add_argument("--space", default="stabilityai/stable-fast-3d")
    args = ap.parse_args()
    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Approved hero reference is missing/small")

    from gradio_client import Client, handle_file
    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    client = Client(args.space, **kwargs)

    prepared = args.output.parent / "sf3d-reference-transparent.png"
    alpha_meta = transparent_reference(args.reference, prepared)

    # Prime the two hidden Gradio State components in the SAME Client session.
    # The live API intentionally exposes only image + slider inputs for run_button;
    # run_btn and background_state are internal state and must not be passed manually.
    prep_name, prep_info = endpoint(client, "requires_bg_remove")
    prep_kwargs: dict[str, Any] = {}
    for p in prep_info.get("parameters") or []:
        name = sanitize(str(p.get("parameter_name") or p.get("label") or ""))
        low = name.lower()
        if low in {"image", "input_image", "input_img"}:
            prep_kwargs[name] = handle_file(str(prepared))
        elif low in {"fr", "foreground_ratio"}:
            prep_kwargs[name] = 0.85
        elif not p.get("parameter_has_default"):
            prep_kwargs[name] = None
    prep_result = client.predict(api_name=prep_name, **prep_kwargs)
    print(f"SF3D_PREPROCESS_PASS space={args.space} resultType={type(prep_result).__name__}", flush=True)

    run_name, run_info = endpoint(client, "run_button")
    call: dict[str, Any] = {}
    exposed: list[str] = []
    for p in run_info.get("parameters") or []:
        name = sanitize(str(p.get("parameter_name") or p.get("label") or ""))
        if not name:
            continue
        exposed.append(name)
        low = name.lower()
        if low in {"input_image", "image", "input_img"}:
            call[name] = handle_file(str(prepared))
        elif low in {"foreground_ratio", "fr"}:
            call[name] = 0.85
        elif low == "remesh_option":
            call[name] = "None"
        elif low in {"vertex_count", "target_vertex_count"}:
            call[name] = -1
        elif low in {"texture_size", "texture_resolution"}:
            call[name] = 1024
        elif not p.get("parameter_has_default"):
            call[name] = None

    print(f"SF3D_RUN_CONTRACT space={args.space} exposed={exposed}", flush=True)
    started = time.monotonic()
    result = client.predict(api_name=run_name, **call)
    elapsed = round(time.monotonic() - started, 2)
    found: list[Path] = []
    collect_glbs(result, found)
    found = list(dict.fromkeys(found))
    if not found:
        raise RuntimeError(f"SF3D returned no GLB; space={args.space} exposed={exposed} resultType={type(result).__name__}")
    source = max(found, key=lambda p: p.stat().st_size)
    structural = validate(source)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, args.output)
    report = {
        "provider": "stable-fast-3d-zero-gpu",
        "space": args.space,
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "tripoApiUsed": False,
        "preprocessEndpoint": prep_name,
        "endpoint": run_name,
        "textureSize": 1024,
        "remeshOption": "None",
        "targetVertexCount": -1,
        "elapsedSeconds": elapsed,
        "referenceAlpha": alpha_meta,
        **structural,
        "productionReady": False,
        "productionGate": "CLOSED",
        "nextStage": "visual multi-angle donor QC, then geometry-aware appearance transfer onto the existing animated high-resolution hero",
    }
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("SF3D_TEXTURED_REFERENCE_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
