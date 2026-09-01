#!/usr/bin/env python3
"""Prepare a photorealistic full-body K9 officer reference using free HF Spaces.

Three-stage free pipeline:
1) clean realistic German-Shepherd anatomy/identity base;
2) transform into a photorealistic anthropomorphic K9 police officer;
3) normalize into an uncropped portrait A-pose reference for biped rigging.

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
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-hero-reference/3.0"})
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


def make_portrait_canvas(source: Path, destination: Path) -> dict[str, int]:
    """Place the accepted officer upper body on a tall neutral canvas for outpainting."""
    from PIL import Image
    with Image.open(source).convert("RGB") as im:
        w, h = im.size
        # Keep the central subject, discard empty landscape margins.
        left = int(w * 0.24)
        right = int(w * 0.76)
        crop = im.crop((left, 0, right, h))
        target_w = 860
        scale = target_w / crop.size[0]
        resized = crop.resize((target_w, int(crop.size[1] * scale)), Image.Resampling.LANCZOS)
        canvas = Image.new("RGB", (1024, 1536), (210, 210, 210))
        x = (1024 - resized.size[0]) // 2
        y = 35
        # If necessary keep room below for complete legs/feet.
        if resized.size[1] > 930:
            scale2 = 930 / resized.size[1]
            resized = resized.resize((int(resized.size[0] * scale2), 930), Image.Resampling.LANCZOS)
            x = (1024 - resized.size[0]) // 2
        canvas.paste(resized, (x, y))
        canvas.save(destination, "PNG")
    return {"width": 1024, "height": 1536}


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
    officer = args.output_dir / "police_dog.stage2.officer.png"
    portrait_canvas = args.output_dir / "police_dog.stage3.outpaint_canvas.png"
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
        "Stand upright on two digitigrade canine legs, with two anatomically plausible furred arms and articulated canine-like hands/paws. Dress the full torso and arms in a fitted dark navy professional police uniform with realistic fabric, collar, shoulder patches, chest badge, blank nameplate, belt and subtle K9 insignia. "
        "Head remains fully realistic German Shepherd. No cartoon, mascot or illustration. Keep the subject isolated on a neutral light-gray studio background. Do not add scenery."
    )
    rig_prompt = (
        "Use the existing K9 officer as the exact identity and costume reference. COMPLETE AND NORMALIZE the character into a full-body rigging reference. "
        "Remove the tablet/device and any held prop. Both hands must be empty and fully visible. Put the character in a neutral symmetric A-pose: upright biped torso, arms about 30 degrees away from the body, elbows nearly straight, palms/paws relaxed and visible, head facing directly forward. "
        "Extend and reconstruct the entire lower body so BOTH digitigrade canine legs and feet are completely visible with clear separation; show the tail behind and to one side. Keep realistic German Shepherd head/fur and the same fitted navy police uniform, trousers, belt, blank patches/nameplate and metal badge. "
        "Do not crop ears, hands, tail or feet. Leave clear gray margin around the complete silhouette. Remove all fake readable text, screens and props. Photorealistic VFX character turnaround quality, neutral seamless gray studio background, even lighting, no dramatic shadow, no cartoon or mascot styling."
    )

    try:
        stage1_provider, stage1_gate = run_stage("anatomy-base", original, anatomy_prompt, anatomy, report)
        stage2_provider, stage2_gate = run_stage("k9-officer-biped", anatomy, officer_prompt, officer, report)
        canvas_meta = make_portrait_canvas(officer, portrait_canvas)
        report["outpaintCanvas"] = canvas_meta
        stage3_provider, stage3_gate = run_stage("rig-reference-fullbody", portrait_canvas, rig_prompt, output, report)
        report.update({
            "stage1Provider": stage1_provider,
            "stage2Provider": stage2_provider,
            "stage3Provider": stage3_provider,
            "anatomyBase": anatomy.name,
            "officerBase": officer.name,
            "winnerOutput": output.name,
            "finalGate": stage3_gate,
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
    print(f"STAGE3_PROVIDER={stage3_provider}")
    print("Production gate remains CLOSED pending visual inspection and 3D validation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
