#!/usr/bin/env python3
"""Free-first 3D candidate orchestrator.

The orchestrator walks runtime3d/free_provider_pool.json in priority order.
It only executes providers explicitly marked automatedInGithub=true, never invokes a
paid provider, and stops on the first structurally valid GLB candidate. On later runs,
if the first provider has exhausted its normal free quota or is unavailable, the
exception is recorded and the next free provider is attempted.

A structurally valid candidate is NOT production-ready. The cinematic identity/PBR/
rig/animation/device acceptance gates remain mandatory.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
import traceback
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from inspect_glb import read_glb_json


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def load_registry(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not data.get("freeOnly") or not data.get("stopBeforePaid"):
        raise RuntimeError("Provider registry must be freeOnly=true and stopBeforePaid=true")
    providers = data.get("providers") or []
    if not providers:
        raise RuntimeError("Provider registry contains no providers")
    return data


def download_reference(url: str, destination: Path) -> None:
    if not url.startswith("https://"):
        raise RuntimeError("Reference URL must use HTTPS")
    destination.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-free-provider-pool/1.0"})
    with urllib.request.urlopen(req, timeout=90) as response, destination.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    if destination.stat().st_size < 10_000:
        raise RuntimeError(f"Reference image is unexpectedly small: {destination.stat().st_size} bytes")


def _existing_glb(value: Any) -> Path | None:
    """Find a materialized .glb path in nested Gradio return objects."""
    if value is None:
        return None
    if isinstance(value, Path):
        return value if value.suffix.lower() == ".glb" and value.exists() else None
    if isinstance(value, str):
        candidate = Path(value)
        return candidate if candidate.suffix.lower() == ".glb" and candidate.exists() else None
    if isinstance(value, dict):
        # Gradio updates/file objects may nest the real path under these keys.
        for key in ("path", "value", "name", "file", "data"):
            if key in value:
                found = _existing_glb(value[key])
                if found:
                    return found
        for nested in value.values():
            found = _existing_glb(nested)
            if found:
                return found
        return None
    if isinstance(value, (tuple, list)):
        for nested in value:
            found = _existing_glb(nested)
            if found:
                return found
    return None


def _client(space: str):
    from gradio_client import Client

    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    return Client(space, **kwargs)


def _endpoint(client: Any, needle: str) -> str:
    info = client.view_api(print_info=False, return_format="dict")
    named = info.get("named_endpoints") or {}
    endpoint = next((name for name in named if needle in name), None)
    if not endpoint:
        raise RuntimeError(f"Space does not expose {needle}; endpoints={list(named)}")
    return endpoint


def run_hf_hunyuan21(provider: dict[str, Any], reference: Path) -> tuple[Path, dict[str, Any]]:
    from gradio_client import handle_file

    client = _client(str(provider["space"]))
    endpoint = _endpoint(client, "shape_generation")
    result = client.predict(
        None,
        handle_file(str(reference)),
        None,
        None,
        None,
        None,
        30,
        7.5,
        1234,
        384,
        True,
        200000,
        False,
        api_name=endpoint,
    )
    path = _existing_glb(result)
    if not path:
        raise RuntimeError(f"Hunyuan3D-2.1 returned no materialized GLB: {type(result).__name__}")
    return path, {
        "space": provider["space"],
        "endpoint": endpoint,
        "inferenceSteps": 30,
        "guidanceScale": 7.5,
        "seed": 1234,
        "octreeResolution": 384,
        "textured": False,
    }


def run_hf_hunyuan2(provider: dict[str, Any], reference: Path) -> tuple[Path, dict[str, Any]]:
    from gradio_client import handle_file

    client = _client(str(provider["space"]))
    endpoint = _endpoint(client, "shape_generation")
    result = client.predict(
        None,
        handle_file(str(reference)),
        30,
        7.5,
        1234,
        256,
        True,
        api_name=endpoint,
    )
    path = _existing_glb(result)
    if not path:
        raise RuntimeError(f"Hunyuan3D-2 returned no materialized GLB: {type(result).__name__}")
    return path, {
        "space": provider["space"],
        "endpoint": endpoint,
        "inferenceSteps": 30,
        "guidanceScale": 7.5,
        "seed": 1234,
        "octreeResolution": 256,
        "textured": False,
    }


ADAPTERS = {
    "hf_hunyuan21_shape": run_hf_hunyuan21,
    "hf_hunyuan2_shape": run_hf_hunyuan2,
}


def structural_gate(path: Path, registry: dict[str, Any]) -> dict[str, int]:
    floor = registry.get("qualityFloor") or {}
    min_bytes = int(floor.get("minimumGlbBytes", 102400))
    min_vertices = int(floor.get("minimumHeroVertices", 10000))
    size = path.stat().st_size
    if size < min_bytes:
        raise RuntimeError(f"GLB below size floor: {size} < {min_bytes}")

    doc = read_glb_json(path)
    meshes = doc.get("meshes") or []
    accessors = doc.get("accessors") or []
    if not meshes:
        raise RuntimeError("GLB contains no meshes")

    vertex_counts: list[int] = []
    for mesh in meshes:
        for primitive in mesh.get("primitives") or []:
            pos = (primitive.get("attributes") or {}).get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertex_counts.append(int(accessors[pos].get("count") or 0))
    vertices = sum(vertex_counts)
    if vertices < min_vertices:
        raise RuntimeError(f"GLB below hero geometry floor: vertices={vertices} < {min_vertices}")
    return {"bytes": size, "meshes": len(meshes), "vertices": vertices}


def safe_reason(exc: BaseException) -> str:
    text = str(exc).replace("\n", " ").strip()
    # Do not leak tokens/headers if a dependency includes them in an exception.
    for env_name in ("HF_TOKEN", "TRIPO_API_KEY"):
        secret = os.environ.get(env_name, "")
        if secret:
            text = text.replace(secret, "***")
    return text[:1200]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, default=Path("runtime3d/free_provider_pool.json"))
    parser.add_argument("--reference-url", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    registry = load_registry(args.registry)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    reference = args.output_dir / "approved-master-reference.png"
    report_path = args.output_dir / "provider-pool-report.json"

    report: dict[str, Any] = {
        "startedAt": utc_now(),
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "referenceUrl": args.reference_url,
        "attempts": [],
        "winner": None,
        "productionReady": False,
        "productionGate": "CLOSED",
    }

    try:
        download_reference(args.reference_url, reference)
        report["referenceBytes"] = reference.stat().st_size
    except Exception as exc:
        report["fatal"] = safe_reason(exc)
        report["finishedAt"] = utc_now()
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        raise

    providers = sorted(registry["providers"], key=lambda p: int(p.get("priority", 9999)))
    for provider in providers:
        attempt: dict[str, Any] = {
            "provider": provider.get("id"),
            "class": provider.get("class"),
            "priority": provider.get("priority"),
            "startedAt": utc_now(),
        }
        report["attempts"].append(attempt)

        if not provider.get("automatedInGithub"):
            attempt["status"] = "skipped-not-automated"
            attempt["reason"] = provider.get("notes", "Requires legitimate external free account/compute or manual export")
            attempt["finishedAt"] = utc_now()
            continue

        adapter_name = provider.get("adapter")
        adapter = ADAPTERS.get(str(adapter_name))
        if adapter is None:
            attempt["status"] = "skipped-no-safe-adapter"
            attempt["reason"] = f"No free-only adapter registered for {adapter_name}"
            attempt["finishedAt"] = utc_now()
            continue

        try:
            t0 = time.monotonic()
            source_path, metadata = adapter(provider, reference)
            gate = structural_gate(source_path, registry)
            target = args.output_dir / f"police_dog.{provider['id']}.candidate.glb"
            shutil.copy2(source_path, target)
            candidate_meta = {
                "provider": provider["id"],
                "providerClass": provider.get("class"),
                "costMode": "free-only",
                "sourceImage": args.reference_url,
                "structuralGate": gate,
                "adapterMetadata": metadata,
                "productionReady": False,
                "requiredNextStages": [
                    "visual identity comparison",
                    "authored PBR/fur/uniform finishing",
                    "quadruped and facial rig",
                    "required named animation clips",
                    "Arabic viseme/lip-sync validation",
                    "physical Android acceptance gate"
                ],
            }
            meta_path = target.with_suffix(".json")
            meta_path.write_text(json.dumps(candidate_meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

            attempt.update({
                "status": "success",
                "elapsedSeconds": round(time.monotonic() - t0, 2),
                "candidate": target.name,
                "structuralGate": gate,
                "finishedAt": utc_now(),
            })
            report["winner"] = provider["id"]
            report["winnerCandidate"] = target.name
            break
        except Exception as exc:
            attempt.update({
                "status": "failed-free-provider",
                "reason": safe_reason(exc),
                "exceptionType": type(exc).__name__,
                "finishedAt": utc_now(),
            })
            print(f"::warning::Free provider {provider.get('id')} failed: {attempt['reason']}", flush=True)
            traceback.print_exc()
            continue

    report["finishedAt"] = utc_now()
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    if report["winner"]:
        print(f"FREE_PROVIDER_WINNER={report['winner']}")
        print(f"FREE_PROVIDER_CANDIDATE={report['winnerCandidate']}")
        print("Production gate remains CLOSED.")
        return 0

    print("::error::All currently automated free providers are exhausted/unavailable. Paid fallback is disabled.")
    return 2


if __name__ == "__main__":
    sys.exit(main())
