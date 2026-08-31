#!/usr/bin/env python3
"""Render clay deformation-review frames for the motion candidate.

This is NOT a material-quality review. It deliberately uses Blender Workbench clay shading so the
review isolates silhouette, skinning and pose deformation from the still-missing cinematic PBR/fur.
It also measures posed bounds and fails on obvious vertex explosions/extreme deformation.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

CASES = [
    ("IdleWork", 0.50, "full"),
    ("Talk", 0.35, "upper"),
    ("Listen", 0.50, "upper"),
    ("ReachFile", 1.00, "full"),
    ("UsePhone", 1.00, "full"),
    ("SitDown", 1.00, "full"),
    ("StandUp", 0.50, "full"),
    ("Walk", 0.25, "full"),
    ("VisemeRest", 1.00, "face"),
    ("VisemeOpen", 1.00, "face"),
    ("VisemeWide", 1.00, "face"),
    ("VisemeRound", 1.00, "face"),
    ("VisemeClosed", 1.00, "face"),
]


def parse_args() -> argparse.Namespace:
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    return p.parse_args(argv)


def bounds_for(objects: list[bpy.types.Object], depsgraph) -> tuple[Vector, Vector]:
    pts: list[Vector] = []
    for obj in objects:
        ev = obj.evaluated_get(depsgraph)
        for corner in ev.bound_box:
            pts.append(ev.matrix_world @ Vector(corner))
    if not pts:
        raise RuntimeError("No evaluated mesh bounds")
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    vals = list(lo) + list(hi)
    if any(not math.isfinite(v) for v in vals):
        raise RuntimeError(f"Non-finite evaluated bounds: {vals}")
    return lo, hi


def extents(lo: Vector, hi: Vector) -> Vector:
    return hi - lo


def aim_camera(camera: bpy.types.Object, target: Vector) -> None:
    direction = target - camera.location
    camera.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def set_camera(camera: bpy.types.Object, lo: Vector, hi: Vector, mode: str) -> None:
    size = extents(lo, hi)
    center = (lo + hi) * 0.5
    # Imported glTF is Z-up in Blender; the hero's front is approximately -Y.
    if mode == "face":
        target = Vector((center.x, center.y, lo.z + size.z * 0.84))
        camera.location = Vector((center.x, lo.y - max(size.x, size.z) * 0.72, target.z + size.z * 0.01))
        camera.data.lens = 75
    elif mode == "upper":
        target = Vector((center.x, center.y, lo.z + size.z * 0.64))
        camera.location = Vector((center.x, lo.y - max(size.x, size.z) * 1.42, target.z + size.z * 0.02))
        camera.data.lens = 58
    else:
        target = Vector((center.x, center.y, lo.z + size.z * 0.50))
        camera.location = Vector((center.x, lo.y - max(size.x, size.z) * 1.85, target.z + size.z * 0.02))
        camera.data.lens = 55
    aim_camera(camera, target)


def activate_action(arm: bpy.types.Object, action_name: str, fraction: float) -> tuple[float, float, float]:
    act = bpy.data.actions.get(action_name)
    if not act:
        raise RuntimeError(f"Imported GLB missing Blender action: {action_name}")
    arm.animation_data_create()
    # Imported NLA tracks can otherwise stack multiple actions during the review.
    for track in arm.animation_data.nla_tracks:
        track.mute = True
    arm.animation_data.action = act
    start, end = float(act.frame_range[0]), float(act.frame_range[1])
    frame = start + (end - start) * min(1.0, max(0.0, fraction))
    bpy.context.scene.frame_set(int(round(frame)))
    bpy.context.view_layer.update()
    return start, end, frame


def main() -> None:
    a = parse_args()
    if not a.input.is_file():
        raise RuntimeError(f"Missing motion GLB: {a.input}")
    a.output_dir.mkdir(parents=True, exist_ok=True)
    a.report.parent.mkdir(parents=True, exist_ok=True)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(a.input))
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    arms = [o for o in bpy.context.scene.objects if o.type == "ARMATURE"]
    if not meshes or len(arms) != 1:
        raise RuntimeError(f"Expected meshes + exactly one armature; meshes={len(meshes)} arms={len(arms)}")
    arm = arms[0]

    scene = bpy.context.scene
    # Clay/Workbench is intentionally used: material quality is a separate gate.
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.show_shadows = True
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = "WORLD"
    scene.display.shading.color_type = "SINGLE"
    scene.display.shading.single_color = (0.52, 0.52, 0.52)
    scene.render.resolution_x = 720
    scene.render.resolution_y = 960
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False

    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "QC_CAMERA"
    scene.camera = camera

    # Reference bounds from the rest pose.
    if arm.animation_data:
        arm.animation_data.action = None
        for track in arm.animation_data.nla_tracks:
            track.mute = True
    scene.frame_set(1)
    bpy.context.view_layer.update()
    deps = bpy.context.evaluated_depsgraph_get()
    rest_lo, rest_hi = bounds_for(meshes, deps)
    rest_ext = extents(rest_lo, rest_hi)
    if min(rest_ext) <= 0:
        raise RuntimeError(f"Invalid rest extents: {list(rest_ext)}")

    results: list[dict] = []
    failures: list[str] = []
    for name, fraction, mode in CASES:
        start, end, frame = activate_action(arm, name, fraction)
        deps = bpy.context.evaluated_depsgraph_get()
        lo, hi = bounds_for(meshes, deps)
        ex = extents(lo, hi)
        ratios = [ex[i] / rest_ext[i] if rest_ext[i] else 0.0 for i in range(3)]
        # Generous enough for sitting/reaching, strict enough to catch skin explosions.
        if max(ratios) > 2.25:
            failures.append(f"{name}: posed bounds exploded versus rest: ratios={ratios}")
        if min(ex) <= 0:
            failures.append(f"{name}: invalid posed extents={list(ex)}")

        set_camera(camera, lo, hi, mode)
        output = a.output_dir / f"{name}.png"
        scene.render.filepath = str(output)
        bpy.ops.render.render(write_still=True)
        if not output.is_file() or output.stat().st_size < 10_000:
            failures.append(f"{name}: render missing/too small")
        results.append({
            "clip": name,
            "frameRange": [start, end],
            "sampleFrame": frame,
            "view": mode,
            "boundsMin": list(lo),
            "boundsMax": list(hi),
            "extents": list(ex),
            "extentRatioVsRest": ratios,
            "render": output.name,
            "renderBytes": output.stat().st_size if output.exists() else 0,
        })

    report = {
        "productionReady": False,
        "reviewPurpose": "deformation-only-clay-review",
        "materialQualityReviewed": False,
        "source": a.input.name,
        "restBoundsMin": list(rest_lo),
        "restBoundsMax": list(rest_hi),
        "restExtents": list(rest_ext),
        "cases": results,
        "automaticFailures": failures,
        "automaticGate": "PASS" if not failures else "FAIL",
        "manualVisualReviewStillRequired": True,
        "productionGate": "CLOSED",
    }
    a.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if failures:
        raise RuntimeError("; ".join(failures))


if __name__ == "__main__":
    main()
