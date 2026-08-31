#!/usr/bin/env python3
"""Direct Gradio 4.44 call path for Tencent Hunyuan3D-2mini-Turbo.

The Space's /config and /call/generation_all endpoints work while /info currently
returns HTTP 500, which breaks gradio_client discovery. This client deliberately
uses only the documented Gradio upload + call/SSE protocol and a normal HF token.
It is free-tier/candidate-only and never invokes a paid provider.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inspect_glb import read_glb_json

BASE = "https://tencent-hunyuan3d-2mini-turbo.hf.space"
API_NAME = "generation_all"
EXPECTED_SPACE = "tencent/Hunyuan3D-2mini-Turbo"


def auth_headers(token: str | None) -> dict[str, str]:
    h = {"User-Agent": "al-shorti-free-hunyuan-turbo/1.0"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def upload_file(session: requests.Session, path: Path, token: str | None) -> str:
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    with path.open("rb") as handle:
        r = session.post(
            BASE + "/upload",
            headers=auth_headers(token),
            files={"files": (path.name, handle, mime)},
            timeout=90,
        )
    r.raise_for_status()
    data = r.json()
    if not isinstance(data, list) or not data or not isinstance(data[0], str):
        raise RuntimeError(f"Unexpected Gradio upload response: {data!r}")
    return data[0]


def file_data(remote_path: str, local: Path) -> dict[str, Any]:
    return {
        "path": remote_path,
        "url": f"{BASE}/file={urllib.parse.quote(remote_path, safe='/')}",
        "size": local.stat().st_size,
        "orig_name": local.name,
        "mime_type": mimetypes.guess_type(local.name)[0] or "image/webp",
        "is_stream": False,
        "meta": {"_type": "gradio.FileData"},
    }


def call_generation(session: requests.Session, image_fd: dict[str, Any], token: str | None) -> tuple[Any, list[dict[str, str]]]:
    # Input order comes from the live /config dependency for api_name=generation_all:
    # caption,image,mv_front,mv_back,mv_left,mv_right,steps,guidance,seed,
    # octree_resolution,remove_background,num_chunks,randomize_seed.
    payload = {
        "data": [
            None,
            image_fd,
            None,
            None,
            None,
            None,
            5,
            5.0,
            1234,
            256,
            True,
            8000,
            False,
        ]
    }
    headers = auth_headers(token)
    headers["Content-Type"] = "application/json"
    post = session.post(BASE + f"/call/{API_NAME}", headers=headers, json=payload, timeout=60)
    post.raise_for_status()
    event_id = post.json().get("event_id")
    if not event_id:
        raise RuntimeError(f"Direct Gradio call returned no event_id: {post.text[:1000]}")

    stream = session.get(
        BASE + f"/call/{API_NAME}/{event_id}",
        headers=auth_headers(token),
        timeout=240,
        stream=True,
    )
    stream.raise_for_status()
    events: list[dict[str, str]] = []
    current_event: str | None = None
    final_data: Any = None
    for raw in stream.iter_lines(decode_unicode=True):
        if raw is None:
            continue
        line = raw.strip()
        if line.startswith("event:"):
            current_event = line.split(":", 1)[1].strip()
            continue
        if not line.startswith("data:"):
            continue
        data_text = line.split(":", 1)[1].strip()
        events.append({"event": current_event or "", "data": data_text[:1200]})
        if current_event == "error":
            raise RuntimeError(f"Hunyuan Turbo SSE error: {data_text[:1200]}")
        if current_event == "complete":
            try:
                final_data = json.loads(data_text)
            except Exception as exc:
                raise RuntimeError(f"Could not decode complete SSE data: {data_text[:1200]}") from exc
            break
    if final_data is None:
        raise RuntimeError(f"SSE ended without complete event; events={events[-8:]}")
    return final_data, events


def collect_file_candidates(value: Any, out: list[dict[str, Any]], context: str = "root") -> None:
    if isinstance(value, str):
        if ".glb" in value.lower():
            out.append({"context": context, "path": value})
        return
    if isinstance(value, dict):
        p = value.get("path") or value.get("value")
        u = value.get("url")
        if isinstance(p, str) and ".glb" in p.lower():
            out.append({"context": context, "path": p, "url": u if isinstance(u, str) else None})
        elif isinstance(u, str) and ".glb" in u.lower():
            out.append({"context": context, "url": u})
        for k, v in value.items():
            if k not in {"path", "url"}:
                collect_file_candidates(v, out, context + "." + str(k))
        return
    if isinstance(value, (list, tuple)):
        for i, v in enumerate(value):
            collect_file_candidates(v, out, context + f"[{i}]")


def download_candidate(session: requests.Session, candidate: dict[str, Any], destination: Path, token: str | None) -> None:
    headers = auth_headers(token)
    urls: list[str] = []
    u = candidate.get("url")
    p = candidate.get("path")
    if isinstance(u, str) and u.startswith("http"):
        urls.append(u)
    if isinstance(p, str):
        if p.startswith("http"):
            urls.append(p)
        else:
            urls.append(BASE + "/file=" + urllib.parse.quote(p, safe="/"))
    errors: list[str] = []
    for url in urls:
        try:
            with session.get(url, headers=headers, timeout=120, stream=True, allow_redirects=True) as r:
                if r.status_code != 200:
                    errors.append(f"{urllib.parse.urlparse(url).path}:HTTP{r.status_code}")
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                with destination.open("wb") as handle:
                    for chunk in r.iter_content(1024 * 1024):
                        if chunk:
                            handle.write(chunk)
            if destination.is_file() and destination.stat().st_size >= 100_000:
                return
            destination.unlink(missing_ok=True)
        except Exception as exc:
            errors.append(f"{urllib.parse.urlparse(url).path}:{type(exc).__name__}:{exc}")
    raise RuntimeError(f"Unable to retrieve GLB candidate: {errors}")


def inspect_textured_glb(path: Path) -> dict[str, Any]:
    d = read_glb_json(path)
    accessors = d.get("accessors") or []
    meshes = d.get("meshes") or []
    vertices = 0
    triangles = 0
    for mesh in meshes:
        for prim in mesh.get("primitives") or []:
            attrs = prim.get("attributes") or {}
            pos = attrs.get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertices += int(accessors[pos].get("count") or 0)
            ind = prim.get("indices")
            if isinstance(ind, int) and 0 <= ind < len(accessors):
                triangles += int(accessors[ind].get("count") or 0) // 3
    mats = d.get("materials") or []
    textures = d.get("textures") or []
    images = d.get("images") or []
    channels: set[str] = set()
    for mat in mats:
        pbr = mat.get("pbrMetallicRoughness") or {}
        if pbr.get("baseColorTexture") is not None:
            channels.add("baseColorTexture")
        if pbr.get("metallicRoughnessTexture") is not None:
            channels.add("metallicRoughnessTexture")
        if mat.get("normalTexture") is not None:
            channels.add("normalTexture")
    result = {
        "bytes": path.stat().st_size,
        "meshes": len(meshes),
        "vertices": vertices,
        "triangles": triangles,
        "materials": len(mats),
        "textures": len(textures),
        "images": len(images),
        "pbrChannels": sorted(channels),
        "heroGeometryEligible": vertices >= 100_000,
    }
    if not meshes or vertices < 10_000:
        raise RuntimeError(f"Donor geometry floor failed: {result}")
    if not mats or not textures or not images or "baseColorTexture" not in channels:
        raise RuntimeError(f"Textured donor PBR gate failed: {result}")
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    args = ap.parse_args()
    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Reference missing or too small")

    token = os.environ.get("HF_TOKEN", "").strip() or None
    if not token:
        raise SystemExit("HF_TOKEN is required so the run uses the legitimate authenticated free quota")

    report: dict[str, Any] = {
        "provider": EXPECTED_SPACE,
        "transport": "direct-gradio-call-bypass-broken-info",
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "donorOnly": True,
        "productionReady": False,
        "productionGate": "CLOSED",
        "settings": {"steps": 5, "guidance": 5.0, "seed": 1234, "octreeResolution": 256, "removeBackground": True, "numChunks": 8000},
    }
    t0 = time.monotonic()
    with requests.Session() as session:
        remote = upload_file(session, args.reference, token)
        report["uploadedReferencePath"] = remote
        final_data, events = call_generation(session, file_data(remote, args.reference), token)
        report["sseEvents"] = events[-12:]
        candidates: list[dict[str, Any]] = []
        collect_file_candidates(final_data, candidates)
        report["returnedGlbCandidates"] = candidates
        if not candidates:
            raise RuntimeError(f"generation_all returned no GLB file: {str(final_data)[:2000]}")

        # generation_all returns white GLB first and textured GLB second. Prefer a candidate
        # whose context/path explicitly says texture, otherwise prefer the last GLB returned.
        ordered = sorted(
            candidates,
            key=lambda c: ("textur" in json.dumps(c, ensure_ascii=False).lower(), candidates.index(c)),
            reverse=True,
        )
        last_error: Exception | None = None
        for candidate in ordered:
            try:
                download_candidate(session, candidate, args.output, token)
                gate = inspect_textured_glb(args.output)
                report["selectedCandidate"] = candidate
                report["structuralGate"] = gate
                break
            except Exception as exc:
                last_error = exc
                args.output.unlink(missing_ok=True)
        else:
            raise RuntimeError(f"No returned GLB passed the PBR gate: {last_error}")

    report["elapsedSeconds"] = round(time.monotonic() - t0, 2)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print("HUNYUAN_TURBO_DIRECT_TEXTURED_DONOR_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
