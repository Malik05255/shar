#!/usr/bin/env python3
"""Generate a real runtime3d hero candidate through the current Tripo OpenAPI.

This intentionally writes a candidate artifact, not production police_dog.glb.
Production activation remains gated by runtime3d/inspect_glb.py and the physical-device
acceptance contract in CONTENT_PACK_SPEC.md.

Required environment:
  TRIPO_API_KEY
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
from urllib.parse import urlparse

BASE = "https://api.tripo3d.ai/v2/openapi"
TERMINAL_FAILURES = {"failed", "cancelled", "banned", "expired", "unknown"}


def request_json(method: str, path: str, api_key: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": "al-shorti-runtime3d/1.1",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        trace = exc.headers.get("X-Tripo-Trace-ID", "")
        suffix = f" trace={trace}" if trace else ""
        raise RuntimeError(f"Tripo HTTP {exc.code} for {path}:{suffix} {detail[:1000]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Tripo network error for {path}: {exc}") from exc

    parsed = json.loads(body)
    if parsed.get("code", 0) != 0:
        raise RuntimeError(f"Tripo API error for {path}: {parsed}")
    return parsed.get("data") or {}


def create_task(api_key: str, payload: dict) -> str:
    data = request_json("POST", "/task", api_key, payload)
    task_id = data.get("task_id")
    if not task_id:
        raise RuntimeError(f"Tripo did not return task_id for {payload.get('type')}: {data}")
    print(f"created {payload.get('type')}: {task_id}", flush=True)
    return str(task_id)


def poll(task_id: str, api_key: str, timeout_seconds: int = 1200) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_state = None
    while time.monotonic() < deadline:
        data = request_json("GET", f"/task/{task_id}", api_key)
        status = str(data.get("status", "")).lower()
        progress = data.get("progress")
        state = (status, progress)
        if state != last_state:
            print(f"{task_id}: status={status or '?'} progress={progress}", flush=True)
            last_state = state
        if status == "success":
            return data
        if status in TERMINAL_FAILURES:
            raise RuntimeError(
                f"Tripo task {task_id} {status}: "
                f"{data.get('error_code')} {data.get('error_message')}"
            )
        time.sleep(3)
    raise TimeoutError(f"Tripo task {task_id} exceeded {timeout_seconds}s")


def image_type(url: str) -> str:
    suffix = Path(urlparse(url).path).suffix.lower()
    if suffix in {".jpg", ".jpeg"}:
        return "jpg"
    return "png"


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-runtime3d/1.1"})
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


def task_credit(task: dict) -> object:
    output = task.get("output") or {}
    return output.get("consumed_credit")


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

    # Current official image_to_model task. Preserve high visual fidelity and PBR.
    generation_id = create_task(
        api_key,
        {
            "type": "image_to_model",
            "model_version": "v3.1-20260211",
            "file": {
                "type": image_type(args.image_url),
                "url": args.image_url,
            },
            "texture": True,
            "pbr": True,
            "texture_quality": "detailed",
            "geometry_quality": "detailed",
            "quad": True,
        },
    )
    generated = poll(generation_id, api_key)

    # Compatibility gate before rigging. This tells us whether the result is riggable
    # and which anatomy class (quadruped/biped/etc.) the rig service recognized.
    check_id = create_task(
        api_key,
        {
            "type": "animate_prerigcheck",
            "original_model_task_id": generation_id,
        },
    )
    checked = poll(check_id, api_key)
    check_output = checked.get("output") or {}
    if not check_output.get("riggable"):
        raise RuntimeError(f"Generated hero is not riggable: {check_output}")

    rig_type = str(check_output.get("rig_type") or "").strip()
    if not rig_type:
        raise RuntimeError(f"Pre-rig check returned no rig_type: {check_output}")
    topology = str(check_output.get("topology") or "").strip()
    print(f"pre-rig accepted hero as {rig_type}; topology={topology or '?'}", flush=True)

    rig_payload = {
        "type": "animate_rig",
        "original_model_task_id": generation_id,
        "model_version": "v2.5-20260210",
        "rig_type": rig_type,
        "spec": "tripo",
        "out_format": "glb",
    }
    if topology in {"bip", "quad"}:
        rig_payload["topology"] = topology

    rig_id = create_task(api_key, rig_payload)
    rigged = poll(rig_id, api_key)
    output = rigged.get("output") or {}
    model_url = output.get("model") or output.get("pbr_model") or output.get("base_model")
    if not model_url:
        raise RuntimeError(f"Rig task returned no model URL: {rigged}")

    # Task asset URLs may be short-lived, so download immediately.
    download(str(model_url), args.output)

    metadata_path = args.metadata or args.output.with_suffix(".json")
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata = {
        "sourceImage": args.image_url,
        "generationTask": generation_id,
        "preRigCheckTask": check_id,
        "rigTask": rig_id,
        "rigType": rig_type,
        "topology": topology or None,
        "generationCredits": task_credit(generated),
        "preRigCheckCredits": task_credit(checked),
        "rigCredits": task_credit(rigged),
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
