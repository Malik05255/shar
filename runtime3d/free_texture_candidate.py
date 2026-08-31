#!/usr/bin/env python3
"""Probe legitimate free Hunyuan texture providers without paid fallback.

The output is a TEXTURE CANDIDATE ONLY. Some public Hunyuan Gradio pipelines perform internal
face reduction before paint, so no result from this script may replace the accepted hero mesh
without a visual/topology quality comparison. The goal is to obtain a free PBR/material reference
and determine which provider can serve the project while the production mesh remains protected.
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

sys.path.insert(0, str(Path(__file__).parent))
from inspect_glb import read_glb_json

PROVIDERS = [
    {"id": "hf-hunyuan3d-2.1-zero-textured", "space": "tencent/Hunyuan3D-2.1", "priority": 10},
    {"id": "hf-hunyuan3d-2.0-zero-textured", "space": "tencent/Hunyuan3D-2", "priority": 20},
]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sanitize(name: str) -> str:
    value = re.sub(r"\W", "_", name.strip())
    return "_" + value if value[:1].isdigit() else value


def client(space: str):
    from gradio_client import Client
    token = os.environ.get("HF_TOKEN", "").strip() or None
    kw: dict[str, Any] = {"verbose": False}
    if token:
        kw["hf_token"] = token
    return Client(space, **kw)


def endpoint_info(c: Any) -> tuple[str, dict[str, Any]]:
    info = c.view_api(print_info=False, return_format="dict")
    named = info.get("named_endpoints") or {}
    name = next((n for n in named if "generation_all" in n), None)
    if not name:
        raise RuntimeError(f"No generation_all endpoint; endpoints={list(named)}")
    return name, named[name]


def build_kwargs(info: dict[str, Any], reference: Path) -> dict[str, Any]:
    from gradio_client import handle_file
    out: dict[str, Any] = {}
    for p in info.get("parameters") or []:
        raw = str(p.get("parameter_name") or p.get("label") or "").strip()
        if not raw:
            continue
        name = sanitize(raw)
        low = name.lower()
        if low == "image" or (low.endswith("_image") and not low.startswith("mv_")):
            out[name] = handle_file(str(reference))
        elif low == "caption":
            out[name] = None
        elif low.startswith("mv_image_"):
            out[name] = None
        elif low in {"steps", "num_steps"}:
            out[name] = 30
        elif low in {"guidance_scale", "cfg_scale"}:
            out[name] = 7.5
        elif low == "seed":
            out[name] = 1234
        elif low == "octree_resolution":
            out[name] = 384
        elif low in {"check_box_rembg", "remove_background"}:
            out[name] = True
        elif low == "num_chunks":
            out[name] = 200000
        elif low == "randomize_seed":
            out[name] = False
        elif p.get("parameter_has_default"):
            continue
        else:
            out[name] = None
    if not any(k.lower() == "image" for k in out):
        raise RuntimeError(f"generation_all exposes no image input: {list(out)}")
    return out


def collect_glbs(value: Any, found: list[Path]) -> None:
    if value is None:
        return
    if isinstance(value, (str, Path)):
        p = Path(value)
        if p.suffix.lower() == ".glb" and p.is_file():
            found.append(p)
        return
    if isinstance(value, dict):
        for v in value.values():
            collect_glbs(v, found)
        return
    if isinstance(value, (list, tuple)):
        for v in value:
            collect_glbs(v, found)


def score_glb(path: Path) -> dict[str, int]:
    doc = read_glb_json(path)
    meshes = doc.get("meshes") or []
    accessors = doc.get("accessors") or []
    vertices = 0
    for mesh in meshes:
        for prim in mesh.get("primitives") or []:
            pos = (prim.get("attributes") or {}).get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertices += int(accessors[pos].get("count") or 0)
    return {
        "bytes": path.stat().st_size,
        "meshes": len(meshes),
        "vertices": vertices,
        "materials": len(doc.get("materials") or []),
        "textures": len(doc.get("textures") or []),
        "images": len(doc.get("images") or []),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference-file", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    args = ap.parse_args()
    if not args.reference_file.is_file() or args.reference_file.stat().st_size < 10_000:
        raise RuntimeError("Exact hero reference file missing/too small")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    report: dict[str, Any] = {
        "startedAt": now(),
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "productionReady": False,
        "productionGate": "CLOSED",
        "referenceBytes": args.reference_file.stat().st_size,
        "attempts": [],
        "winner": None,
        "warnings": [
            "Public generation_all pipelines may perform internal face reduction before texture generation.",
            "A textured candidate must never replace the accepted hero mesh without visual/topology comparison.",
        ],
    }

    for provider in PROVIDERS:
        attempt: dict[str, Any] = {"provider": provider["id"], "space": provider["space"], "startedAt": now()}
        report["attempts"].append(attempt)
        try:
            c = client(provider["space"])
            endpoint, info = endpoint_info(c)
            kw = build_kwargs(info, args.reference_file)
            started = time.monotonic()
            result = c.predict(api_name=endpoint, **kw)
            glbs: list[Path] = []
            collect_glbs(result, glbs)
            unique: list[Path] = []
            seen: set[str] = set()
            for p in glbs:
                key = str(p.resolve())
                if key not in seen:
                    seen.add(key)
                    unique.append(p)
            scored = [(p, score_glb(p)) for p in unique]
            attempt["returned"] = [{"file": p.name, **s} for p, s in scored]
            candidates = [(p, s) for p, s in scored if s["materials"] > 0 and (s["textures"] > 0 or s["images"] > 0)]
            if not candidates:
                raise RuntimeError(f"Provider returned no GLB with material+texture payload; returned={attempt['returned']}")
            # Prefer richest material payload, then geometry. Never imply this is the production mesh.
            source, stats = max(candidates, key=lambda item: (item[1]["textures"] + item[1]["images"], item[1]["vertices"], item[1]["bytes"]))
            target = args.output_dir / f"police_dog.{provider['id']}.texture_candidate.glb"
            shutil.copy2(source, target)
            attempt.update({"status": "success", "elapsedSeconds": round(time.monotonic() - started, 2), "winnerStats": stats, "finishedAt": now()})
            report["winner"] = provider["id"]
            report["winnerFile"] = target.name
            report["winnerStats"] = stats
            break
        except Exception as exc:
            attempt.update({"status": "failed-free-provider", "reason": str(exc)[:1600], "finishedAt": now()})
            print(f"::warning::{provider['id']} failed: {attempt['reason']}", flush=True)

    report["finishedAt"] = now()
    (args.output_dir / "texture-provider-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if not report["winner"]:
        print("::error::All automated free texture providers unavailable/exhausted; paid fallback disabled.")
        return 2
    print(f"FREE_TEXTURE_WINNER={report['winner']}")
    print(f"FREE_TEXTURE_CANDIDATE={report['winnerFile']}")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
