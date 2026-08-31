#!/usr/bin/env python3
"""Render a geometry-only GLB from four neutral world-axis views for manual QC.

This script deliberately does not claim semantic front/back orientation. It does not
modify or export the input GLB. A temporary clay material and studio lights are used
only for preview rendering so geometry/silhouette can be judged before spending free
texture/rigging compute.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    return ap.parse_args(argv)


def import_glb(path: Path) -> list[bpy.types.Object]:
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    return [obj for obj in bpy.data.objects if obj not in before]


def mesh_objects(objects: list[bpy.types.Object]) -> list[bpy.types.Object]:
    return [obj for obj in objects if obj.type == "MESH" and len(obj.data.vertices) > 0]


def world_bounds(meshes: list[bpy.types.Object]) -> tuple[Vector, Vector]:
    points: list[Vector] = []
    for obj in meshes:
        for corner in obj.bound_box:
            points.append(obj.matrix_world @ Vector(corner))
    if not points:
        raise RuntimeError("No mesh bounds found")
    mn = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
    mx = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
    return mn, mx


def point_at(obj: bpy.types.Object, target: Vector) -> None:
    obj.rotation_euler = (target - obj.location).to_track_quat("-Z", "Y").to_euler()


def make_clay_material() -> bpy.types.Material:
    mat = bpy.data.materials.new("QC_NEUTRAL_CLAY")
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf:
        bsdf.inputs["Base Color"].default_value = (0.42, 0.42, 0.42, 1.0)
        bsdf.inputs["Roughness"].default_value = 0.72
        bsdf.inputs["Metallic"].default_value = 0.0
    return mat


def select_eevee_engine(scene: bpy.types.Scene) -> str:
    """Use the Eevee identifier exposed by the exact Blender build."""
    available = {item.identifier for item in scene.bl_rna.properties["render"].fixed_type.properties["engine"].enum_items}
    for candidate in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        if candidate in available:
            scene.render.engine = candidate
            return candidate
    scene.render.engine = "BLENDER_WORKBENCH"
    return "BLENDER_WORKBENCH"


def main() -> int:
    args = parse_args()
    if not args.input.is_file() or args.input.stat().st_size < 100_000:
        raise SystemExit(f"Input GLB missing/too small: {args.input}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    imported = import_glb(args.input)
    meshes = mesh_objects(imported)
    if not meshes:
        raise RuntimeError("Imported GLB contains no mesh objects")

    vertices = sum(len(o.data.vertices) for o in meshes)
    polygons = sum(len(o.data.polygons) for o in meshes)
    if vertices < 100_000:
        raise RuntimeError(f"Geometry QC floor failed: {vertices} vertices < 100000")

    clay = make_clay_material()
    for obj in meshes:
        obj.data.materials.clear()
        obj.data.materials.append(clay)
        for poly in obj.data.polygons:
            poly.use_smooth = True

    mn, mx = world_bounds(meshes)
    center = (mn + mx) * 0.5
    extent = mx - mn
    radius = max(extent.x, extent.y, extent.z) * 0.5
    if radius <= 1e-6:
        raise RuntimeError("Degenerate GLB bounds")

    scene = bpy.context.scene
    render_engine = select_eevee_engine(scene)
    scene.render.resolution_x = 900
    scene.render.resolution_y = 1200
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    if scene.world is None:
        scene.world = bpy.data.worlds.new("QC_WORLD")
    scene.world.color = (0.018, 0.018, 0.018)

    cam_data = bpy.data.cameras.new("QC_CAMERA")
    cam = bpy.data.objects.new("QC_CAMERA", cam_data)
    bpy.context.collection.objects.link(cam)
    scene.camera = cam
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = max(extent.x, extent.y, extent.z) * 1.18

    for name, energy, size, offset in [
        ("QC_KEY", 1150.0, radius * 1.3, Vector((1.7, -1.6, 1.9))),
        ("QC_FILL", 500.0, radius * 1.6, Vector((-1.5, -0.8, 0.8))),
        ("QC_RIM", 800.0, radius * 1.1, Vector((0.4, 1.8, 1.5))),
    ]:
        ld = bpy.data.lights.new(name, type="AREA")
        ld.energy = energy
        ld.size = max(size, 0.1)
        light = bpy.data.objects.new(name, ld)
        bpy.context.collection.objects.link(light)
        light.location = center + offset * radius
        point_at(light, center)

    # Blender glTF import converts glTF Y-up into Blender Z-up. Rotate cameras around Z.
    distance = radius * 4.0
    views = [
        ("axis_neg_y", Vector((0.0, -distance, 0.0))),
        ("axis_pos_x", Vector((distance, 0.0, 0.0))),
        ("axis_pos_y", Vector((0.0, distance, 0.0))),
        ("axis_neg_x", Vector((-distance, 0.0, 0.0))),
    ]
    rendered: list[str] = []
    for label, offset in views:
        cam.location = center + offset + Vector((0.0, 0.0, extent.z * 0.04))
        point_at(cam, center)
        out = args.output_dir / f"hunyuan_shape.{label}.png"
        scene.render.filepath = str(out)
        bpy.ops.render.render(write_still=True)
        if not out.is_file() or out.stat().st_size < 10_000:
            raise RuntimeError(f"QC render missing/too small: {out}")
        rendered.append(out.name)

    report = {
        "input": args.input.name,
        "inputBytes": args.input.stat().st_size,
        "meshObjects": len(meshes),
        "vertices": vertices,
        "polygons": polygons,
        "worldBoundsMin": [float(v) for v in mn],
        "worldBoundsMax": [float(v) for v in mx],
        "worldExtent": [float(v) for v in extent],
        "renderEngine": render_engine,
        "renderedViews": rendered,
        "semanticOrientationClaimed": False,
        "materialMode": "temporary-neutral-clay-preview-only",
        "productionReady": False,
        "productionGate": "CLOSED",
    }
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("HUNYUAN_SHAPE_QC_RENDER=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
