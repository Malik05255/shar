#!/usr/bin/env python3
"""Use aptol/genshin's public free bridge to its Hunyuan backend.

This is candidate-only and free-only. The public Space performs CPU preprocessing,
then its 120s ZeroGPU step calls the owner's backend for model + texture generation.
No paid provider or credit API is used here.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inspect_glb import read_glb_json

SPACE = "aptol/genshin"
SPACE_HOST = "https://aptol-genshin.hf.space"


def safe_text(value: Any) -> str:
    return str(value).replace("\n", " ")[:1600]


def validate_glb(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size < 100_000:
        raise RuntimeError(f"Downloaded candidate is missing/too small: {path}")
    d = read_glb_json(path)
    meshes = d.get("meshes") or []
    mats = d.get("materials") or []
    tex = d.get("textures") or []
    imgs = d.get("images") or []
    accessors = d.get("accessors") or []
    vertices = 0
    triangles = 0
    for mesh in meshes:
        for prim in mesh.get("primitives") or []:
            attrs = prim.get("attributes") or {}
            p = attrs.get("POSITION")
            if isinstance(p, int) and 0 <= p < len(accessors):
                vertices += int(accessors[p].get("count") or 0)
            ind = prim.get("indices")
            if isinstance(ind, int) and 0 <= ind < len(accessors):
                triangles += int(accessors[ind].get("count") or 0) // 3
    channels: set[str] = set()
    for m in mats:
        pbr = m.get("pbrMetallicRoughness") or {}
        if pbr.get("baseColorTexture") is not None:
            channels.add("baseColorTexture")
        if pbr.get("metallicRoughnessTexture") is not None:
            channels.add("metallicRoughnessTexture")
        if m.get("normalTexture") is not None:
            channels.add("normalTexture")
    if not meshes or vertices < 10_000:
        raise RuntimeError(f"Geometry gate failed: meshes={len(meshes)} vertices={vertices}")
    if not mats or not tex or not imgs or "baseColorTexture" not in channels:
        raise RuntimeError(
            f"PBR gate failed: materials={len(mats)} textures={len(tex)} images={len(imgs)} channels={sorted(channels)}"
        )
    return {
        "bytes": path.stat().st_size,
        "meshes": len(meshes),
        "vertices": vertices,
        "triangles": triangles,
        "materials": len(mats),
        "textures": len(tex),
        "images": len(imgs),
        "pbrChannels": sorted(channels),
    }


def try_download_server_path(server_path: str, destination: Path, token: str | None) -> tuple[bool, list[dict[str, Any]]]:
    attempts: list[dict[str, Any]] = []
    raw = server_path.strip()
    if not raw:
        return False, attempts
    p = Path(raw)
    if p.is_file():
        shutil.copy2(p, destination)
        return True, [{"method": "local", "path": raw, "bytes": destination.stat().st_size}]

    headers = {"User-Agent": "al-shorti-free-3d/1.0"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    quoted = urllib.parse.quote(raw, safe="/")
    urls = [
        f"{SPACE_HOST}/gradio_api/file={quoted}",
        f"{SPACE_HOST}/file={quoted}",
    ]
    for url in urls:
        rec: dict[str, Any] = {"method": "gradio-file-route", "urlPath": urllib.parse.urlparse(url).path}
        try:
            with requests.get(url, headers=headers, timeout=90, stream=True, allow_redirects=True) as r:
                rec["status"] = r.status_code
                rec["contentType"] = r.headers.get("content-type")
                if r.status_code == 200:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with destination.open("wb") as f:
                        for chunk in r.iter_content(1024 * 1024):
                            if chunk:
                                f.write(chunk)
                    rec["bytes"] = destination.stat().st_size
                    attempts.append(rec)
                    if destination.stat().st_size >= 100_000:
                        return True, attempts
                    destination.unlink(missing_ok=True)
            attempts.append(rec)
        except Exception as exc:
            rec["error"] = safe_text(exc)
            attempts.append(rec)
    return False, attempts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    args = ap.parse_args()
    if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
        raise SystemExit("Reference is missing or too small")

    from gradio_client import Client, handle_file

    token = os.environ.get("HF_TOKEN", "").strip() or None
    kwargs: dict[str, Any] = {"verbose": False}
    if token:
        kwargs["hf_token"] = token
    client = Client(SPACE, **kwargs)

    report: dict[str, Any] = {
        "provider": "aptol-genshin-free-bridge",
        "space": SPACE,
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "productionReady": False,
        "productionGate": "CLOSED",
        "reference": args.reference.name,
        "attempts": [],
    }

    t0 = time.monotonic()
    cpu = client.predict(
        img=handle_file(str(args.reference)),
        keep_rembg=True,
        do_weaponless=False,
        weapon_terms="",
        api_name="/step1_cpu",
    )
    if not isinstance(cpu, (tuple, list)) or len(cpu) < 2:
        raise RuntimeError(f"step1_cpu returned unexpected result: {type(cpu).__name__}")
    s1_path = str(cpu[1] or "").strip()
    if not s1_path:
        raise RuntimeError("step1_cpu returned no server-side preprocessed path")
    report["step1"] = {"serverPath": s1_path, "elapsedSeconds": round(time.monotonic() - t0, 2)}

    t1 = time.monotonic()
    generated = client.predict(
        space_key="fork (backend)",
        custom_repo="",
        steps=30,
        guidance=7.5,
        seed=1234,
        s1_path=s1_path,
        gender="auto",
        weaponless=False,
        enforce_tpose=False,
        do_texture=True,
        prompt_extra="photorealistic German Shepherd police officer, realistic navy police uniform, full body, production 3D asset",
        api_name="/step2_generate",
    )
    report["step2ElapsedSeconds"] = round(time.monotonic() - t1, 2)
    if not isinstance(generated, (tuple, list)) or len(generated) < 2:
        raise RuntimeError(f"step2_generate returned unexpected result: {type(generated).__name__}")
    model_path = str(generated[0] or "").strip()
    textured_path = str(generated[1] or "").strip()
    log = str(generated[2] if len(generated) > 2 else "")
    report["step2"] = {"modelServerPath": model_path, "texturedServerPath": textured_path, "log": log[:1200]}
    chosen = textured_path or model_path
    if not chosen:
        raise RuntimeError(f"Bridge returned no GLB path; log={log[:500]}")

    ok, downloads = try_download_server_path(chosen, args.output, token)
    report["downloadAttempts"] = downloads
    if not ok:
        # Ask the Space to consume the same server-side paths for its own four/six-view stage.
        # This provides useful diagnostics without fabricating a candidate.
        try:
            review = client.predict(
                glb_model=model_path,
                glb_tex=textured_path,
                add_bs=False,
                do_vrm=False,
                api_name="/step3_all",
            )
            report["step3Diagnostic"] = safe_text(review)
        except Exception as exc:
            report["step3DiagnosticError"] = safe_text(exc)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        raise RuntimeError(f"Generated GLB exists on provider but could not be retrieved through allowed Gradio routes: {chosen}")

    gate = validate_glb(args.output)
    report["structuralGate"] = gate
    report["elapsedSeconds"] = round(time.monotonic() - t0, 2)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print("APTOL_HUNYUAN_TEXTURED_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
