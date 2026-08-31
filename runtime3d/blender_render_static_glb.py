#!/usr/bin/env python3
"""Render deterministic four-view QC images for a static GLB candidate."""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--preview-dir", type=Path, required=True)
    p.add_argument("--qc", type=Path, required=True)
    return p.parse_args(argv)


def world_bounds(objects: list[bpy.types.Object]) -> tuple[Vector, Vector]:
    pts: list[Vector] = []
    for obj in objects:
        if obj.type != "MESH":
            continue
        for corner in obj.bound_box:
            pts.append(obj.matrix_world @ Vector(corner))
    if not pts:
        raise RuntimeError("Imported GLB contains no renderable mesh bounds")
    mn = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    mx = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return mn, mx


def point_at(obj: bpy.types.Object, target: Vector) -> None:
    obj.rotation_euler = (target - obj.location).to_track_quat("-Z", "Y").to_euler()


def main() -> int:
    args = parse_args()
    if not args.input.is_file():
        raise SystemExit(f"GLB missing: {args.input}")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    before = set(bpy.data.objects)
    result = bpy.ops.import_scene.gltf(filepath=str(args.input))
    if "FINISHED" not in result:
        raise RuntimeError(f"GLB import failed: {result}")
    imported = [obj for obj in bpy.data.objects if obj not in before]
    meshes = [obj for obj in imported if obj.type == "MESH"]
    if not meshes:
        raise RuntimeError("GLB imported without meshes")

    mn, mx = world_bounds(meshes)
    center = (mn + mx) * 0.5
    extent = mx - mn
    radius = max(extent.x, extent.y, extent.z) * 0.70
    if radius <= 1e-5:
        raise RuntimeError("Degenerate GLB bounds")

    scene = bpy.context.scene
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    try:
        scene.render.engine = "BLENDER_EEVEE_NEXT"
    except Exception:
        scene.render.engine = "BLENDER_EEVEE"
    scene.render.image_settings.color_mode = "RGBA"
    if scene.world is None:
        scene.world = bpy.data.worlds.new("QC_WORLD")
    scene.world.color = (0.035, 0.04, 0.048)

    cam_data = bpy.data.cameras.new("QC_CAMERA")
    cam = bpy.data.objects.new("QC_CAMERA", cam_data)
    bpy.context.collection.objects.link(cam)
    scene.camera = cam
    cam.data.lens = 58

    light_specs = [
        ("QC_KEY", 1050.0, Vector((1.4, -1.4, 1.6)), 1.2),
        ("QC_FILL", 520.0, Vector((-1.3, -0.8, 0.8)), 1.0),
        ("QC_RIM", 780.0, Vector((-0.6, 1.4, 1.4)), 0.9),
    ]
    for name, energy, direction, size_scale in light_specs:
        data = bpy.data.lights.new(name, type="AREA")
        data.energy = energy
        data.size = radius * size_scale
        obj = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(obj)
        obj.location = center + direction * radius
        point_at(obj, center)

    # Keep all imported meshes visible; suppress unrelated helper/camera objects from the GLB.
    for obj in imported:
        obj.hide_render = obj.type != "MESH"

    args.preview_dir.mkdir(parents=True, exist_ok=True)
    views = [
        ("front", Vector((0.0, -2.55, 0.10))),
        ("three_quarter", Vector((1.55, -2.15, 0.15))),
        ("side", Vector((2.55, 0.0, 0.10))),
        ("back", Vector((0.0, 2.55, 0.10))),
    ]
    files: list[str] = []
    for name, direction in views:
        cam.location = center + direction * radius
        point_at(cam, center)
        path = args.preview_dir / f"trellis2_hero.{name}.png"
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        if not path.is_file() or path.stat().st_size < 10_000:
            raise RuntimeError(f"QC render missing/too small: {path}")
        files.append(path.name)

    material_count = len({mat.name for obj in meshes for mat in obj.data.materials if mat})
    qc = {
        "input": args.input.name,
        "meshObjects": len(meshes),
        "rawVertices": sum(len(obj.data.vertices) for obj in meshes),
        "rawPolygons": sum(len(obj.data.polygons) for obj in meshes),
        "materials": material_count,
        "boundsMin": list(map(float, mn)),
        "boundsMax": list(map(float, mx)),
        "extent": list(map(float, extent)),
        "previewFiles": files,
        "productionReady": False,
        "productionGate": "CLOSED",
    }
    args.qc.parent.mkdir(parents=True, exist_ok=True)
    args.qc.write_text(json.dumps(qc, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(qc, indent=2), flush=True)
    print("STATIC_FOUR_VIEW_RENDER_GATE=PASS", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
