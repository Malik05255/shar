#!/usr/bin/env python3
"""Paint an already accepted hero mesh with official Hunyuan3D-Paint 2.1.

This runner intentionally preserves the chosen geometry: use_remesh=False. The official
pipeline creates UVs and PBR textures from the exact hero reference. After inference we
save a second full-resolution 4K texture payload from the live renderer state instead of
keeping only the pipeline's default half-resolution export.

Run from a pinned Tencent-Hunyuan/Hunyuan3D-2.1 checkout on a legitimate free GPU.
Production activation remains outside this script and requires visual/deformation/device QC.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PINNED_HUNYUAN_COMMIT = "82920d643c0dc2f7bfd7255f45f62d386edfe60c"
MIN_VRAM_GIB = 21.0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git_head(repo: Path) -> str | None:
    try:
        return subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    except Exception:
        return None


def read_glb_json(path: Path) -> dict[str, Any]:
    import struct
    raw = path.read_bytes()
    if len(raw) < 20 or raw[:4] != b"glTF":
        raise RuntimeError(f"Not a binary GLB: {path}")
    version, total = struct.unpack_from("<II", raw, 4)
    if version != 2 or total != len(raw):
        raise RuntimeError(f"Invalid GLB header/version/length: version={version}, declared={total}, actual={len(raw)}")
    chunk_len, chunk_type = struct.unpack_from("<II", raw, 12)
    if chunk_type != 0x4E4F534A:
        raise RuntimeError("First GLB chunk is not JSON")
    return json.loads(raw[20:20 + chunk_len].decode("utf-8").rstrip(" \t\r\n\x00"))


def structural_summary(path: Path) -> dict[str, int]:
    doc = read_glb_json(path)
    accessors = doc.get("accessors") or []
    vertices = 0
    for mesh in doc.get("meshes") or []:
        for prim in mesh.get("primitives") or []:
            pos = (prim.get("attributes") or {}).get("POSITION")
            if isinstance(pos, int) and 0 <= pos < len(accessors):
                vertices += int(accessors[pos].get("count") or 0)
    return {
        "bytes": path.stat().st_size,
        "meshes": len(doc.get("meshes") or []),
        "vertices": vertices,
        "materials": len(doc.get("materials") or []),
        "textures": len(doc.get("textures") or []),
        "images": len(doc.get("images") or []),
        "animations": len(doc.get("animations") or []),
        "skins": len(doc.get("skins") or []),
    }


def choose_quality(total_vram_gib: float, quality: str) -> tuple[int, int, str]:
    if quality == "high":
        if total_vram_gib < 39.0:
            raise RuntimeError(f"High PBR preset requires >=39 GiB VRAM, detected {total_vram_gib:.2f}")
        return 9, 768, "high-9view-768"
    if quality == "standard":
        return 6, 512, "official-memory-floor-6view-512"
    if total_vram_gib >= 39.0:
        return 9, 768, "auto-high-9view-768"
    return 6, 512, "auto-official-6view-512"


def material_gate(path: Path, min_vertices: int) -> dict[str, Any]:
    doc = read_glb_json(path)
    summary = structural_summary(path)
    if summary["vertices"] < min_vertices:
        raise RuntimeError(f"Paint output geometry fell below accepted shape floor: {summary['vertices']} < {min_vertices}")
    mats = doc.get("materials") or []
    textures = doc.get("textures") or []
    images = doc.get("images") or []
    if not mats or not textures or not images:
        raise RuntimeError(f"PBR payload missing: materials={len(mats)} textures={len(textures)} images={len(images)}")
    has_base = False
    has_mr = False
    for mat in mats:
        pbr = mat.get("pbrMetallicRoughness") or {}
        has_base = has_base or isinstance((pbr.get("baseColorTexture") or {}).get("index"), int)
        has_mr = has_mr or isinstance((pbr.get("metallicRoughnessTexture") or {}).get("index"), int)
    if not has_base or not has_mr:
        raise RuntimeError(f"PBR semantic gate failed: baseColor={has_base} metallicRoughness={has_mr}")
    return {**summary, "baseColorTexture": has_base, "metallicRoughnessTexture": has_mr}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hunyuan-root", type=Path, required=True)
    ap.add_argument("--mesh", type=Path, required=True)
    ap.add_argument("--reference", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    ap.add_argument("--quality", choices=["auto", "standard", "high"], default="auto")
    args = ap.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.output_dir / "hunyuan21-paint-report.json"
    report: dict[str, Any] = {
        "startedAt": utc_now(),
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "productionReady": False,
        "productionGate": "CLOSED",
        "pinnedHunyuanCommit": PINNED_HUNYUAN_COMMIT,
    }
    try:
        if not args.mesh.is_file() or args.mesh.stat().st_size < 100_000:
            raise RuntimeError(f"Accepted shape missing/too small: {args.mesh}")
        if not args.reference.is_file() or args.reference.stat().st_size < 10_000:
            raise RuntimeError(f"Hero reference missing/too small: {args.reference}")
        root = args.hunyuan_root.resolve()
        if not (root / "hy3dpaint" / "textureGenPipeline.py").is_file():
            raise RuntimeError(f"Not a Hunyuan3D-2.1 checkout: {root}")
        head = git_head(root)
        report["actualHunyuanCommit"] = head
        if head and head != PINNED_HUNYUAN_COMMIT:
            raise RuntimeError(f"Hunyuan checkout is not pinned: {head} != {PINNED_HUNYUAN_COMMIT}")

        sys.path.insert(0, str(root / "hy3dpaint"))
        sys.path.insert(0, str(root))
        os.chdir(root)

        import torch
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA GPU is required for Hunyuan3D-Paint 2.1")
        props = torch.cuda.get_device_properties(0)
        total_vram = props.total_memory / (1024 ** 3)
        free_bytes, total_bytes = torch.cuda.mem_get_info(0)
        free_vram = free_bytes / (1024 ** 3)
        report["gpu"] = {
            "name": props.name,
            "totalVramGiB": round(total_vram, 2),
            "freeVramBeforeLoadGiB": round(free_vram, 2),
            "torchCuda": torch.version.cuda,
        }
        if total_vram < MIN_VRAM_GIB:
            raise RuntimeError(f"Official Paint memory floor is 21 GiB VRAM; detected {total_vram:.2f} GiB")

        views, resolution, preset = choose_quality(total_vram, args.quality)
        report["qualityPreset"] = preset
        report["maxNumView"] = views
        report["resolution"] = resolution
        report["textureTarget"] = "4096x4096 full-resolution save"

        source_summary = structural_summary(args.mesh)
        if source_summary["vertices"] < 100_000:
            raise RuntimeError(f"Accepted Hunyuan shape is unexpectedly weak: {source_summary}")
        report["sourceShape"] = source_summary
        report["sourceMeshSha256"] = sha256(args.mesh)
        report["referenceSha256"] = sha256(args.reference)

        try:
            from utils.torchvision_fix import apply_fix
            apply_fix()
        except Exception as exc:
            report["torchvisionFixWarning"] = str(exc)[:500]

        from textureGenPipeline import Hunyuan3DPaintConfig, Hunyuan3DPaintPipeline
        from DifferentiableRenderer.mesh_utils import convert_obj_to_glb

        config = Hunyuan3DPaintConfig(max_num_view=views, resolution=resolution)
        pipeline = Hunyuan3DPaintPipeline(config)

        work_mesh = args.mesh.resolve()
        reference = args.reference.resolve()
        default_obj = args.output_dir.resolve() / "police_dog.hunyuan21.pbr_default.obj"
        t0 = time.monotonic()
        pipeline(
            mesh_path=str(work_mesh),
            image_path=str(reference),
            output_mesh_path=str(default_obj),
            use_remesh=False,
            save_glb=True,
        )
        report["paintSeconds"] = round(time.monotonic() - t0, 2)

        # The official __call__ saves downsample=True (2K from its internal 4K texture).
        # Preserve the exact accepted geometry but additionally save the renderer's full 4K PBR state.
        full_obj = args.output_dir.resolve() / "police_dog.hunyuan21.pbr_4k.obj"
        pipeline.render.save_mesh(str(full_obj), downsample=False)
        full_glb = args.output_dir.resolve() / "police_dog.hunyuan21.pbr_4k.glb"
        convert_obj_to_glb(str(full_obj), str(full_glb))
        if not full_glb.is_file():
            raise RuntimeError("Full-resolution Paint GLB was not created")

        pbr_gate = material_gate(full_glb, min_vertices=source_summary["vertices"])
        report["outputGlb"] = full_glb.name
        report["outputSha256"] = sha256(full_glb)
        report["pbrGate"] = pbr_gate
        report["topologyPolicy"] = "use_remesh=false; source geometry floor must be preserved"
        report["normalMapStatus"] = "not-required-from-Paint-gate; dedicated normal/fur finishing remains downstream"
        report["requiredNextStages"] = [
            "four-axis PBR visual identity QC against exact hero reference",
            "fur/cloth/badge/metal normal-detail finishing if the Paint output lacks sufficient microdetail",
            "rig exact accepted geometry without replacing UV/PBR",
            "26 required animation clips plus facial visemes",
            "physical Android visual/performance acceptance"
        ]
        report["status"] = "success-pbr-candidate"
        report["finishedAt"] = utc_now()
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("HUNYUAN21_PBR_CANDIDATE=PASS")
        print("PRODUCTION_GATE=CLOSED")
        return 0
    except Exception as exc:
        report["status"] = "failed-closed"
        report["reason"] = str(exc).replace("\n", " ")[:1600]
        report["finishedAt"] = utc_now()
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("HUNYUAN21_PBR_CANDIDATE=FAILED_CLOSED")
        print("PRODUCTION_GATE=CLOSED")
        return 2


if __name__ == "__main__":
    sys.exit(main())
