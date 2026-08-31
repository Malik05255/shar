#!/usr/bin/env python3
"""Prepare a photorealistic full-body K9 officer reference using free HF Spaces.

Two-stage free pipeline:
1) derive a clean realistic German-Shepherd anatomy/identity base from the old office image;
2) convert that base into the production-style anthropomorphic K9 officer needed by
   the runtime animation contract: biped stance, full navy uniform, usable arms/hands.

No paid provider, paid overage, or automatic billing fallback is allowed.
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
from typing import Any, Callable


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def download(url: str, path: Path) -> None:
    if not url.startswith("https://"):
        raise RuntimeError("reference URL must be HTTPS")
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-hero-reference/2.0"})
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


def flux_edit(reference: Path, prompt: str, steps: int = 20) -> tuple[Path, dict[str, Any]]:
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
        steps,
        api_name=endpoint,
    )
    path = local_image_path(result)
    if not path:
        raise RuntimeError(f"FLUX Kontext returned no materialized image: {type(result).__name__}")
    return path, {"space": space, "endpoint": endpoint, "steps": steps, "seed": 20260831}


def qwen_fast_edit(reference: Path, prompt: str) -> tuple[Path, dict[str, Any]]:
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
        "cartoon, anime, illustration, mascot, toy, malformed anatomy, extra limbs, desk, office, text",
        8,
        1.0,
        api_name=endpoint,
    )
    path = local_image_path(result)
    if not path:
        raise RuntimeError(f"Fast Qwen returned no materialized image: {type(result).__name__}")
    return path, {"space": space, "endpoint": endpoint, "steps": 8, "seed": 20260831}


def validate_image(path: Path, stage: str) -> dict[str, Any]:
    from PIL import Image
    if not path.exists() or path.stat().st_size < 20_000:
        raise RuntimeError(f"{stage} image missing/suspiciously small: {path.stat().st_size if path.exists() else 0}")
    with Image.open(path) as im:
        width, height = im.size
        if min(width, height) < 700:
            raise RuntimeError(f"{stage} image resolution too low: {width}x{height}")
        return {"bytes": path.stat().st_size, "width": width, "height": height, "format": im.format}


def run_stage(
    stage: str,
    source: Path,
    prompt: str,
    destination: Path,
    report: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    providers: list[tuple[str, Callable[[Path, str], tuple[Path, dict[str, Any]]]]] = [
        ("flux-kontext-official-zero", lambda p, q: flux_edit(p, q, 20)),
        ("qwen-image-edit-2509-fast-zero", qwen_fast_edit),
    ]
    for provider_name, fn in providers:
        attempt = {"stage": stage, "provider": provider_name, "startedAt": now()}
        report["attempts"].append(attempt)
        try:
            produced, metadata = fn(source, prompt)
            shutil.copy2(produced, destination)
            gate = validate_image(destination, stage)
            attempt.update({"status": "success", "gate": gate, "metadata": metadata, "finishedAt": now()})
            return provider_name, gate
        except Exception as exc:
            attempt.update({"status": "failed-free-provider", "reason": str(exc)[:1500], "finishedAt": now()})
            print(f"::warning::{stage}/{provider_name} failed: {exc}", flush=True)
    raise RuntimeError(f"all free providers failed for stage {stage}; paid fallback disabled")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference-url", required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    args = ap.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    original = args.output_dir / "approved-master-reference.png"
    anatomy = args.output_dir / "police_dog.stage1.anatomy.png"
    output = args.output_dir / "police_dog.hero_reference.fullbody.png"
    report_path = args.output_dir / "hero-reference-report.json"
    report: dict[str, Any] = {
        "startedAt": now(),
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "productionReady": False,
        "attempts": [],
        "productionGate": "CLOSED",
    }
    download(args.reference_url, original)

    anatomy_prompt = (
        "Extract and reinterpret ONLY the central K9 officer as a real photorealistic German Shepherd. Remove the complete office, desk, chair, monitor, phone, people, signs and text. "
        "Create one clean full-body real German Shepherd on a seamless light-gray studio background, all four legs and paws plus tail fully visible, realistic black-and-tan coat, individual fur strands, realistic eyes and nose, no costume except the existing collar/badge identity cue. "
        "No illustration, anime, cartoon, mascot or toy. Center the dog and keep the entire body uncropped. This is a neutral high-fidelity anatomy/identity base for the next character-design stage."
    )
    officer_prompt = (
        "Keep this exact realistic German Shepherd head, ears, muzzle, eyes, coat colors and fur identity. Transform the body into a believable CINEMATIC PHOTOREALISTIC anthropomorphic K9 police officer designed for a real-time 3D character. "
        "The character stands upright on two digitigrade canine legs in a neutral A-pose, with two anatomically plausible furred arms ending in articulated canine-like hands/paws capable of holding a phone, pen and file. Do not make it cartoonish or mascot-like. "
        "Dress the full torso and arms in a fitted dark navy professional police uniform matching the original reference: realistic fabric weave, collar, shoulder patches, chest badge, nameplate, belt and subtle K9 insignia; no readable fake text. "
        "Head remains a fully realistic German Shepherd with individual fur strands and natural canine facial anatomy. Full body visible from ears to feet, tail visible, arms slightly separated from torso, hands visible, legs separated, symmetric neutral stance for biped skeletal rigging and facial rigging. "
        "Clean seamless neutral light-gray studio background, even soft front/side lighting, minimal floor shadow, no office, no props, no text, no extra objects. High-end VFX character reference, realistic materials and proportions."
    )

    try:
        stage1_provider, stage1_gate = run_stage("anatomy-base", original, anatomy_prompt, anatomy, report)
        stage2_provider, stage2_gate = run_stage("k9-officer-biped", anatomy, officer_prompt, output, report)
        report.update({
            "stage1Provider": stage1_provider,
            "stage2Provider": stage2_provider,
            "anatomyBase": anatomy.name,
            "winnerOutput": output.name,
            "finalGate": stage2_gate,
        })
    except Exception as exc:
        report["fatal"] = str(exc)[:2000]
        report["finishedAt"] = now()
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"::error::{exc}")
        return 2

    report["finishedAt"] = now()
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"FREE_HERO_REFERENCE={output}")
    print(f"STAGE1_PROVIDER={stage1_provider}")
    print(f"STAGE2_PROVIDER={stage2_provider}")
    print("Production gate remains CLOSED pending visual inspection and 3D validation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
