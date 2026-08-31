#!/usr/bin/env python3
"""Generate a fail-closed TRELLIS.2 hero-shape candidate through Hugging Face ZeroGPU.

This client intentionally has no paid/provider fallback. It keeps one gradio_client session so the
Space's hidden gr.State produced by image_to_3d can be consumed by extract_glb.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Any

import requests
from gradio_client import Client, handle_file

SPACE = "microsoft/TRELLIS.2"
SPACE_BASE = "https://microsoft-trellis-2.hf.space"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--reference", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--resolution", choices=("512", "1024", "1536"), default="1024")
    p.add_argument("--decimation-target", type=int, default=300_000)
    p.add_argument("--texture-size", type=int, choices=(1024, 2048, 4096), default=2048)
    p.add_argument("--seed", type=int, default=42)
    return p.parse_args()


def make_client(token: str) -> Client:
    # gradio_client historically used hf_token; retain compatibility if the installed version
    # switches the public keyword to token.
    try:
        return Client(SPACE, hf_token=token, verbose=True)
    except TypeError:
        return Client(SPACE, token=token, verbose=True)  # type: ignore[call-arg]


def view_api(client: Client) -> dict[str, Any]:
    attempts = (
        {"all_endpoints": True, "print_info": False, "return_format": "dict"},
        {"print_info": False, "return_format": "dict"},
        {"return_format": "dict"},
    )
    for kwargs in attempts:
        try:
            value = client.view_api(**kwargs)
            if isinstance(value, dict):
                return value
        except TypeError:
            continue
        except Exception:
            break
    return {}


def collect_endpoint_names(value: Any) -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str) and key.startswith("/"):
                found.append(key)
            found.extend(collect_endpoint_names(child))
    elif isinstance(value, (list, tuple)):
        for child in value:
            found.extend(collect_endpoint_names(child))
    return list(dict.fromkeys(found))


def resolve_endpoint(api: dict[str, Any], preferred: str, keyword: str) -> str:
    names = collect_endpoint_names(api)
    if preferred in names or not names:
        return preferred
    keyword = keyword.lower()
    matches = [name for name in names if keyword in name.lower()]
    if matches:
        matches.sort(key=lambda name: (len(name), name))
        return matches[0]
    return preferred


def flatten(value: Any) -> list[Any]:
    if isinstance(value, dict):
        out: list[Any] = []
        for child in value.values():
            out.extend(flatten(child))
        return out
    if isinstance(value, (list, tuple)):
        out: list[Any] = []
        for child in value:
            out.extend(flatten(child))
        return out
    return [value]


def find_glb_candidate(value: Any) -> str | None:
    for item in flatten(value):
        if isinstance(item, Path) and item.suffix.lower() == ".glb":
            return str(item)
        if isinstance(item, str) and ".glb" in item.lower():
            return item
    return None


def materialize_glb(candidate: str, output: Path, token: str) -> dict[str, Any]:
    output.parent.mkdir(parents=True, exist_ok=True)
    source = Path(candidate)
    if source.is_file():
        shutil.copy2(source, output)
        return {"source": str(source), "transport": "gradio-client-download"}

    url = candidate
    if candidate.startswith("/file="):
        url = SPACE_BASE + candidate
    elif candidate.startswith("file="):
        url = SPACE_BASE + "/" + candidate
    elif candidate.startswith("/"):
        url = SPACE_BASE + candidate
    elif not candidate.startswith(("http://", "https://")):
        # Gradio file paths are normally downloaded by gradio_client. Keep this explicit error
        # rather than guessing a remote filesystem URL.
        raise RuntimeError(f"TRELLIS returned unresolved GLB path: {candidate}")

    with requests.get(
        url,
        headers={"Authorization": f"Bearer {token}"},
        stream=True,
        timeout=(20, 240),
        allow_redirects=True,
    ) as response:
        response.raise_for_status()
        with output.open("wb") as fh:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    fh.write(chunk)
    return {"source": url, "transport": "authenticated-http"}


def optional_preprocess(client: Client, reference: Path, api_name: str) -> Any:
    try:
        value = client.predict(handle_file(str(reference)), api_name=api_name)
        # Gradio Image outputs normally arrive as a downloaded local path. If not, leave the
        # exact source image in place rather than failing generation only because preprocessing
        # is hidden by a Space-version API change.
        if isinstance(value, (str, Path)) and Path(str(value)).is_file():
            return handle_file(str(value))
    except Exception as exc:
        print(f"TRELLIS_PREPROCESS_OPTIONAL_WARNING={type(exc).__name__}:{exc}", flush=True)
    return handle_file(str(reference))


def write_report(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    token = os.environ.get("HF_TOKEN", "").strip()
    if not token:
        raise SystemExit("HF_TOKEN is required; paid fallback is forbidden")
    if not args.reference.is_file() or args.reference.stat().st_size < 1024:
        raise SystemExit(f"Reference image missing/too small: {args.reference}")
    if not 100_000 <= args.decimation_target <= 500_000:
        raise SystemExit("decimation target must be within TRELLIS.2 UI contract 100k..500k")

    started = time.time()
    report: dict[str, Any] = {
        "provider": "microsoft/TRELLIS.2",
        "transport": "hugging-face-zerogpu-gradio",
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "productionReady": False,
        "manifestActivation": False,
        "reference": args.reference.name,
        "referenceBytes": args.reference.stat().st_size,
        "resolution": args.resolution,
        "decimationTarget": args.decimation_target,
        "textureSize": args.texture_size,
        "seed": args.seed,
        "productionGate": "CLOSED",
    }

    try:
        client = make_client(token)
        api = view_api(client)
        endpoint_names = collect_endpoint_names(api)
        preprocess_api = resolve_endpoint(api, "/preprocess_image", "preprocess")
        generate_api = resolve_endpoint(api, "/image_to_3d", "image_to_3d")
        extract_api = resolve_endpoint(api, "/extract_glb", "extract_glb")
        report["apiEndpoints"] = endpoint_names
        report["selectedEndpoints"] = {
            "preprocess": preprocess_api,
            "generate": generate_api,
            "extract": extract_api,
        }
        print(json.dumps(report["selectedEndpoints"], indent=2), flush=True)

        image_input = optional_preprocess(client, args.reference, preprocess_api)

        # Exact defaults from the current TRELLIS.2 Space advanced controls. We deliberately
        # disable UI seed randomization by invoking image_to_3d directly with a fixed seed.
        preview_result = client.predict(
            image_input,
            args.seed,
            args.resolution,
            7.5, 0.7, 12, 5.0,
            7.5, 0.5, 12, 3.0,
            1.0, 0.0, 12, 3.0,
            api_name=generate_api,
        )
        report["previewResultType"] = type(preview_result).__name__
        print(f"TRELLIS_IMAGE_TO_3D=PASS resultType={type(preview_result).__name__}", flush=True)

        # output_buf is gr.State. gradio_client keeps State within this Client session, so the
        # public extract endpoint receives only the two visible controls below.
        extract_result = client.predict(
            args.decimation_target,
            args.texture_size,
            api_name=extract_api,
        )
        report["extractResultType"] = type(extract_result).__name__
        candidate = find_glb_candidate(extract_result)
        if not candidate:
            report["extractResultPreview"] = repr(extract_result)[:2000]
            raise RuntimeError("TRELLIS extract endpoint returned no GLB")

        transfer = materialize_glb(candidate, args.output, token)
        if not args.output.is_file() or args.output.stat().st_size < 100_000:
            raise RuntimeError("TRELLIS GLB missing or unexpectedly small")
        report.update(transfer)
        report["output"] = args.output.name
        report["outputBytes"] = args.output.stat().st_size
        report["elapsedSeconds"] = round(time.time() - started, 3)
        report["generationGate"] = "PASS"
        write_report(args.report, report)
        print(json.dumps(report, indent=2, ensure_ascii=False), flush=True)
        print("TRELLIS2_FREE_SHAPE_GENERATION_GATE=PASS", flush=True)
        print("PRODUCTION_GATE=CLOSED", flush=True)
        return 0
    except Exception as exc:
        report["generationGate"] = "FAIL"
        report["errorType"] = type(exc).__name__
        report["error"] = str(exc)
        report["elapsedSeconds"] = round(time.time() - started, 3)
        write_report(args.report, report)
        print(json.dumps(report, indent=2, ensure_ascii=False), flush=True)
        raise


if __name__ == "__main__":
    raise SystemExit(main())
