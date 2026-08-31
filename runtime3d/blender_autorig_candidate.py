#!/usr/bin/env python3
"""Create a non-production biped/canine armature candidate without reducing mesh detail.

Run inside Blender in background mode. The script preserves the source hero mesh,
creates a biped K9 skeleton from measured model bounds, uses Blender automatic bone
heat weights, limits skinning to 4 influences per vertex, and exports a GLB candidate.
It deliberately does NOT fabricate the production animation clip set.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def parse_args() -> argparse.Namespace:
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--qc", type=Path, required=True)
    return p.parse_args(argv)


def world_bounds(objects: list[bpy.types.Object]) -> tuple[Vector, Vector]:
    points: list[Vector] = []
    for obj in objects:
        for corner in obj.bound_box:
            points.append(obj.matrix_world @ Vector(corner))
    if not points:
        raise RuntimeError("No mesh bounds available")
    lo = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
    hi = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
    return lo, hi


def main() -> None:
    args = parse_args()
    if not args.input.is_file():
        raise RuntimeError(f"Input GLB missing: {args.input}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.qc.parent.mkdir(parents=True, exist_ok=True)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(args.input))
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    if not meshes:
        raise RuntimeError("Imported candidate contains no mesh objects")

    before_vertices = sum(len(o.data.vertices) for o in meshes)
    before_polygons = sum(len(o.data.polygons) for o in meshes)
    lo, hi = world_bounds(meshes)
    ext = hi - lo
    cx = (lo.x + hi.x) * 0.5
    cz = (lo.z + hi.z) * 0.5
    h, w, d = ext.y, ext.x, ext.z
    if h <= 0 or w <= 0 or d <= 0:
        raise RuntimeError(f"Invalid model bounds: {lo[:]=} {hi[:]=}")

    def Y(t: float) -> float:
        return lo.y + h * t

    def X(t: float) -> float:
        return cx + w * t

    def Z(t: float) -> float:
        return cz + d * t

    bpy.ops.object.armature_add(enter_editmode=True, location=(0, 0, 0))
    arm_obj = bpy.context.object
    arm_obj.name = "POLICE_DOG_RIG_CANDIDATE"
    arm = arm_obj.data
    arm.name = "POLICE_DOG_ARMATURE_CANDIDATE"
    for b in list(arm.edit_bones):
        arm.edit_bones.remove(b)

    bones: dict[str, bpy.types.EditBone] = {}

    def bone(name: str, head, tail, parent: str | None = None, connect: bool = False, deform: bool = True):
        b = arm.edit_bones.new(name)
        b.head = Vector(head)
        b.tail = Vector(tail)
        if (b.tail - b.head).length < h * 0.01:
            b.tail.y += h * 0.02
        if parent:
            b.parent = bones[parent]
            b.use_connect = connect
        b.use_deform = deform
        bones[name] = b
        return b

    # Central chain. Y is vertical; +Z is the character's forward direction.
    bone("root", (cx, Y(0.36), cz), (cx, Y(0.42), cz), deform=False)
    bone("pelvis", (cx, Y(0.36), cz), (cx, Y(0.48), Z(0.00)), "root")
    bone("spine_01", (cx, Y(0.48), Z(0.00)), (cx, Y(0.59), Z(0.01)), "pelvis", True)
    bone("spine_02", (cx, Y(0.59), Z(0.01)), (cx, Y(0.69), Z(0.01)), "spine_01", True)
    bone("chest", (cx, Y(0.69), Z(0.01)), (cx, Y(0.76), Z(0.02)), "spine_02", True)
    bone("neck", (cx, Y(0.76), Z(0.02)), (cx, Y(0.82), Z(0.05)), "chest", True)
    bone("head", (cx, Y(0.82), Z(0.05)), (cx, Y(0.94), Z(0.08)), "neck", True)
    bone("jaw", (cx, Y(0.83), Z(0.10)), (cx, Y(0.84), Z(0.31)), "head", False, True)
    bone("muzzle_ctrl", (cx, Y(0.86), Z(0.18)), (cx, Y(0.86), Z(0.30)), "head", False, False)
    bone("eye.L", (X(-0.09), Y(0.88), Z(0.16)), (X(-0.09), Y(0.88), Z(0.24)), "head", False, False)
    bone("eye.R", (X(0.09), Y(0.88), Z(0.16)), (X(0.09), Y(0.88), Z(0.24)), "head", False, False)
    bone("ear.L", (X(-0.12), Y(0.91), Z(0.02)), (X(-0.17), Y(0.995), Z(0.00)), "head", False, True)
    bone("ear.R", (X(0.12), Y(0.91), Z(0.02)), (X(0.17), Y(0.995), Z(0.00)), "head", False, True)

    # Arms follow the current relaxed-down source pose to make automatic weighting stable.
    for side, s in (("L", -1.0), ("R", 1.0)):
        sx = 0.26 * s
        ex = 0.39 * s
        wx = 0.42 * s
        hx = 0.43 * s
        bone(f"clavicle.{side}", (cx, Y(0.72), Z(0.01)), (X(sx), Y(0.72), Z(0.01)), "chest")
        bone(f"upper_arm.{side}", (X(sx), Y(0.72), Z(0.01)), (X(ex), Y(0.53), Z(0.01)), f"clavicle.{side}", True)
        bone(f"forearm.{side}", (X(ex), Y(0.53), Z(0.01)), (X(wx), Y(0.36), Z(0.04)), f"upper_arm.{side}", True)
        bone(f"hand.{side}", (X(wx), Y(0.36), Z(0.04)), (X(hx), Y(0.27), Z(0.10)), f"forearm.{side}", True)

    # Legs and feet.
    for side, s in (("L", -1.0), ("R", 1.0)):
        hx = 0.14 * s
        kx = 0.16 * s
        ax = 0.16 * s
        bone(f"thigh.{side}", (X(hx), Y(0.38), Z(0.00)), (X(kx), Y(0.23), Z(0.01)), "pelvis")
        bone(f"shin.{side}", (X(kx), Y(0.23), Z(0.01)), (X(ax), Y(0.075), Z(0.00)), f"thigh.{side}", True)
        bone(f"foot.{side}", (X(ax), Y(0.075), Z(0.00)), (X(ax), Y(0.025), Z(0.27)), f"shin.{side}", True)
        bone(f"toe.{side}", (X(ax), Y(0.025), Z(0.27)), (X(ax), Y(0.025), Z(0.43)), f"foot.{side}", True)

    # Tail chain; rear is -Z for this reconstruction.
    bone("tail_01", (cx, Y(0.38), Z(-0.16)), (cx, Y(0.29), Z(-0.29)), "pelvis")
    bone("tail_02", (cx, Y(0.29), Z(-0.29)), (X(0.05), Y(0.20), Z(-0.40)), "tail_01", True)
    bone("tail_03", (X(0.05), Y(0.20), Z(-0.40)), (X(0.12), Y(0.15), Z(-0.47)), "tail_02", True)

    bpy.ops.object.mode_set(mode="OBJECT")
    arm_obj.show_in_front = True

    # Automatic bone-heat weighting only. There is deliberately no lower-quality fallback.
    bpy.ops.object.select_all(action="DESELECT")
    for obj in meshes:
        obj.select_set(True)
    arm_obj.select_set(True)
    bpy.context.view_layer.objects.active = arm_obj
    try:
        bpy.ops.object.parent_set(type="ARMATURE_AUTO")
    except RuntimeError as exc:
        raise RuntimeError(f"Blender automatic weights failed; refusing lower-quality fallback: {exc}") from exc

    # Enforce the runtime contract: at most four influences per vertex, then normalize.
    total_vertices = 0
    unweighted = 0
    max_influences = 0
    for obj in meshes:
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        bpy.ops.object.vertex_group_limit_total(limit=4)
        bpy.ops.object.vertex_group_normalize_all(lock_active=False)
        total_vertices += len(obj.data.vertices)
        for v in obj.data.vertices:
            weighted = [g for g in v.groups if g.weight > 1e-6]
            if not weighted:
                unweighted += 1
            max_influences = max(max_influences, len(weighted))
        obj.select_set(False)

    unweighted_ratio = unweighted / max(1, total_vertices)
    if unweighted_ratio > 0.005:
        raise RuntimeError(f"Too many unweighted vertices after automatic weighting: {unweighted}/{total_vertices} ({unweighted_ratio:.4%})")
    if max_influences > 4:
        raise RuntimeError(f"Skinning influence limit violated: {max_influences}")

    deform_bones = [b.name for b in arm_obj.data.bones if b.use_deform]
    if len(deform_bones) < 24:
        raise RuntimeError(f"Rig has too few deform bones: {len(deform_bones)}")

    # Preserve full source geometry. No decimation/remesh/subdivision is applied.
    after_vertices = sum(len(o.data.vertices) for o in meshes)
    after_polygons = sum(len(o.data.polygons) for o in meshes)
    if after_vertices != before_vertices or after_polygons != before_polygons:
        raise RuntimeError(
            f"Geometry count changed during rigging: vertices {before_vertices}->{after_vertices}, "
            f"polygons {before_polygons}->{after_polygons}"
        )

    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.export_scene.gltf(
        filepath=str(args.output),
        export_format="GLB",
        use_selection=False,
        export_skins=True,
        export_animations=True,
        export_apply=False,
        export_yup=True,
    )

    qc = {
        "source": str(args.input.name),
        "output": str(args.output.name),
        "productionReady": False,
        "qualityDowngradeAllowed": False,
        "decimationApplied": False,
        "meshObjects": len(meshes),
        "vertices": before_vertices,
        "polygons": before_polygons,
        "bounds": {"min": list(lo), "max": list(hi), "extents": list(ext)},
        "armature": arm_obj.name,
        "bones": len(arm_obj.data.bones),
        "deformBones": len(deform_bones),
        "deformBoneNames": deform_bones,
        "maxInfluencesPerVertex": max_influences,
        "unweightedVertices": unweighted,
        "unweightedRatio": unweighted_ratio,
        "weighting": "Blender ARMATURE_AUTO bone heat",
        "nextGates": [
            "visual deformation review",
            "PBR/fur/uniform finishing",
            "facial muzzle/jaw/viseme authoring",
            "required named body animation clips",
            "physical Android device acceptance"
        ],
    }
    args.qc.write_text(json.dumps(qc, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(qc, indent=2))


if __name__ == "__main__":
    main()
