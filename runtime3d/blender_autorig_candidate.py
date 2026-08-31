#!/usr/bin/env python3
"""Create a non-production biped/canine armature candidate without reducing mesh detail.

Run inside Blender in background mode. Blender imports glTF into its native Z-up world,
so this script measures and rigs in Blender coordinates (X right, Y forward/back, Z up),
then lets the glTF exporter convert back to the runtime's +Y-up convention.

The script preserves the source hero mesh, creates a biped K9 skeleton from measured
model bounds, uses Blender automatic bone-heat weights, limits skinning to 4 influences
per vertex, and exports a GLB candidate. It deliberately does NOT fabricate the final
production animation clip set.
"""
from __future__ import annotations

import argparse
import json
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
    cy = (lo.y + hi.y) * 0.5
    width, depth, height = ext.x, ext.y, ext.z
    if height <= 0 or width <= 0 or depth <= 0:
        raise RuntimeError(f"Invalid model bounds: min={tuple(lo)} max={tuple(hi)}")
    # K9 candidate should clearly be portrait/upright after Blender import.
    if height < max(width, depth) * 1.15:
        raise RuntimeError(
            f"Imported model is not sufficiently upright for biped rigging: "
            f"width={width:.4f} depth={depth:.4f} height={height:.4f}"
        )

    def Z(t: float) -> float:  # vertical in Blender
        return lo.z + height * t

    def X(t: float) -> float:
        return cx + width * t

    def Y(t: float) -> float:  # depth/forward offset from model center
        return cy + depth * t

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
        if (b.tail - b.head).length < height * 0.01:
            b.tail.z += height * 0.02
        if parent:
            b.parent = bones[parent]
            b.use_connect = connect
        b.use_deform = deform
        bones[name] = b
        return b

    # Central chain. Blender is Z-up; negative Y is treated as character forward here.
    bone("root", (cx, cy, Z(0.36)), (cx, cy, Z(0.42)), deform=False)
    bone("pelvis", (cx, cy, Z(0.36)), (cx, Y(0.00), Z(0.48)), "root")
    bone("spine_01", (cx, Y(0.00), Z(0.48)), (cx, Y(-0.01), Z(0.59)), "pelvis", True)
    bone("spine_02", (cx, Y(-0.01), Z(0.59)), (cx, Y(-0.01), Z(0.69)), "spine_01", True)
    bone("chest", (cx, Y(-0.01), Z(0.69)), (cx, Y(-0.02), Z(0.76)), "spine_02", True)
    bone("neck", (cx, Y(-0.02), Z(0.76)), (cx, Y(-0.05), Z(0.82)), "chest", True)
    bone("head", (cx, Y(-0.05), Z(0.82)), (cx, Y(-0.08), Z(0.94)), "neck", True)
    bone("jaw", (cx, Y(-0.10), Z(0.83)), (cx, Y(-0.31), Z(0.84)), "head", False, True)
    bone("muzzle_ctrl", (cx, Y(-0.18), Z(0.86)), (cx, Y(-0.30), Z(0.86)), "head", False, False)
    bone("eye.L", (X(-0.09), Y(-0.16), Z(0.88)), (X(-0.09), Y(-0.24), Z(0.88)), "head", False, False)
    bone("eye.R", (X(0.09), Y(-0.16), Z(0.88)), (X(0.09), Y(-0.24), Z(0.88)), "head", False, False)
    bone("ear.L", (X(-0.12), Y(-0.02), Z(0.91)), (X(-0.17), Y(0.00), Z(0.995)), "head", False, True)
    bone("ear.R", (X(0.12), Y(-0.02), Z(0.91)), (X(0.17), Y(0.00), Z(0.995)), "head", False, True)

    # Arms follow the relaxed-down source pose to improve automatic weighting stability.
    for side, s in (("L", -1.0), ("R", 1.0)):
        sx = 0.26 * s
        ex = 0.39 * s
        wx = 0.42 * s
        hx = 0.43 * s
        bone(f"clavicle.{side}", (cx, Y(-0.01), Z(0.72)), (X(sx), Y(-0.01), Z(0.72)), "chest")
        bone(f"upper_arm.{side}", (X(sx), Y(-0.01), Z(0.72)), (X(ex), Y(-0.01), Z(0.53)), f"clavicle.{side}", True)
        bone(f"forearm.{side}", (X(ex), Y(-0.01), Z(0.53)), (X(wx), Y(-0.04), Z(0.36)), f"upper_arm.{side}", True)
        bone(f"hand.{side}", (X(wx), Y(-0.04), Z(0.36)), (X(hx), Y(-0.10), Z(0.27)), f"forearm.{side}", True)

    # Digitigrade legs and feet.
    for side, s in (("L", -1.0), ("R", 1.0)):
        hx = 0.14 * s
        kx = 0.16 * s
        ax = 0.16 * s
        bone(f"thigh.{side}", (X(hx), Y(0.00), Z(0.38)), (X(kx), Y(-0.01), Z(0.23)), "pelvis")
        bone(f"shin.{side}", (X(kx), Y(-0.01), Z(0.23)), (X(ax), Y(0.00), Z(0.075)), f"thigh.{side}", True)
        bone(f"foot.{side}", (X(ax), Y(0.00), Z(0.075)), (X(ax), Y(-0.27), Z(0.025)), f"shin.{side}", True)
        bone(f"toe.{side}", (X(ax), Y(-0.27), Z(0.025)), (X(ax), Y(-0.43), Z(0.025)), f"foot.{side}", True)

    # Tail chain. Positive Y is rear for this reconstruction.
    bone("tail_01", (cx, Y(0.16), Z(0.38)), (cx, Y(0.29), Z(0.29)), "pelvis")
    bone("tail_02", (cx, Y(0.29), Z(0.29)), (X(0.05), Y(0.40), Z(0.20)), "tail_01", True)
    bone("tail_03", (X(0.05), Y(0.40), Z(0.20)), (X(0.12), Y(0.47), Z(0.15)), "tail_02", True)

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

    # Enforce runtime/mobile skinning constraints: max four influences per vertex.
    total_vertices = 0
    unweighted = 0
    max_influences = 0
    for obj in meshes:
        bpy.ops.object.select_all(action="DESELECT")
        obj.select_set(True)
        bpy.context.view_layer.objects.active = obj
        bpy.ops.object.vertex_group_limit_total(limit=4)
        bpy.ops.object.vertex_group_normalize_all(lock_active=False)
        total_vertices += len(obj.data.vertices)
        for v in obj.data.vertices:
            weighted = [g for g in v.groups if g.weight > 1e-6]
            if not weighted:
                unweighted += 1
            max_influences = max(max_influences, len(weighted))

    unweighted_ratio = unweighted / max(1, total_vertices)
    if unweighted_ratio > 0.005:
        raise RuntimeError(
            f"Too many unweighted vertices after automatic weighting: "
            f"{unweighted}/{total_vertices} ({unweighted_ratio:.4%})"
        )
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
        "source": args.input.name,
        "output": args.output.name,
        "productionReady": False,
        "qualityDowngradeAllowed": False,
        "decimationApplied": False,
        "coordinateSystemInBlender": "X right, Y depth, Z up",
        "exportCoordinateSystem": "glTF +Y up",
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
