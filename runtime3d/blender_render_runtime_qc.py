#!/usr/bin/env python3
"""Render deterministic front/three-quarter/side QC views for an existing runtime GLB."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import bpy

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_transfer_pbr_candidate as base


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--preview-dir", type=Path, required=True)
    return parser.parse_args(argv)


def main() -> int:
    args = parse_args()
    if not args.input.is_file():
        raise RuntimeError(f"Input GLB missing: {args.input}")
    bpy.ops.wm.read_factory_settings(use_empty=True)
    objects = base.import_glb(args.input)
    meshes = base.mesh_objects(objects)
    armatures = base.armature_objects(objects)
    target, diagnostics = base.select_skinned_hero(meshes, armatures)
    previews = base.setup_preview(target, args.preview_dir)
    print(json.dumps({
        "input": args.input.name,
        "selectedHero": target.name,
        "previews": previews,
        "targetDiagnostics": diagnostics,
    }, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
