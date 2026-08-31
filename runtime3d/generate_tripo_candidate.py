#!/usr/bin/env python3
"""Generate a real runtime3d hero candidate through Tripo v3.

This intentionally writes a candidate artifact, not production police_dog.glb.
Production activation remains gated by runtime3d/inspect_glb.py and the physical-device
acceptance contract in CONTENT_PACK_SPEC.md.

Required environment:
  TRIPO_API_KEY

Example:
  python3 runtime3d/generate_tripo_candidate.py \
    --image-url https://example.com/master.png \
    --output runtime3d-candidate/police_dog.rigged.glb
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://openapi.tripo3d.com/v3"
TERMINAL_FAILURES = {"failed", "cancelled"}


def request_json(method: str, path: str, api_key: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": "al-shorti-runtime3d/1.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Tripo HTTP {exc.code} for {path}: {detail[:1000]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Tripo network error for {path}: {exc}") from exc

    parsed = json.loads(body)
    if parsed.get("code", 0) != 0:
        raise RuntimeError(f"Tripo API error for {path}: {parsed}")
    return parsed.get("data") or {}


def create_task(path: str, api_key: str, payload: dict) -> str:
    data = request_json("POST", path, api_key, payload)
    task_id = data.get("task_id")
    if not task_id:
        raise RuntimeError(f"Tripo did not return task_id for {path}: {data}")
    print(f"created {path}: {task_id}", flush=True)
    return str(task_id)


def poll(task_id: str, api_key: str, timeout_seconds: int = 1200) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_progress = None
    while time.monotonic() < deadline:
        data = request_json("GET", f"/tasks/{task_id}", api_key)
        status = str(data.get("status", "")).lower()
        progress = data.get("progress")
        if progress != last_progress:
            print(f"{task_id}: status={status or '?'} progress={progress}", flush=True)
            last_progress = progress
        if status == "success":
            return data
        if status in TERMINAL_FAILURES:
            raise RuntimeError(
                f"Tripo task {task_id} {status}: "
                f"{data.get('error_code')} {data.get('error_message')}"
            )
        time.sleep(3)
    raise TimeoutError(f"Tripo task {task_id} exceeded {timeout_seconds}s")


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-runtime3d/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=120) as response, destination.open("wb") as handle:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
    except Exception:
        destination.unlink(missing_ok=True)
        raise
    if destination.stat().st_size < 1024:
        raise RuntimeError(f"Downloaded GLB is unexpectedly small: {destination.stat().st_size} bytes")
    print(f"downloaded {destination} ({destination.stat().st_size} bytes)", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path)
    args = parser.parse_args()

    api_key = os.environ.get("TRIPO_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("TRIPO_API_KEY is required")
    if not args.image_url.startswith("https://"):
        raise SystemExit("--image-url must be a public HTTPS image")

    # Highest-fidelity H-series path. No low-poly mode is used for the hero.
    generation_id = create_task(
        "/generation/image-to-model",
        api_key,
        {
            "input": args.image_url,
            "model": "v3.1-20260211",
            "texture": True,
            "pbr": True,
            "texture_quality": "detailed",
            "geometry_quality": "detailed",
            "quad": True,
            "orientation": "align_image",
        },
    )
    generated = poll(generation_id, api_key)

    # Free compatibility gate before consuming rigging credits.
    check_id = create_task("/animations/rig-check", api_key, {"input": generation_id})
    checked = poll(check_id, api_key)
    check_output = checked.get("output") or {}
    if not check_output.get("riggable"):
        raise RuntimeError(f"Generated hero is not riggable: {check_output}")

    rig_type = str(check_output.get("rig_type") or "").strip()
    if not rig_type:
        raise RuntimeError(f"Rig check returned no rig_type: {check_output}")
    rig_model = "v1.0-20240301" if rig_type == "biped" else "v2.5-20260210"
    print(f"rig-check accepted hero as {rig_type}; rig model={rig_model}", flush=True)

    rig_id = create_task(
        "/animations/rig",
        api_key,
        {
            "input": generation_id,
            "model": rig_model,
            "rig_type": rig_type,
            "spec": "tripo",
            "out_format": "glb",
        },
    )
    rigged = poll(rig_id, api_key)
    output = rigged.get("output") or {}
    model_url = output.get("model_url")
    if not model_url:
        raise RuntimeError(f"Rig task returned no model_url: {rigged}")

    # Tripo task URLs are short-lived, so download immediately.
    download(str(model_url), args.output)

    metadata_path = args.metadata or args.output.with_suffix(".json")
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata = {
        "sourceImage": args.image_url,
        "generationTask": generation_id,
        "rigCheckTask": check_id,
        "rigTask": rig_id,
        "rigType": rig_type,
        "generationCredits": generated.get("credits_consumed"),
        "rigCredits": rigged.get("credits_consumed"),
        "productionReady": False,
        "reason": (
            "Candidate must still receive the exact authored body/facial animation set and pass "
            "runtime3d/inspect_glb.py plus physical-device visual acceptance before activation."
        ),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote metadata {metadata_path}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
