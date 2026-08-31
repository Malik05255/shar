#!/usr/bin/env python3
"""Blender 4 compatible entrypoint for reusable office prop authoring.

Besides forwarding only arguments after `--`, this patches the one Blender API incompatibility in
our authoring module: Blender 4.0 requires NlaStrips.new(start=...) to receive an integer frame.
"""
from __future__ import annotations

from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

raw = list(sys.argv)
forwarded = raw[raw.index("--") + 1:] if "--" in raw else []
sys.argv = [str(SCRIPT_DIR / "blender_author_office_props.py"), *forwarded]

import blender_author_office_props as author


def action_track_compat(obj, name: str, duration_frames: int, location_keys=None, rotation_keys=None, scale_keys=None):
    action = author.bpy.data.actions.new(name)
    action.use_fake_user = True
    if location_keys:
        for axis in range(3):
            curve = action.fcurves.new(data_path='location', index=axis)
            for frame, value in location_keys:
                curve.keyframe_points.insert(frame, value[axis], options={'FAST'})
    if rotation_keys:
        obj.rotation_mode = 'XYZ'
        for axis in range(3):
            curve = action.fcurves.new(data_path='rotation_euler', index=axis)
            for frame, value in rotation_keys:
                curve.keyframe_points.insert(frame, value[axis], options={'FAST'})
    if scale_keys:
        for axis in range(3):
            curve = action.fcurves.new(data_path='scale', index=axis)
            for frame, value in scale_keys:
                curve.keyframe_points.insert(frame, value[axis], options={'FAST'})
    obj.animation_data_create()
    track = obj.animation_data.nla_tracks.new()
    track.name = name
    track.strips.new(name, 1, action)
    return action


author.action_track = action_track_compat

author.main()
