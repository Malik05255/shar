#!/usr/bin/env python3
"""Generate a free Hunyuan3D-2.1 textured donor from the exact hero reference.

This script intentionally uses the donor ONLY as a texture source. Its geometry is never accepted
as the runtime hero because the official generation_all path performs face reduction before paint.
The downstream Blender transfer preserves the already-rigged/animated hero geometry.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
from pathlib import Path
from typing import Any


def _sanitize(name: str) -> str:
    name = re.sub(r"\W", "_", name.strip())
    if name and name[0].isdigit():
        name = "_" + name
    return name


def _client(space: str):
    from gradio_client import Client
    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    return Client(space, **kwargs)


def _endpoint_info(client: Any, needle: str) -> tuple[str, dict[str, Any]]:
    info = client.view_api(print_info=False, return_format="dict")
    named = info.get("named_endpoints") or {}
    endpoint = next((name for name in named if needle in name), None)
    if not endpoint:
        raise RuntimeError(f"Space does not expose {needle}; endpoints={list(named)}")
    return endpoint, named[endpoint]


def _build_kwargs(endpoint_info: dict[str, Any], reference: Path) -> tuple[dict[str, Any], list[str]]:
    from gradio_client import handle_file
    kwargs: dict[str, Any] = {}
    exposed: list[str] = []
    for parameter in endpoint_info.get("parameters") or []:
        raw = str(parameter.get("parameter_name") or parameter.get("label") or "").strip()
        if not raw:
            continue
        name = _sanitize(raw)
        exposed.append(name)
        lower = name.lower()
        if lower == "image" or (lower.endswith("_image") and not lower.startswith("mv_")):
            kwargs[name] = handle_file(str(reference))
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
            kwargs[name] = 384
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
    if not any(k.lower() == "image" for k in kwargs):
        raise RuntimeError(f"generation_all exposes no image input; parameters={exposed}")
    return kwargs, exposed


def _collect_glbs(value: Any, out: list[Path]) -> None:
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
        for v in value.values():
            _collect_glbs(v, out)
        return
    if isinstance(value, (list, tuple)):
        for v in value:
            _collect_glbs(v, out)


def _read_glb_json(path: Path) -> dict[str, Any]:
    import struct
    raw = path.read_bytes()
    if len(raw) < 20 or raw[:4] != b"glTF":
        raise RuntimeError(f"Not a GLB: {path}")
    version, total = struct.unpack_from("<II", raw, 4)
    if version != 2 or total > len(raw):
        raise RuntimeError(f"Invalid GLB header: version={version}, total={total}")
    length, chunk_type = struct.unpack_from("<II", raw, 12)
    if chunk_type != 0x4E4F534A:
        raise RuntimeError("GLB first chunk is not JSON")
    return json.loads(raw[20:20 + length].decode("utf-8").rstrip(" \t\r\n\x00"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    ap.add_argument("--space", default="tencent/Hunyuan3D-2.1")
    args = ap.parse_args()

    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Reference image missing or too small")

    client = _client(args.space)
    endpoint, info = _endpoint_info(client, "generation_all")
    kwargs, parameters = _build_kwargs(info, args.reference)
    result = client.predict(api_name=endpoint, **kwargs)

    glbs: list[Path] = []
    _collect_glbs(result, glbs)
    unique: list[Path] = []
    seen = set()
    for p in glbs:
        rp = str(p.resolve())
        if rp not in seen:
            unique.append(p)
            seen.add(rp)
    if not unique:
        raise RuntimeError(f"Hunyuan generation_all returned no materialized GLB; type={type(result).__name__}")

    textured = next((p for p in unique if "textured" in p.name.lower()), None)
    if textured is None and len(unique) >= 2:
        textured = unique[1]
    if textured is None:
        textured = unique[0]

    doc = _read_glb_json(textured)
    materials = doc.get("materials") or []
    textures = doc.get("textures") or []
    images = doc.get("images") or []
    if not materials:
        raise RuntimeError(f"Hunyuan donor contains no materials: {textured}")
    if not textures and not images:
        raise RuntimeError(f"Hunyuan donor contains materials but no texture/image payload: {textured}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(textured, args.output)
    report = {
        "provider": args.space,
        "endpoint": endpoint,
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "reference": args.reference.name,
        "seed": 1234,
        "octreeResolution": 384,
        "steps": 30,
        "guidanceScale": 7.5,
        "parameters": parameters,
        "returnedGlbs": [p.name for p in unique],
        "selectedDonor": textured.name,
        "bytes": args.output.stat().st_size,
        "materials": len(materials),
        "textures": len(textures),
        "images": len(images),
        "geometryAcceptedForRuntime": False,
        "purpose": "PBR texture donor only",
        "productionReady": False,
    }
    args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("HUNYUAN_PBR_DONOR_GATE=PASS")
    print("DONOR_GEOMETRY_ACCEPTED=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
