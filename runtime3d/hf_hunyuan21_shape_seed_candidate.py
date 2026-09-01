#!/usr/bin/env python3
"""Generate a second official Hunyuan3D-2.1 ZeroGPU shape candidate for comparison.

Uses one exact hero reference and one explicit deterministic seed. This never calls a
paid API, never textures, never activates production, and rejects weak GLBs structurally.
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

SPACE = "tencent/Hunyuan3D-2.1"


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
        kwargs["hf_token"] = token
    return Client(SPACE, **kwargs)


def endpoint_info(client: Any) -> tuple[str, dict[str, Any]]:
    info = client.view_api(print_info=False, return_format="dict")
    named = info.get("named_endpoints") or {}
    endpoint = next((name for name in named if "shape_generation" in name), None)
    if not endpoint:
        raise RuntimeError(f"Official Space exposes no shape_generation; endpoints={list(named)}")
    return endpoint, named[endpoint]


def kwargs_from_live_schema(info: dict[str, Any], reference: Path, seed: int) -> tuple[dict[str, Any], list[str]]:
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
            kwargs[name] = handle_file(str(reference)); image_set = True
        elif lower == "caption": kwargs[name] = None
        elif lower.startswith("mv_image_"): kwargs[name] = None
        elif lower in {"steps", "num_steps"}: kwargs[name] = 30
        elif lower in {"guidance_scale", "cfg_scale"}: kwargs[name] = 7.5
        elif lower == "seed": kwargs[name] = int(seed)
        elif lower == "octree_resolution": kwargs[name] = 384
        elif lower in {"check_box_rembg", "remove_background"}: kwargs[name] = True
        elif lower == "num_chunks": kwargs[name] = 200000
        elif lower == "randomize_seed": kwargs[name] = False
        elif parameter.get("parameter_has_default"): continue
        else: kwargs[name] = None
    if not image_set:
        raise RuntimeError(f"shape_generation exposes no image input; parameters={exposed}")
    return kwargs, exposed


def find_glb(value: Any) -> Path | None:
    if value is None: return None
    if isinstance(value, Path): return value if value.exists() and value.suffix.lower() == ".glb" else None
    if isinstance(value, str):
        p = Path(value); return p if p.exists() and p.suffix.lower() == ".glb" else None
    if isinstance(value, dict):
        for nested in value.values():
            p = find_glb(nested)
            if p: return p
    if isinstance(value, (list, tuple)):
        for nested in value:
            p = find_glb(nested)
            if p: return p
    return None


def gate(path: Path) -> dict[str, int]:
    if not path.is_file() or path.stat().st_size < 100_000:
        raise RuntimeError("Returned Hunyuan GLB is missing or too small")
    doc = read_glb_json(path)
    accessors = doc.get("accessors") or []
    vertices = 0
    for mesh in doc.get("meshes") or []:
        for prim in mesh.get("primitives") or []:
            pos = (prim.get("attributes") or {}).get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertices += int(accessors[pos].get("count") or 0)
    if vertices < 100_000:
        raise RuntimeError(f"Hunyuan geometry floor failed: {vertices} vertices")
    return {"bytes": path.stat().st_size, "meshes": len(doc.get("meshes") or []), "vertices": vertices}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    ap.add_argument("--seed", type=int, default=7319)
    args = ap.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "startedAt": utc_now(), "space": SPACE, "seed": args.seed,
        "costMode": "free-only", "textured": False,
        "productionReady": False, "productionGate": "CLOSED"
    }
    report_path = args.output_dir / "hunyuan21-second-seed-report.json"
    try:
        if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
            raise RuntimeError(f"Reference missing/too small: {args.reference}")
        staged = args.output_dir / "hero-reference.webp"
        shutil.copy2(args.reference, staged)
        client = client_for_space()
        endpoint, info = endpoint_info(client)
        kwargs, exposed = kwargs_from_live_schema(info, staged, args.seed)
        report["endpoint"] = endpoint; report["exposedParameters"] = exposed
        t0 = time.monotonic()
        result = client.predict(api_name=endpoint, **kwargs)
        report["elapsedSeconds"] = round(time.monotonic() - t0, 2)
        source = find_glb(result)
        if not source:
            raise RuntimeError(f"shape_generation returned no materialized GLB: {type(result).__name__}")
        structural = gate(source)
        target = args.output_dir / f"police_dog.hunyuan21.seed-{args.seed}.shape_candidate.glb"
        shutil.copy2(source, target)
        report.update({"status": "success", "candidate": target.name, "structuralGate": structural, "finishedAt": utc_now()})
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("HUNYUAN21_SECOND_SEED=PASS")
        print("PRODUCTION_GATE=CLOSED")
        return 0
    except Exception as exc:
        report.update({"status": "failed-free-only", "reason": str(exc).replace("\n", " ")[:1200], "finishedAt": utc_now()})
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("HUNYUAN21_SECOND_SEED=FAILED_FREE_ONLY")
        print("PRODUCTION_GATE=CLOSED")
        return 2


if __name__ == "__main__":
    sys.exit(main())
