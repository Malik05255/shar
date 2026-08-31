#!/usr/bin/env python3
"""Generate one free textured comparison candidate on Tencent's Hunyuan3D-2 ZeroGPU Space.

This is deliberately a comparison stage, not a production fallback. It uses the exact
hero reference, calls the live /generation_all schema, selects the textured GLB rather
than the white mesh, and fails closed unless a material + embedded texture image exists.
No paid API is used and production activation is outside this script.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from inspect_glb import read_glb_json

SPACE = "tencent/Hunyuan3D-2"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sanitize(name: str) -> str:
    value = re.sub(r"\W", "_", name.strip())
    if value and value[0].isdigit():
        value = "_" + value
    return value


def client_for_space():
    from gradio_client import Client
    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        # Keep compatibility with the Gradio client version currently used in CI.
        kwargs["hf_token"] = token
    return Client(SPACE, **kwargs)


def endpoint_info(client: Any, needle: str) -> tuple[str, dict[str, Any]]:
    info = client.view_api(print_info=False, return_format="dict")
    named = info.get("named_endpoints") or {}
    endpoint = next((name for name in named if needle in name), None)
    if not endpoint:
        raise RuntimeError(f"Space does not expose {needle}; endpoints={list(named)}")
    return endpoint, named[endpoint]


def build_live_kwargs(info: dict[str, Any], reference: Path) -> tuple[dict[str, Any], list[str]]:
    from gradio_client import handle_file
    kwargs: dict[str, Any] = {}
    exposed: list[str] = []
    image_set = False
    for parameter in info.get("parameters") or []:
        raw = str(parameter.get("parameter_name") or parameter.get("label") or "").strip()
        if not raw:
            continue
        name = sanitize(raw)
        exposed.append(name)
        lower = name.lower()
        if lower == "image" or (lower.endswith("_image") and not lower.startswith("mv_")):
            kwargs[name] = handle_file(str(reference))
            image_set = True
        elif lower == "caption":
            kwargs[name] = None
        elif lower.startswith("mv_image_"):
            kwargs[name] = None
        elif lower in {"steps", "num_steps"}:
            kwargs[name] = 30
        elif lower in {"guidance_scale", "cfg_scale"}:
            kwargs[name] = 7.5
        elif lower == "seed":
            kwargs[name] = 1234
        elif lower == "octree_resolution":
            kwargs[name] = 256
        elif lower in {"check_box_rembg", "remove_background"}:
            kwargs[name] = True
        elif lower == "num_chunks":
            kwargs[name] = 200000
        elif lower == "randomize_seed":
            kwargs[name] = False
        elif parameter.get("parameter_has_default"):
            continue
        else:
            kwargs[name] = None
    if not image_set:
        raise RuntimeError(f"generation_all exposes no usable image input; parameters={exposed}")
    return kwargs, exposed


def collect_materialized_glbs(value: Any) -> list[Path]:
    found: list[Path] = []
    if value is None:
        return found
    if isinstance(value, Path):
        if value.suffix.lower() == ".glb" and value.exists():
            found.append(value)
    elif isinstance(value, str):
        p = Path(value)
        if p.suffix.lower() == ".glb" and p.exists():
            found.append(p)
    elif isinstance(value, dict):
        for nested in value.values():
            found.extend(collect_materialized_glbs(nested))
    elif isinstance(value, (list, tuple)):
        for nested in value:
            found.extend(collect_materialized_glbs(nested))
    unique: list[Path] = []
    seen: set[str] = set()
    for p in found:
        key = str(p.resolve())
        if key not in seen:
            seen.add(key)
            unique.append(p)
    return unique


def glb_gate(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size < 100_000:
        raise RuntimeError(f"Textured GLB missing/too small: {path}")
    doc = read_glb_json(path)
    accessors = doc.get("accessors") or []
    vertices = 0
    for mesh in doc.get("meshes") or []:
        for prim in mesh.get("primitives") or []:
            pos = (prim.get("attributes") or {}).get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertices += int(accessors[pos].get("count") or 0)
    materials = doc.get("materials") or []
    textures = doc.get("textures") or []
    images = doc.get("images") or []
    if vertices < 10_000:
        raise RuntimeError(f"Textured candidate below geometry floor: vertices={vertices}")
    if not materials or not textures or not images:
        raise RuntimeError(
            f"Textured candidate missing embedded material payload: materials={len(materials)} "
            f"textures={len(textures)} images={len(images)}"
        )
    return {
        "bytes": path.stat().st_size,
        "meshes": len(doc.get("meshes") or []),
        "vertices": vertices,
        "materials": len(materials),
        "textures": len(textures),
        "images": len(images),
        "animations": len(doc.get("animations") or []),
        "skins": len(doc.get("skins") or []),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    args = ap.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.output_dir / "hunyuan20-textured-report.json"
    report: dict[str, Any] = {
        "startedAt": utc_now(),
        "space": SPACE,
        "costMode": "free-only",
        "productionReady": False,
        "productionGate": "CLOSED",
    }
    try:
        if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
            raise RuntimeError(f"Reference missing/too small: {args.reference}")
        staged = args.output_dir / "hero-reference.webp"
        shutil.copy2(args.reference, staged)
        report["referenceBytes"] = staged.stat().st_size

        client = client_for_space()
        endpoint, info = endpoint_info(client, "generation_all")
        kwargs, exposed = build_live_kwargs(info, staged)
        report["endpoint"] = endpoint
        report["exposedParameters"] = exposed
        report["request"] = {
            "steps": 30,
            "guidanceScale": 7.5,
            "seed": 1234,
            "octreeResolution": 256,
            "removeBackground": True,
        }
        started = time.monotonic()
        result = client.predict(api_name=endpoint, **kwargs)
        report["elapsedSeconds"] = round(time.monotonic() - started, 2)
        glbs = collect_materialized_glbs(result)
        report["materializedGlbs"] = [p.name for p in glbs]
        if not glbs:
            raise RuntimeError(f"generation_all returned no materialized GLB; result={type(result).__name__}")

        textured = [p for p in glbs if "textur" in p.name.lower()]
        candidates = textured or glbs
        accepted: tuple[Path, dict[str, Any]] | None = None
        rejection: list[dict[str, str]] = []
        for p in candidates:
            try:
                gate = glb_gate(p)
                accepted = (p, gate)
                break
            except Exception as exc:
                rejection.append({"file": p.name, "reason": str(exc)[:600]})
        report["rejectedGlbs"] = rejection
        if accepted is None:
            raise RuntimeError("No returned GLB passed the textured structural/material gate")

        source, gate = accepted
        target = args.output_dir / "police_dog.hunyuan20.textured_comparison.glb"
        shutil.copy2(source, target)
        report["candidate"] = target.name
        report["structuralMaterialGate"] = gate
        report["status"] = "success-comparison-candidate"
        report["requiredNext"] = [
            "four-view visual identity comparison against Hunyuan 2.1 shape and hero reference",
            "reject if fur/uniform/face quality is below the cinematic benchmark",
            "do not promote RGB texture as Hunyuan 2.1 PBR equivalence",
            "rig/deformation/lip-sync only after visual acceptance"
        ]
        report["finishedAt"] = utc_now()
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("HUNYUAN20_TEXTURED_COMPARISON=PASS")
        print("PRODUCTION_GATE=CLOSED")
        return 0
    except Exception as exc:
        report["status"] = "failed-free-only"
        report["reason"] = str(exc).replace("\n", " ")[:1200]
        report["finishedAt"] = utc_now()
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("HUNYUAN20_TEXTURED_COMPARISON=FAILED_FREE_ONLY")
        print("PRODUCTION_GATE=CLOSED")
        return 2


if __name__ == "__main__":
    sys.exit(main())
