#!/usr/bin/env python3
"""Generate a free geometry-aware Hunyuan textured reference GLB.

Candidate-only: use official Tencent Hugging Face ZeroGPU Spaces, never a paid
provider. Hunyuan3D-2.1 is preferred only when an authenticated free HF token is
available; anonymous runs use Hunyuan3D-2 because its combined shape+paint call is
within the current anonymous ZeroGPU per-call duration cap.
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


def sanitize(name: str) -> str:
    name = re.sub(r"\W", "_", name.strip())
    if name and name[0].isdigit():
        name = "_" + name
    return name


def safe_reason(exc: BaseException) -> str:
    text = str(exc).replace("\n", " ").strip()
    token = os.environ.get("HF_TOKEN", "").strip()
    if token:
        text = text.replace(token, "***")
    return text[:1200]


def collect_glbs(value: Any, out: list[Path]) -> None:
    if value is None:
        return
    if isinstance(value, Path):
        if value.suffix.lower() == ".glb" and value.exists():
            out.append(value)
        return
    if isinstance(value, str):
        p = Path(value)
        if p.suffix.lower() == ".glb" and p.exists():
            out.append(p)
        return
    if isinstance(value, dict):
        for nested in value.values():
            collect_glbs(nested, out)
        return
    if isinstance(value, (tuple, list)):
        for nested in value:
            collect_glbs(nested, out)


def build_call(info: dict[str, Any], reference: Path) -> tuple[dict[str, Any], list[str]]:
    from gradio_client import handle_file

    call: dict[str, Any] = {}
    exposed: list[str] = []
    for parameter in info.get("parameters") or []:
        raw = str(parameter.get("parameter_name") or parameter.get("label") or "").strip()
        if not raw:
            continue
        name = sanitize(raw)
        exposed.append(name)
        lower = name.lower()
        if lower == "image" or (lower.endswith("_image") and not lower.startswith("mv_")):
            call[name] = handle_file(str(reference))
        elif lower == "caption":
            call[name] = None
        elif lower.startswith("mv_image_"):
            call[name] = None
        elif lower in {"steps", "num_steps"}:
            call[name] = 30
        elif lower in {"guidance_scale", "cfg_scale"}:
            call[name] = 7.5
        elif lower == "seed":
            call[name] = 1234
        elif lower == "octree_resolution":
            call[name] = 384
        elif lower in {"check_box_rembg", "remove_background"}:
            call[name] = True
        elif lower == "num_chunks":
            call[name] = 200000
        elif lower == "randomize_seed":
            call[name] = False
        elif parameter.get("parameter_has_default"):
            continue
        else:
            call[name] = None
    if not any(k.lower() == "image" for k in call):
        raise RuntimeError(f"generation_all exposes no image parameter; parameters={exposed}")
    return call, exposed


def validate_textured_glb(path: Path) -> tuple[dict[str, Any], list[str]]:
    doc = read_glb_json(path)
    meshes = doc.get("meshes") or []
    materials = doc.get("materials") or []
    textures = doc.get("textures") or []
    images = doc.get("images") or []
    if not meshes:
        raise RuntimeError("Textured reference contains no meshes")
    if not materials or not textures or not images:
        raise RuntimeError(
            f"Incomplete material payload: materials={len(materials)} textures={len(textures)} images={len(images)}"
        )

    channels: list[str] = []
    for material in materials:
        pbr = material.get("pbrMetallicRoughness") or {}
        if pbr.get("baseColorTexture") is not None:
            channels.append("baseColorTexture")
        if pbr.get("metallicRoughnessTexture") is not None:
            channels.append("metallicRoughnessTexture")
        if material.get("normalTexture") is not None:
            channels.append("normalTexture")
    if "baseColorTexture" not in channels:
        raise RuntimeError(f"Generated reference lacks baseColorTexture; channels={sorted(set(channels))}")
    return {
        "meshes": len(meshes),
        "materials": len(materials),
        "textures": len(textures),
        "images": len(images),
    }, sorted(set(channels))


def run_space(space: str, reference: Path, token: str | None) -> tuple[Path, dict[str, Any]]:
    from gradio_client import Client

    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    client = Client(space, **kwargs)
    api = client.view_api(print_info=False, return_format="dict")
    named = api.get("named_endpoints") or {}
    endpoint = next((name for name in named if "generation_all" in name), None)
    if not endpoint:
        raise RuntimeError(f"Space exposes no generation_all endpoint; endpoints={list(named)}")
    call, exposed = build_call(named[endpoint], reference)

    t0 = time.monotonic()
    result = client.predict(api_name=endpoint, **call)
    elapsed = round(time.monotonic() - t0, 2)

    glbs: list[Path] = []
    collect_glbs(result, glbs)
    unique = list(dict.fromkeys(glbs))
    if not unique:
        raise RuntimeError(f"generation_all returned no materialized GLB: {type(result).__name__}")
    textured = [p for p in unique if "textured" in p.name.lower()]
    source = max(textured or unique, key=lambda p: p.stat().st_size)
    structural, channels = validate_textured_glb(source)
    return source, {
        "space": space,
        "endpoint": endpoint,
        "elapsedSeconds": elapsed,
        "exposedParameters": exposed,
        "structural": structural,
        "pbrChannels": channels,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    ap.add_argument("--space", default=None, help="Optional explicit official Tencent Space")
    args = ap.parse_args()

    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Approved hero reference is missing or unexpectedly small")

    token = os.environ.get("HF_TOKEN", "").strip() or None
    if args.space:
        spaces = [args.space]
    elif token:
        spaces = ["tencent/Hunyuan3D-2.1", "tencent/Hunyuan3D-2"]
    else:
        # 2.1 currently requests 270s for generation_all, above anonymous per-call cap.
        # 2.0's official Space requests 90s and remains a legitimate free ZeroGPU path.
        spaces = ["tencent/Hunyuan3D-2"]

    attempts: list[dict[str, Any]] = []
    source: Path | None = None
    metadata: dict[str, Any] | None = None
    for space in spaces:
        attempt = {"space": space, "status": "started"}
        attempts.append(attempt)
        try:
            source, metadata = run_space(space, args.reference, token)
            attempt.update({"status": "success", "elapsedSeconds": metadata["elapsedSeconds"]})
            break
        except Exception as exc:
            attempt.update({"status": "failed-free-provider", "reason": safe_reason(exc), "exceptionType": type(exc).__name__})
            print(f"::warning::{space} failed: {attempt['reason']}", flush=True)

    if source is None or metadata is None:
        raise RuntimeError(f"Every official free Hunyuan texture path failed: {attempts}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, args.output)
    structural = metadata["structural"]
    report = {
        "provider": "official-tencent-hunyuan-zero",
        "space": metadata["space"],
        "endpoint": metadata["endpoint"],
        "freeOnly": True,
        "authenticatedFreeTokenUsed": bool(token),
        "paidFallbackAllowed": False,
        "tripoApiUsed": False,
        "purpose": "360-degree geometry-aware PBR texture transfer source",
        "reference": args.reference.name,
        "elapsedSeconds": metadata["elapsedSeconds"],
        "bytes": args.output.stat().st_size,
        "meshes": structural["meshes"],
        "materials": structural["materials"],
        "textures": structural["textures"],
        "images": structural["images"],
        "pbrChannels": metadata["pbrChannels"],
        "attempts": attempts,
        "parameters": {
            "steps": 30,
            "guidanceScale": 7.5,
            "seed": 1234,
            "octreeResolution": 384,
            "removeBackground": True,
            "randomizeSeed": False,
        },
        "productionReady": False,
        "productionGate": "CLOSED",
        "nextStage": "bake/transfer generated 360-degree PBR onto the existing animated high-resolution hero without changing its topology",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print("HUNYUAN_TEXTURED_REFERENCE_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
