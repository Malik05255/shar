#!/usr/bin/env python3
"""Prepare an isolated photorealistic full-body hero reference using free HF Spaces.

Free-only policy:
- only public Hugging Face ZeroGPU Spaces are attempted;
- no paid API key or billing fallback is allowed;
- failures rotate to the next free editor;
- outputs are references/candidates only, never production assets.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def download(url: str, path: Path) -> None:
    if not url.startswith("https://"):
        raise RuntimeError("reference URL must be HTTPS")
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-hero-reference/1.0"})
    with urllib.request.urlopen(req, timeout=90) as response, path.open("wb") as f:
        shutil.copyfileobj(response, f)
    if path.stat().st_size < 10_000:
        raise RuntimeError("reference download is suspiciously small")


def local_image_path(value: Any) -> Path | None:
    if value is None:
        return None
    if isinstance(value, str):
        p = Path(value)
        return p if p.exists() and p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"} else None
    if isinstance(value, Path):
        return value if value.exists() else None
    if isinstance(value, dict):
        for key in ("path", "name", "value", "data", "file"):
            if key in value:
                found = local_image_path(value[key])
                if found:
                    return found
        for v in value.values():
            found = local_image_path(v)
            if found:
                return found
    if isinstance(value, (list, tuple)):
        for v in value:
            found = local_image_path(v)
            if found:
                return found
    return None


def client(space: str):
    from gradio_client import Client
    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    return Client(space, **kwargs)


def pick_endpoint(c: Any, *needles: str) -> str:
    info = c.view_api(print_info=False, return_format="dict")
    endpoints = info.get("named_endpoints") or {}
    for name, spec in endpoints.items():
        text = json.dumps(spec).lower()
        if all(n.lower() in text for n in needles):
            return name
    if endpoints:
        return next(iter(endpoints))
    raise RuntimeError("Space exposes no callable endpoints")


def qwen_fast_edit(reference: Path, prompt: str) -> tuple[Path, dict[str, Any]]:
    """8-step Lightning Qwen edit. Space reserves only 60s ZeroGPU."""
    from gradio_client import handle_file
    space = "akhaliq/Qwen-Image-Edit-2509"
    c = client(space)
    endpoint = pick_endpoint(c, "prompt", "image")
    result = c.predict(
        handle_file(str(reference)),
        handle_file(str(reference)),
        prompt,
        20260831,
        1.0,
        "cartoon, anime, illustration, mascot, toy, low detail, malformed anatomy, extra limbs, desk, office, text",
        8,
        1.0,
        api_name=endpoint,
    )
    path = local_image_path(result)
    if not path:
        raise RuntimeError(f"Fast Qwen returned no materialized image: {type(result).__name__}")
    return path, {"space": space, "endpoint": endpoint, "steps": 8, "seed": 20260831}


def flux_kontext_edit(reference: Path, prompt: str) -> tuple[Path, dict[str, Any]]:
    """Official FLUX Kontext ZeroGPU fallback."""
    from gradio_client import handle_file
    space = "black-forest-labs/FLUX.1-Kontext-Dev"
    c = client(space)
    endpoint = pick_endpoint(c, "prompt", "image")
    result = c.predict(
        handle_file(str(reference)),
        prompt,
        20260831,
        False,
        2.5,
        20,
        api_name=endpoint,
    )
    path = local_image_path(result)
    if not path:
        raise RuntimeError(f"FLUX Kontext returned no materialized image: {type(result).__name__}")
    return path, {"space": space, "endpoint": endpoint, "steps": 20, "seed": 20260831}


def qwen_official_edit(reference: Path, prompt: str) -> tuple[Path, dict[str, Any]]:
    """Official Qwen fallback. It may exceed anonymous ZeroGPU duration."""
    from gradio_client import handle_file
    space = "Qwen/Qwen-Image-Edit"
    c = client(space)
    endpoint = pick_endpoint(c, "prompt", "image")
    result = c.predict(
        handle_file(str(reference)),
        prompt,
        20260831,
        False,
        4.0,
        24,
        False,
        api_name=endpoint,
    )
    path = local_image_path(result)
    if not path:
        raise RuntimeError(f"Official Qwen returned no materialized image: {type(result).__name__}")
    return path, {"space": space, "endpoint": endpoint, "steps": 24, "seed": 20260831}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference-url", required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    args = ap.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    original = args.output_dir / "approved-master-reference.png"
    output = args.output_dir / "police_dog.hero_reference.fullbody.png"
    report_path = args.output_dir / "hero-reference-report.json"
    report: dict[str, Any] = {
        "startedAt": now(),
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "productionReady": False,
        "attempts": [],
    }
    download(args.reference_url, original)

    prompt = (
        "Transform ONLY the central seated K9 police officer into a production-grade PHOTOREALISTIC full-body police dog character reference. "
        "The final character must be a believable real German Shepherd police K9, not cartoon, anime, illustration, mascot, toy or anthropomorphic fantasy. "
        "Preserve the recognizable face intent, alert friendly eyes, dark navy police uniform design, collar, badge/emblem placement and authoritative officer identity from the reference while correcting anatomy into a real German Shepherd. "
        "Remove the entire office, desk, monitor, phone, chair, other characters, signs, text and every prop. "
        "Reconstruct the complete body below the desk naturally: full torso, four anatomically correct legs and paws, tail, ears and muzzle, all fully visible and uncropped. "
        "Use a neutral standing quadruped reference pose suitable for high-end 3D reconstruction and skeletal rigging; head facing camera, legs separated clearly, tail visible, no limb overlap. "
        "Photorealistic individual fur strands and realistic black-and-tan German Shepherd coat pattern, physically plausible eyes/nose/claws, realistic navy fabric weave and metal badge response. "
        "Use a clean light-gray seamless studio background, even soft neutral lighting and minimal shadow. No desk, no scenery, no text, no extra objects, no stylization. "
        "Centered full-body character occupying most of the frame, reference-sheet quality."
    )

    providers = [
        ("qwen-image-edit-2509-fast-zero", qwen_fast_edit),
        ("flux-kontext-official-zero", flux_kontext_edit),
        ("qwen-image-edit-official-zero", qwen_official_edit),
    ]
    for name, fn in providers:
        attempt = {"provider": name, "startedAt": now()}
        report["attempts"].append(attempt)
        try:
            path, meta = fn(original, prompt)
            shutil.copy2(path, output)
            if output.stat().st_size < 100_000:
                raise RuntimeError(f"output image too small: {output.stat().st_size}")
            attempt.update({"status": "success", "bytes": output.stat().st_size, "metadata": meta, "finishedAt": now()})
            report["winner"] = name
            report["output"] = output.name
            break
        except Exception as exc:
            attempt.update({"status": "failed-free-provider", "reason": str(exc)[:1500], "finishedAt": now()})
            print(f"::warning::{name} failed: {exc}", flush=True)

    report["finishedAt"] = now()
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if not report.get("winner"):
        print("::error::No free image-edit provider produced the hero reference. Paid fallback disabled.")
        return 2
    print(f"FREE_HERO_REFERENCE={output}")
    print(f"FREE_HERO_REFERENCE_PROVIDER={report['winner']}")
    print("Production gate remains CLOSED.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
