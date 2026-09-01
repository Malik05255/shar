#!/usr/bin/env python3
"""Probe a Stable Fast 3D Gradio API without invoking GPU generation."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sf3d_textured_reference import sanitize, transparent_reference


def summarize(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float, str)):
        if isinstance(value, str):
            return {"type": "str", "length": len(value), "suffix": Path(value).suffix if len(value) < 500 else ""}
        return {"type": type(value).__name__, "value": value}
    if isinstance(value, Path):
        return {"type": "Path", "name": value.name, "exists": value.exists(), "suffix": value.suffix}
    if isinstance(value, dict):
        return {"type": "dict", "keys": sorted(str(k) for k in value.keys())}
    if isinstance(value, (tuple, list)):
        return {"type": type(value).__name__, "length": len(value)}
    size = getattr(value, "size", None)
    mode = getattr(value, "mode", None)
    return {"type": type(value).__name__, "size": list(size) if isinstance(size, tuple) else str(size), "mode": mode}


def parameter_summary(info: dict[str, Any]) -> list[dict[str, Any]]:
    rows = []
    for p in info.get("parameters") or []:
        raw = str(p.get("parameter_name") or p.get("label") or "")
        rows.append({
            "raw": raw,
            "sanitized": sanitize(raw),
            "hasDefault": bool(p.get("parameter_has_default")),
            "pythonType": p.get("python_type"),
            "component": p.get("component"),
        })
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--work-dir", type=Path, required=True)
    ap.add_argument("--space", default="stabilityai/stable-fast-3d")
    args = ap.parse_args()

    from gradio_client import Client, handle_file

    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    client = Client(args.space, **kwargs)

    args.work_dir.mkdir(parents=True, exist_ok=True)
    prepared = args.work_dir / "sf3d-probe-transparent.png"
    alpha_meta = transparent_reference(args.reference, prepared)

    api = client.view_api(print_info=False, return_format="dict") or {}
    named = api.get("named_endpoints") or {}
    endpoint_rows = {
        name: {
            "parameters": parameter_summary(info),
            "returns": info.get("returns") or [],
        }
        for name, info in named.items()
    }

    # Only call the known CPU preprocessing endpoint. Any direct image_to_glb endpoint
    # may be GPU-backed and is intentionally NOT invoked by this probe.
    prep_result_summary = None
    if any("requires_bg_remove" in name for name in named):
        prep_name = next(name for name in named if "requires_bg_remove" in name)
        prep_info = named[prep_name]
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
        prep_result_summary = summarize(client.predict(api_name=prep_name, **prep_kwargs))

    report = {
        "space": args.space,
        "authenticated": bool(token),
        "gpuInvoked": False,
        "referenceAlpha": alpha_meta,
        "namedEndpoints": endpoint_rows,
        "cpuPreprocessResult": prep_result_summary,
        "productionGate": "CLOSED",
    }
    out = args.work_dir / "sf3d-gradio-probe.json"
    out.write_text(json.dumps(report, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, default=str))
    print("SF3D_GRADIO_PROBE=PASS")
    print("GPU_INVOKED=false")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
