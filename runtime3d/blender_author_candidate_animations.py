#!/usr/bin/env python3
"""Author a non-production motion/viseme candidate on the free Blender rig.

This script is deliberately conservative: it does not decimate/remesh the hero and it
never marks the result production-ready. It creates the exact runtime clip names needed
for integration tests using the existing body/jaw rig. Facial clips that require true
muzzle/eyelid deformation remain explicitly flagged for later cinematic facial authoring.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

REQUIRED = [
    "IdleWork", "Breathing", "Blink", "EyeSaccade", "LookAtDesk", "LookAtMonitor",
    "LookAtCamera", "LookAtDoor", "LookAtStaff", "ReachFile", "ReviewFile", "TurnPage",
    "WriteNote", "SetFileDown", "UsePhone", "Listen", "Talk", "StandUp", "SitDown",
    "Walk", "LeanBack", "VisemeRest", "VisemeOpen", "VisemeWide", "VisemeRound",
    "VisemeClosed",
]


def args() -> argparse.Namespace:
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--qc", type=Path, required=True)
    return p.parse_args(argv)


def clear_pose(arm: bpy.types.Object) -> None:
    for pb in arm.pose.bones:
        pb.rotation_mode = "XYZ"
        pb.rotation_euler = (0.0, 0.0, 0.0)
        pb.location = (0.0, 0.0, 0.0)
        pb.scale = (1.0, 1.0, 1.0)


def key(arm: bpy.types.Object, frame: int, rotations: dict[str, tuple[float, float, float]] | None = None,
        locations: dict[str, tuple[float, float, float]] | None = None) -> None:
    rotations = rotations or {}
    locations = locations or {}
    for name, rot in rotations.items():
        pb = arm.pose.bones.get(name)
        if not pb:
            raise RuntimeError(f"Missing required pose bone: {name}")
        pb.rotation_mode = "XYZ"
        pb.rotation_euler = tuple(math.radians(v) for v in rot)
        pb.keyframe_insert(data_path="rotation_euler", frame=frame, group=name)
    for name, loc in locations.items():
        pb = arm.pose.bones.get(name)
        if not pb:
            raise RuntimeError(f"Missing required pose bone: {name}")
        pb.location = loc
        pb.keyframe_insert(data_path="location", frame=frame, group=name)


def action(arm: bpy.types.Object, name: str, end: int, keys: list[tuple[int, dict, dict]]):
    clear_pose(arm)
    act = bpy.data.actions.new(name=name)
    act.use_fake_user = True
    arm.animation_data_create()
    arm.animation_data.action = act
    for frame, rots, locs in keys:
        key(arm, frame, rots, locs)
    for fc in act.fcurves:
        for kp in fc.keyframe_points:
            kp.interpolation = "BEZIER"
    act.frame_range = (1, end)
    # Put every action on its own muted NLA track so glTF can export named clips.
    track = arm.animation_data.nla_tracks.new()
    track.name = name
    strip = track.strips.new(name, 1, act)
    strip.name = name
    track.mute = True
    arm.animation_data.action = None
    return act


def main() -> None:
    a = args()
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

    # Micro-motion / gaze. Blender uses Z-up internally; rotations are local bone rotations.
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
    # Controls exist in the rig but do not yet provide cinematic eyelid/muzzle deformation.
    action(arm, "Blink", 12, [(1, {}, {}), (6, {"head": (0.3, 0, 0)}, {}), (12, {}, {})])
    action(arm, "EyeSaccade", 30, [(1, {"head": (0, 0, -0.5)}, {}), (10, {"head": (0, 0, 0.8)}, {}), (30, {"head": (0, 0, -0.5)}, {})])

    look_specs = {
        "LookAtDesk": ((14, 0, 0), (5, 0, 0)),
        "LookAtMonitor": ((4, 0, -8), (2, 0, -3)),
        "LookAtCamera": ((-2, 0, 0), (0, 0, 0)),
        "LookAtDoor": ((0, 0, 22), (0, 0, 8)),
        "LookAtStaff": ((0, 0, -18), (0, 0, -6)),
    }
    for name, (head_rot, chest_rot) in look_specs.items():
        action(arm, name, 24, [(1, {}, {}), (12, {"head": head_rot, "chest": chest_rot}, {}), (24, {"head": head_rot, "chest": chest_rot}, {})])

    # Desk/hand work clips.
    action(arm, "ReachFile", 36, [(1, {}, {}), (36, {"upper_arm.R": (22, 0, -18), "forearm.R": (-34, 0, 4), "hand.R": (10, 0, 0)}, {})])
    action(arm, "ReviewFile", 70, [(1, {"head": (10, 0, 0), "forearm.L": (-20, 0, 0), "forearm.R": (-20, 0, 0)}, {}), (35, {"head": (13, 0, -2), "forearm.L": (-23, 0, 0), "forearm.R": (-18, 0, 0)}, {}), (70, {"head": (10, 0, 0), "forearm.L": (-20, 0, 0), "forearm.R": (-20, 0, 0)}, {})])
    action(arm, "TurnPage", 35, [(1, {}, {}), (18, {"forearm.R": (-26, 0, -8), "hand.R": (12, 0, -15)}, {}), (35, {"forearm.R": (-18, 0, 4), "hand.R": (0, 0, 5)}, {})])
    action(arm, "WriteNote", 70, [(1, {"upper_arm.R": (14, 0, -10), "forearm.R": (-38, 0, 2)}, {}), (20, {"hand.R": (5, 0, -8)}, {}), (40, {"hand.R": (-5, 0, 8)}, {}), (70, {"hand.R": (5, 0, -8)}, {})])
    action(arm, "SetFileDown", 35, [(1, {"upper_arm.R": (18, 0, -12), "forearm.R": (-32, 0, 0)}, {}), (35, {"upper_arm.R": (5, 0, -4), "forearm.R": (-8, 0, 0)}, {})])
    action(arm, "UsePhone", 60, [(1, {}, {}), (30, {"upper_arm.R": (-8, 0, -55), "forearm.R": (-92, 0, 5), "hand.R": (25, 0, -10), "head": (0, 0, 4)}, {}), (60, {"upper_arm.R": (-8, 0, -55), "forearm.R": (-92, 0, 5), "hand.R": (25, 0, -10), "head": (0, 0, 4)}, {})])

    action(arm, "Listen", 80, [(1, {"head": (0, 0, -2), "ear.L": (0, 0, 1), "ear.R": (0, 0, -1)}, {}), (40, {"head": (-2, 0, 2), "ear.L": (0, 0, -1), "ear.R": (0, 0, 1)}, {}), (80, {"head": (0, 0, -2), "ear.L": (0, 0, 1), "ear.R": (0, 0, -1)}, {})])
    action(arm, "Talk", 80, [(1, {"head": (0, 0, -1), "chest": (0, 0, 0)}, {}), (20, {"head": (-2, 0, 2), "chest": (1, 0, -1)}, {}), (40, {"head": (1, 0, -2), "chest": (-1, 0, 1)}, {}), (80, {"head": (0, 0, -1), "chest": (0, 0, 0)}, {})])

    # Locomotion/posture candidate clips.
    action(arm, "SitDown", 45, [(1, {}, {}), (45, {"thigh.L": (-55, 0, 0), "thigh.R": (-55, 0, 0), "shin.L": (72, 0, 0), "shin.R": (72, 0, 0), "spine_01": (8, 0, 0)}, {"pelvis": (0, 0, -0.22)})])
    action(arm, "StandUp", 45, [(1, {"thigh.L": (-55, 0, 0), "thigh.R": (-55, 0, 0), "shin.L": (72, 0, 0), "shin.R": (72, 0, 0), "spine_01": (8, 0, 0)}, {"pelvis": (0, 0, -0.22)}), (45, {}, {"pelvis": (0, 0, 0)})])
    action(arm, "LeanBack", 50, [(1, {}, {}), (25, {"spine_01": (-6, 0, 0), "spine_02": (-5, 0, 0), "head": (4, 0, 0)}, {}), (50, {}, {})])
    action(arm, "Walk", 48, [
        (1, {"thigh.L": (20, 0, 0), "thigh.R": (-20, 0, 0), "shin.L": (-12, 0, 0), "shin.R": (25, 0, 0), "upper_arm.L": (-10, 0, 0), "upper_arm.R": (10, 0, 0)}, {}),
        (24, {"thigh.L": (-20, 0, 0), "thigh.R": (20, 0, 0), "shin.L": (25, 0, 0), "shin.R": (-12, 0, 0), "upper_arm.L": (10, 0, 0), "upper_arm.R": (-10, 0, 0)}, {}),
        (48, {"thigh.L": (20, 0, 0), "thigh.R": (-20, 0, 0), "shin.L": (-12, 0, 0), "shin.R": (25, 0, 0), "upper_arm.L": (-10, 0, 0), "upper_arm.R": (10, 0, 0)}, {}),
    ])

    # Jaw-based viseme candidates. These are usable for motion tests, not final cinematic lip-sync.
    vis = {
        "VisemeRest": 0,
        "VisemeOpen": 16,
        "VisemeWide": 9,
        "VisemeRound": 12,
        "VisemeClosed": -2,
    }
    for name, jaw_deg in vis.items():
        action(arm, name, 2, [(1, {"jaw": (jaw_deg, 0, 0)}, {}), (2, {"jaw": (jaw_deg, 0, 0)}, {})])

    # No geometry reduction is allowed during authoring.
    after_vertices = sum(len(o.data.vertices) for o in meshes)
    after_polygons = sum(len(o.data.polygons) for o in meshes)
    if (before_vertices, before_polygons) != (after_vertices, after_polygons):
        raise RuntimeError(f"Geometry changed during animation authoring: {(before_vertices, before_polygons)} -> {(after_vertices, after_polygons)}")

    names = sorted(a.name for a in bpy.data.actions)
    missing = [n for n in REQUIRED if n not in names]
    if missing:
        raise RuntimeError(f"Missing authored actions: {missing}")

    # Unmute NLA tracks for export; the exporter emits strips as named clips.
    if arm.animation_data:
        for track in arm.animation_data.nla_tracks:
            track.mute = False
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.export_scene.gltf(
        filepath=str(a.output), export_format="GLB", use_selection=False,
        export_skins=True, export_animations=True, export_nla_strips=True,
        export_apply=False, export_yup=True,
    )

    qc = {
        "productionReady": False,
        "purpose": "motion-and-runtime-integration-candidate",
        "freeOnly": True,
        "geometryPreserved": True,
        "vertices": before_vertices,
        "polygons": before_polygons,
        "actionCount": len(REQUIRED),
        "actions": REQUIRED,
        "jawVisemesAuthored": ["VisemeRest", "VisemeOpen", "VisemeWide", "VisemeRound", "VisemeClosed"],
        "knownNonProductionGaps": [
            "true eyelid blink deformation is not yet authored",
            "eye saccade currently uses subtle head control because independent eyeball deformation is not yet available",
            "muzzle/cheek/tongue facial deformation is not yet authored",
            "hero has no approved cinematic PBR/fur/uniform materials yet",
            "body animation requires visual deformation review before acceptance",
        ],
        "productionGate": "CLOSED",
    }
    a.qc.write_text(json.dumps(qc, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(qc, indent=2))


if __name__ == "__main__":
    main()
