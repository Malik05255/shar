#!/usr/bin/env python3
"""Author a sparse, layer-safe motion/facial candidate on the existing full-detail K9 rig.

The input rig geometry is preserved exactly. This pass upgrades the existing zero-weight
facial controls (muzzle and eyes) with localized skin weights, then authors the exact
runtime clip names using sparse channels so BODY/GAZE/FACE clips can be applied in
sequence without resetting unrelated bones.

This remains a candidate: it deliberately keeps the production gate closed until visual
review and physical Android acceptance pass.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Iterable

import bpy
from mathutils import Vector

REQUIRED = [
    "IdleWork", "Breathing", "Blink", "EyeSaccade", "LookAtDesk", "LookAtMonitor",
    "LookAtCamera", "LookAtDoor", "LookAtStaff", "ReachFile", "ReviewFile", "TurnPage",
    "WriteNote", "SetFileDown", "UsePhone", "Listen", "Talk", "StandUp", "SitDown",
    "Walk", "LeanBack", "VisemeRest", "VisemeOpen", "VisemeWide", "VisemeRound",
    "VisemeClosed",
]
FACE_BONES = ("jaw", "muzzle_ctrl", "eye.L", "eye.R")


def parse_args() -> argparse.Namespace:
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--qc", type=Path, required=True)
    return p.parse_args(argv)


def world_bounds(obj: bpy.types.Object) -> tuple[Vector, Vector]:
    points = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    if not points:
        raise RuntimeError(f"No bounds for {obj.name}")
    lo = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
    hi = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
    return lo, hi


def ellipsoid_weight(p: tuple[float, float, float], center: tuple[float, float, float],
                     radii: tuple[float, float, float], peak: float) -> float:
    d2 = sum(((p[i] - center[i]) / radii[i]) ** 2 for i in range(3))
    if d2 >= 1.0:
        return 0.0
    t = 1.0 - d2
    return peak * t * t


def retrofit_facial_weights(meshes: list[bpy.types.Object], arm: bpy.types.Object) -> dict:
    if not meshes:
        raise RuntimeError("No mesh available for facial weighting")
    hero = max(meshes, key=lambda o: len(o.data.vertices))
    if len(hero.data.vertices) < 100000:
        raise RuntimeError(f"Hero mesh unexpectedly small: {len(hero.data.vertices)} vertices")

    for name in FACE_BONES:
        bone = arm.data.bones.get(name)
        if bone is None:
            raise RuntimeError(f"Required facial bone missing: {name}")
        bone.use_deform = True

    lo, hi = world_bounds(hero)
    ext = hi - lo
    width, depth, height = ext.x, ext.y, ext.z
    if min(width, depth, height) <= 0:
        raise RuntimeError(f"Invalid hero bounds: lo={tuple(lo)} hi={tuple(hi)}")
    cx, cy = (lo.x + hi.x) * 0.5, (lo.y + hi.y) * 0.5

    groups = {}
    for name in ("muzzle_ctrl", "eye.L", "eye.R"):
        groups[name] = hero.vertex_groups.get(name) or hero.vertex_groups.new(name=name)

    # Coordinates mirror the rig author's normalized landmarks:
    # x/depth offsets use full mesh extent; z is normalized from mesh floor.
    counts_added = {name: 0 for name in groups}
    max_added = {name: 0.0 for name in groups}
    for v in hero.data.vertices:
        wp = hero.matrix_world @ v.co
        n = (
            (wp.x - cx) / width,
            (wp.y - cy) / depth,
            (wp.z - lo.z) / height,
        )
        candidates = {
            "muzzle_ctrl": ellipsoid_weight(n, (0.0, -0.205, 0.855), (0.235, 0.185, 0.105), 1.00),
            "eye.L": ellipsoid_weight(n, (-0.090, -0.155, 0.885), (0.105, 0.095, 0.060), 1.00),
            "eye.R": ellipsoid_weight(n, (0.090, -0.155, 0.885), (0.105, 0.095, 0.060), 1.00),
        }
        for name, weight in candidates.items():
            if weight <= 0.015:
                continue
            groups[name].add([v.index], min(1.0, weight), "REPLACE")
            counts_added[name] += 1
            max_added[name] = max(max_added[name], weight)

    # Normalize and preserve the Android/Filament four-influence constraint.
    bpy.ops.object.select_all(action="DESELECT")
    hero.select_set(True)
    bpy.context.view_layer.objects.active = hero
    bpy.ops.object.vertex_group_normalize_all(lock_active=False)
    bpy.ops.object.vertex_group_limit_total(limit=4)
    bpy.ops.object.vertex_group_normalize_all(lock_active=False)

    counts_after = {name: 0 for name in groups}
    weighted_sum = {name: 0.0 for name in groups}
    max_influences = 0
    unweighted = 0
    for v in hero.data.vertices:
        influences = [g for g in v.groups if g.weight > 1e-6]
        max_influences = max(max_influences, len(influences))
        if not influences:
            unweighted += 1
        for g in influences:
            group_name = hero.vertex_groups[g.group].name
            if group_name in counts_after:
                counts_after[group_name] += 1
                weighted_sum[group_name] += float(g.weight)

    floors = {"muzzle_ctrl": 1000, "eye.L": 250, "eye.R": 250}
    failures = {name: counts_after[name] for name in floors if counts_after[name] < floors[name]}
    if failures:
        raise RuntimeError(f"Facial control weighting floor failed: {failures}")
    if max_influences > 4:
        raise RuntimeError(f"Facial retrofit exceeded four influences: {max_influences}")
    if unweighted / max(1, len(hero.data.vertices)) > 0.005:
        raise RuntimeError(f"Too many unweighted vertices after facial retrofit: {unweighted}")

    return {
        "hero": hero.name,
        "boundsMin": [float(x) for x in lo],
        "boundsMax": [float(x) for x in hi],
        "addedCandidateVertices": counts_added,
        "retainedWeightedVertices": counts_after,
        "retainedWeightSums": weighted_sum,
        "peakAssignedWeight": max_added,
        "maxInfluencesPerVertex": max_influences,
        "unweightedVertices": unweighted,
    }


def clear_pose(arm: bpy.types.Object) -> None:
    for pb in arm.pose.bones:
        pb.rotation_mode = "XYZ"
        pb.rotation_euler = (0.0, 0.0, 0.0)
        pb.location = (0.0, 0.0, 0.0)
        pb.scale = (1.0, 1.0, 1.0)


def insert_pose_key(
    arm: bpy.types.Object,
    frame: int,
    rotations: dict[str, tuple[float, float, float]] | None = None,
    locations: dict[str, tuple[float, float, float]] | None = None,
    scales: dict[str, tuple[float, float, float]] | None = None,
) -> None:
    rotations = rotations or {}
    locations = locations or {}
    scales = scales or {}
    for name, rot in rotations.items():
        pb = arm.pose.bones.get(name)
        if pb is None:
            raise RuntimeError(f"Missing pose bone: {name}")
        pb.rotation_mode = "XYZ"
        pb.rotation_euler = tuple(math.radians(v) for v in rot)
        pb.keyframe_insert(data_path="rotation_euler", frame=frame, group=name)
    for name, loc in locations.items():
        pb = arm.pose.bones.get(name)
        if pb is None:
            raise RuntimeError(f"Missing pose bone: {name}")
        pb.location = loc
        pb.keyframe_insert(data_path="location", frame=frame, group=name)
    for name, scale in scales.items():
        pb = arm.pose.bones.get(name)
        if pb is None:
            raise RuntimeError(f"Missing pose bone: {name}")
        pb.scale = scale
        pb.keyframe_insert(data_path="scale", frame=frame, group=name)


def action(arm: bpy.types.Object, name: str, end: int, keys: Iterable[tuple]) -> bpy.types.Action:
    clear_pose(arm)
    act = bpy.data.actions.new(name=name)
    act.use_fake_user = True
    arm.animation_data_create()
    arm.animation_data.action = act
    for spec in keys:
        if len(spec) == 3:
            frame, rots, locs = spec
            scales = {}
        elif len(spec) == 4:
            frame, rots, locs, scales = spec
        else:
            raise RuntimeError(f"Invalid key spec in {name}: {spec}")
        insert_pose_key(arm, frame, rots, locs, scales)
    for fc in act.fcurves:
        for kp in fc.keyframe_points:
            kp.interpolation = "BEZIER"
    act.frame_range = (1, end)
    track = arm.animation_data.nla_tracks.new()
    track.name = name
    strip = track.strips.new(name, 1, act)
    strip.name = name
    track.mute = True
    arm.animation_data.action = None
    return act


def author_actions(arm: bpy.types.Object) -> None:
    # Biological micro-motion and independent facial controls.
    action(arm, "IdleWork", 120, [
        (1, {"spine_02": (0, 0, -1.0), "neck": (1.0, 0, 0), "tail_02": (0, 2, 0)}, {}),
        (60, {"spine_02": (0.5, 0, 1.0), "neck": (-1.0, 0, 0), "tail_02": (0, -2, 0)}, {}),
        (120, {"spine_02": (0, 0, -1.0), "neck": (1.0, 0, 0), "tail_02": (0, 2, 0)}, {}),
    ])
    action(arm, "Breathing", 90, [
        (1, {"chest": (-1.0, 0, 0), "spine_02": (0.5, 0, 0)}, {}),
        (45, {"chest": (1.5, 0, 0), "spine_02": (-0.5, 0, 0)}, {}),
        (90, {"chest": (-1.0, 0, 0), "spine_02": (0.5, 0, 0)}, {}),
    ])
    action(arm, "Blink", 12, [
        (1, {}, {}, {"eye.L": (1.0, 1.0, 1.0), "eye.R": (1.0, 1.0, 1.0)}),
        (4, {}, {}, {"eye.L": (1.02, 1.0, 0.22), "eye.R": (1.02, 1.0, 0.22)}),
        (7, {}, {}, {"eye.L": (1.02, 1.0, 0.22), "eye.R": (1.02, 1.0, 0.22)}),
        (12, {}, {}, {"eye.L": (1.0, 1.0, 1.0), "eye.R": (1.0, 1.0, 1.0)}),
    ])
    action(arm, "EyeSaccade", 30, [
        (1, {"eye.L": (0, 0, -2.0), "eye.R": (0, 0, -2.0)}, {}),
        (10, {"eye.L": (0, 0, 3.0), "eye.R": (0, 0, 3.0)}, {}),
        (20, {"eye.L": (0, 0, -1.0), "eye.R": (0, 0, -1.0)}, {}),
        (30, {"eye.L": (0, 0, -2.0), "eye.R": (0, 0, -2.0)}, {}),
    ])

    look_specs = {
        "LookAtDesk": ((14, 0, 0), (5, 0, 0)),
        "LookAtMonitor": ((4, 0, -8), (2, 0, -3)),
        "LookAtCamera": ((-2, 0, 0), (0, 0, 0)),
        "LookAtDoor": ((0, 0, 22), (0, 0, 8)),
        "LookAtStaff": ((0, 0, -18), (0, 0, -6)),
    }
    for name, (head_rot, chest_rot) in look_specs.items():
        action(arm, name, 24, [
            (1, {}, {}),
            (12, {"head": head_rot, "chest": chest_rot}, {}),
            (24, {"head": head_rot, "chest": chest_rot}, {}),
        ])

    action(arm, "ReachFile", 36, [(1, {}, {}), (36, {"upper_arm.R": (22, 0, -18), "forearm.R": (-34, 0, 4), "hand.R": (10, 0, 0)}, {})])
    action(arm, "ReviewFile", 70, [
        (1, {"head": (10, 0, 0), "forearm.L": (-20, 0, 0), "forearm.R": (-20, 0, 0)}, {}),
        (35, {"head": (13, 0, -2), "forearm.L": (-23, 0, 0), "forearm.R": (-18, 0, 0)}, {}),
        (70, {"head": (10, 0, 0), "forearm.L": (-20, 0, 0), "forearm.R": (-20, 0, 0)}, {}),
    ])
    action(arm, "TurnPage", 35, [
        (1, {}, {}),
        (18, {"forearm.R": (-26, 0, -8), "hand.R": (12, 0, -15)}, {}),
        (35, {"forearm.R": (-18, 0, 4), "hand.R": (0, 0, 5)}, {}),
    ])
    action(arm, "WriteNote", 70, [
        (1, {"upper_arm.R": (14, 0, -10), "forearm.R": (-38, 0, 2)}, {}),
        (20, {"hand.R": (5, 0, -8)}, {}),
        (40, {"hand.R": (-5, 0, 8)}, {}),
        (70, {"hand.R": (5, 0, -8)}, {}),
    ])
    action(arm, "SetFileDown", 35, [
        (1, {"upper_arm.R": (18, 0, -12), "forearm.R": (-32, 0, 0)}, {}),
        (35, {"upper_arm.R": (5, 0, -4), "forearm.R": (-8, 0, 0)}, {}),
    ])
    action(arm, "UsePhone", 60, [
        (1, {}, {}),
        (30, {"upper_arm.R": (-8, 0, -55), "forearm.R": (-92, 0, 5), "hand.R": (25, 0, -10), "head": (0, 0, 4)}, {}),
        (60, {"upper_arm.R": (-8, 0, -55), "forearm.R": (-92, 0, 5), "hand.R": (25, 0, -10), "head": (0, 0, 4)}, {}),
    ])
    action(arm, "Listen", 80, [
        (1, {"head": (0, 0, -2), "ear.L": (0, 0, 1), "ear.R": (0, 0, -1)}, {}),
        (40, {"head": (-2, 0, 2), "ear.L": (0, 0, -1), "ear.R": (0, 0, 1)}, {}),
        (80, {"head": (0, 0, -2), "ear.L": (0, 0, 1), "ear.R": (0, 0, -1)}, {}),
    ])
    action(arm, "Talk", 80, [
        (1, {"head": (0, 0, -1), "chest": (0, 0, 0)}, {}),
        (20, {"head": (-2, 0, 2), "chest": (1, 0, -1)}, {}),
        (40, {"head": (1, 0, -2), "chest": (-1, 0, 1)}, {}),
        (80, {"head": (0, 0, -1), "chest": (0, 0, 0)}, {}),
    ])

    action(arm, "SitDown", 45, [
        (1, {}, {}),
        (45, {"thigh.L": (-55, 0, 0), "thigh.R": (-55, 0, 0), "shin.L": (72, 0, 0), "shin.R": (72, 0, 0), "spine_01": (8, 0, 0)}, {"pelvis": (0, 0, -0.22)}),
    ])
    action(arm, "StandUp", 45, [
        (1, {"thigh.L": (-55, 0, 0), "thigh.R": (-55, 0, 0), "shin.L": (72, 0, 0), "shin.R": (72, 0, 0), "spine_01": (8, 0, 0)}, {"pelvis": (0, 0, -0.22)}),
        (45, {}, {"pelvis": (0, 0, 0)}),
    ])
    action(arm, "LeanBack", 50, [
        (1, {}, {}),
        (25, {"spine_01": (-6, 0, 0), "spine_02": (-5, 0, 0), "head": (4, 0, 0)}, {}),
        (50, {}, {}),
    ])
    action(arm, "Walk", 48, [
        (1, {"thigh.L": (20, 0, 0), "thigh.R": (-20, 0, 0), "shin.L": (-12, 0, 0), "shin.R": (25, 0, 0), "upper_arm.L": (-10, 0, 0), "upper_arm.R": (10, 0, 0)}, {}),
        (24, {"thigh.L": (-20, 0, 0), "thigh.R": (20, 0, 0), "shin.L": (25, 0, 0), "shin.R": (-12, 0, 0), "upper_arm.L": (10, 0, 0), "upper_arm.R": (-10, 0, 0)}, {}),
        (48, {"thigh.L": (20, 0, 0), "thigh.R": (-20, 0, 0), "shin.L": (-12, 0, 0), "shin.R": (25, 0, 0), "upper_arm.L": (-10, 0, 0), "upper_arm.R": (10, 0, 0)}, {}),
    ])

    # Layer-safe visemes: jaw controls opening while muzzle_ctrl supplies genuinely distinct
    # wide/round/closed silhouettes. Each pose is intentionally constant so t=0 is usable.
    visemes = {
        "VisemeRest": ((0.0, 0.0, 0.0), (1.00, 1.00, 1.00)),
        "VisemeOpen": ((16.0, 0.0, 0.0), (1.00, 1.03, 1.05)),
        "VisemeWide": ((8.0, 0.0, 0.0), (1.16, 0.99, 0.96)),
        "VisemeRound": ((10.0, 0.0, 0.0), (0.87, 1.11, 1.03)),
        "VisemeClosed": ((-2.0, 0.0, 0.0), (1.02, 0.99, 0.92)),
    }
    for name, (jaw_rot, muzzle_scale) in visemes.items():
        action(arm, name, 2, [
            (1, {"jaw": jaw_rot}, {}, {"muzzle_ctrl": muzzle_scale}),
            (2, {"jaw": jaw_rot}, {}, {"muzzle_ctrl": muzzle_scale}),
        ])


def main() -> None:
    a = parse_args()
    if not a.input.is_file():
        raise RuntimeError(f"Missing input: {a.input}")
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.qc.parent.mkdir(parents=True, exist_ok=True)

    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(a.input))
    armatures = [o for o in bpy.context.scene.objects if o.type == "ARMATURE"]
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    if len(armatures) != 1:
        raise RuntimeError(f"Expected exactly one armature, got {len(armatures)}")
    arm = armatures[0]
    before_vertices = sum(len(o.data.vertices) for o in meshes)
    before_polygons = sum(len(o.data.polygons) for o in meshes)

    facial_qc = retrofit_facial_weights(meshes, arm)
    author_actions(arm)

    names = sorted(act.name for act in bpy.data.actions)
    missing = [n for n in REQUIRED if n not in names]
    if missing:
        raise RuntimeError(f"Missing authored actions: {missing}")

    after_vertices = sum(len(o.data.vertices) for o in meshes)
    after_polygons = sum(len(o.data.polygons) for o in meshes)
    if (before_vertices, before_polygons) != (after_vertices, after_polygons):
        raise RuntimeError(
            f"Geometry changed during sparse animation authoring: "
            f"{(before_vertices, before_polygons)} -> {(after_vertices, after_polygons)}"
        )

    if arm.animation_data:
        for track in arm.animation_data.nla_tracks:
            track.mute = False

    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.export_scene.gltf(
        filepath=str(a.output),
        export_format="GLB",
        use_selection=False,
        export_skins=True,
        export_animations=True,
        export_nla_strips=True,
        export_force_sampling=False,
        export_apply=False,
        export_yup=True,
    )

    qc = {
        "productionReady": False,
        "purpose": "layer-safe-cinematic-motion-and-facial-candidate",
        "freeOnly": True,
        "geometryPreserved": True,
        "vertices": before_vertices,
        "polygons": before_polygons,
        "actionCount": len(REQUIRED),
        "actions": REQUIRED,
        "sparseAnimationExport": True,
        "facialControls": list(FACE_BONES),
        "facialWeighting": facial_qc,
        "visemeModel": "jaw rotation + localized muzzle scale",
        "blinkModel": "localized eye-region scale",
        "eyeSaccadeModel": "localized eye-region rotation",
        "productionGate": "CLOSED",
        "remainingGates": [
            "visual facial deformation review against approved reference",
            "PBR/fur/uniform material transfer and visual review",
            "Arabic lip-sync timing review with real speech",
            "physical Android 30-minute acceptance",
        ],
    }
    a.qc.write_text(json.dumps(qc, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(qc, indent=2), flush=True)


if __name__ == "__main__":
    main()
