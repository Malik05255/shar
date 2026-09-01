#!/usr/bin/env python3
"""Free-first 3D candidate orchestrator with automatic provider failover.

Only providers marked automatedInGithub=true are invoked. Paid fallback is forbidden.
The first provider that produces a structurally valid GLB wins for that run; if a free
quota is exhausted or a provider is unavailable, the next provider is attempted.
Production activation remains gated by visual identity/PBR/rig/animation/device checks.
"""

from __future__ import annotations

import argparse
import json
import os
import re
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
    if not data.get("providers"):
        raise RuntimeError("Provider registry contains no providers")
    return data


def download_reference(url: str, destination: Path) -> None:
    if not url.startswith("https://"):
        raise RuntimeError("Reference URL must use HTTPS")
    destination.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "al-shorti-free-provider-pool/1.2"})
    with urllib.request.urlopen(req, timeout=90) as response, destination.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    if destination.stat().st_size < 10_000:
        raise RuntimeError(f"Reference image is unexpectedly small: {destination.stat().st_size} bytes")


def stage_local_reference(source: Path, destination: Path) -> None:
    """Copy an exact artifact-produced reference without a public re-download round trip."""
    if not source.is_file():
        raise RuntimeError(f"Reference file does not exist: {source}")
    if source.stat().st_size < 10_000:
        raise RuntimeError(f"Reference file is unexpectedly small: {source.stat().st_size} bytes")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    if destination.stat().st_size != source.stat().st_size:
        raise RuntimeError("Local reference copy changed byte size")


def _existing_glb(value: Any) -> Path | None:
    if value is None:
        return None
    if isinstance(value, Path):
        return value if value.suffix.lower() == ".glb" and value.exists() else None
    if isinstance(value, str):
        candidate = Path(value)
        return candidate if candidate.suffix.lower() == ".glb" and candidate.exists() else None
    if isinstance(value, dict):
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


def _endpoint_info(client: Any, needle: str) -> tuple[str, dict[str, Any]]:
    info = client.view_api(print_info=False, return_format="dict")
    named = info.get("named_endpoints") or {}
    endpoint = next((name for name in named if needle in name), None)
    if not endpoint:
        raise RuntimeError(f"Space does not expose {needle}; endpoints={list(named)}")
    return endpoint, named[endpoint]


def _sanitize(name: str) -> str:
    name = re.sub(r"\W", "_", name.strip())
    if name and name[0].isdigit():
        name = "_" + name
    return name


def _shape_call(client: Any, endpoint: str, endpoint_info: dict[str, Any], reference: Path, octree: int) -> tuple[Any, list[str]]:
    """Build kwargs from the live Gradio schema instead of assuming input positions."""
    from gradio_client import handle_file

    kwargs: dict[str, Any] = {}
    exposed: list[str] = []
    for parameter in endpoint_info.get("parameters") or []:
        raw_name = str(parameter.get("parameter_name") or parameter.get("label") or "").strip()
        if not raw_name:
            continue
        name = _sanitize(raw_name)
        exposed.append(name)
        lower = name.lower()

        if lower == "image" or lower.endswith("_image") and not lower.startswith("mv_"):
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
            kwargs[name] = octree
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
        raise RuntimeError(f"Shape endpoint exposes no image parameter; parameters={exposed}")

    result = client.predict(api_name=endpoint, **kwargs)
    return result, exposed


def _run_hunyuan(provider: dict[str, Any], reference: Path, octree: int) -> tuple[Path, dict[str, Any]]:
    client = _client(str(provider["space"]))
    endpoint, info = _endpoint_info(client, "shape_generation")
    result, parameters = _shape_call(client, endpoint, info, reference, octree)
    path = _existing_glb(result)
    if not path:
        raise RuntimeError(f"{provider['id']} returned no materialized GLB: {type(result).__name__}")
    return path, {
        "space": provider["space"],
        "endpoint": endpoint,
        "exposedParameters": parameters,
        "inferenceSteps": 30,
        "guidanceScale": 7.5,
        "seed": 1234,
        "octreeResolution": octree,
        "textured": False,
    }


def run_hf_hunyuan21(provider: dict[str, Any], reference: Path) -> tuple[Path, dict[str, Any]]:
    return _run_hunyuan(provider, reference, 384)


def run_hf_hunyuan2(provider: dict[str, Any], reference: Path) -> tuple[Path, dict[str, Any]]:
    return _run_hunyuan(provider, reference, 256)


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
    for env_name in ("HF_TOKEN", "TRIPO_API_KEY"):
        secret = os.environ.get(env_name, "")
        if secret:
            text = text.replace(secret, "***")
    return text[:1200]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, default=Path("runtime3d/free_provider_pool.json"))
    source_group = parser.add_mutually_exclusive_group(required=True)
    source_group.add_argument("--reference-url")
    source_group.add_argument("--reference-file", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    registry = load_registry(args.registry)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    if args.reference_file:
        suffix = args.reference_file.suffix.lower() or ".img"
        reference = args.output_dir / f"hero-reference{suffix}"
        reference_source = f"local-artifact:{args.reference_file.name}"
    else:
        suffix = Path(str(args.reference_url).split("?", 1)[0]).suffix.lower() or ".img"
        reference = args.output_dir / f"hero-reference{suffix}"
        reference_source = str(args.reference_url)

    report_path = args.output_dir / "provider-pool-report.json"
    report: dict[str, Any] = {
        "startedAt": utc_now(),
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "referenceSource": reference_source,
        "attempts": [],
        "winner": None,
        "productionReady": False,
        "productionGate": "CLOSED",
    }

    try:
        if args.reference_file:
            stage_local_reference(args.reference_file, reference)
        else:
            download_reference(str(args.reference_url), reference)
        report["referenceBytes"] = reference.stat().st_size
        report["referenceSuffix"] = reference.suffix.lower()
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

        adapter = ADAPTERS.get(str(provider.get("adapter")))
        if adapter is None:
            attempt["status"] = "skipped-no-safe-adapter"
            attempt["reason"] = f"No free-only adapter registered for {provider.get('adapter')}"
            attempt["finishedAt"] = utc_now()
            continue

        try:
            t0 = time.monotonic()
            source_path, metadata = adapter(provider, reference)
            gate = structural_gate(source_path, registry)
            target = args.output_dir / f"police_dog.{provider['id']}.candidate.glb"
            shutil.copy2(source_path, target)
            target.with_suffix(".json").write_text(
                json.dumps({
                    "provider": provider["id"],
                    "providerClass": provider.get("class"),
                    "costMode": "free-only",
                    "sourceImage": reference_source,
                    "structuralGate": gate,
                    "adapterMetadata": metadata,
                    "productionReady": False,
                    "requiredNextStages": [
                        "visual identity and isolated-silhouette comparison",
                        "authored PBR/fur/uniform finishing",
                        "biped canine and facial rig",
                        "required named animation clips",
                        "Arabic viseme/lip-sync validation",
                        "physical Android acceptance gate"
                    ]
                }, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
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
