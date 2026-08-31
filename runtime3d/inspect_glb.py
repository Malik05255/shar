#!/usr/bin/env python3
"""Strict local preflight for Al-Shorti runtime GLBs.

This does not judge artistic quality; it prevents structurally incomplete assets from being
published as production content. It parses the JSON chunk of binary glTF without third-party
packages so it can run in CI/build machines.
"""

import argparse
import json
import struct
import sys
from pathlib import Path

MAGIC = 0x46546C67  # glTF
JSON_CHUNK = 0x4E4F534A

DOG_CLIPS = {
    "IdleWork", "Breathing", "Blink", "EyeSaccade",
    "LookAtDesk", "LookAtMonitor", "LookAtCamera", "LookAtDoor", "LookAtStaff",
    "ReachFile", "ReviewFile", "TurnPage", "WriteNote", "SetFileDown",
    "UsePhone", "Listen", "Talk", "StandUp", "SitDown", "Walk", "LeanBack",
}
STAFF_CLIPS = {
    "IdleDesk", "Breathing", "Blink", "Type", "Read", "Write",
    "TalkToStaff", "ListenToStaff", "GestureSmall", "HeadNod",
    "Walk", "WalkCarryFile", "CarryFile", "StandUp", "SitDown",
    "UsePhone", "Drink", "OpenDoor", "CloseDoor",
}
PROP_CLIPS = {
    "door.glb": {"OpenDoor", "CloseDoor", "Idle"},
    "phone.glb": {"Ring", "Idle"},
    "file.glb": {"Idle", "MoveToDesk", "MoveToHand"},
    "chair.glb": {"Idle", "Shift", "Turn"},
    "printer.glb": {"Idle", "Print"},
    "coffee_cup.glb": {"Idle", "MoveToHand", "MoveToDesk"},
}
STATIC_FILES = {
    "office_shell.glb", "desk.glb", "monitor.glb", "keyboard.glb",
}


def fail(path: Path, message: str) -> None:
    print(f"{path.name}: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_glb_json(path: Path) -> dict:
    raw = path.read_bytes()
    if len(raw) < 20:
        fail(path, "file is too small to be a GLB")
    magic, version, declared_length = struct.unpack_from("<III", raw, 0)
    if magic != MAGIC:
        fail(path, "invalid GLB magic")
    if version != 2:
        fail(path, f"glTF version must be 2, got {version}")
    if declared_length != len(raw):
        fail(path, f"declared GLB length {declared_length} != file length {len(raw)}")

    offset = 12
    while offset + 8 <= len(raw):
        chunk_length, chunk_type = struct.unpack_from("<II", raw, offset)
        offset += 8
        end = offset + chunk_length
        if end > len(raw):
            fail(path, "chunk exceeds GLB length")
        if chunk_type == JSON_CHUNK:
            try:
                return json.loads(raw[offset:end].rstrip(b" \t\r\n\x00").decode("utf-8"))
            except Exception as exc:
                fail(path, f"invalid glTF JSON chunk: {exc}")
        offset = end
    fail(path, "missing glTF JSON chunk")
    raise AssertionError


def required_clips(filename: str) -> set[str]:
    if filename == "police_dog.glb":
        return DOG_CLIPS
    if filename.startswith("staff_") or filename == "visitor_01.glb":
        return STAFF_CLIPS
    return PROP_CLIPS.get(filename, set())


def inspect(path: Path) -> None:
    doc = read_glb_json(path)
    asset_version = str(doc.get("asset", {}).get("version", ""))
    if not asset_version.startswith("2"):
        fail(path, "asset.version is not glTF 2.x")

    meshes = doc.get("meshes") or []
    if not meshes:
        fail(path, "contains no meshes")
    if not (doc.get("materials") or []):
        fail(path, "contains no materials")

    required = required_clips(path.name)
    names = {str(item.get("name", "")).strip() for item in (doc.get("animations") or [])}
    missing = sorted(required - names)
    if missing:
        fail(path, "missing required animation clips: " + ", ".join(missing))

    is_character = path.name == "police_dog.glb" or path.name.startswith("staff_") or path.name == "visitor_01.glb"
    if is_character:
        skins = doc.get("skins") or []
        if not skins:
            fail(path, "character has no skin/rig")
        joint_count = max((len(skin.get("joints") or []) for skin in skins), default=0)
        if joint_count < 12:
            fail(path, f"character rig is implausibly small ({joint_count} joints)")

    if path.name in STATIC_FILES and doc.get("animations"):
        print(f"warning: {path.name} is static but contains animations", file=sys.stderr)

    print(
        f"{path.name}: OK meshes={len(meshes)} materials={len(doc.get('materials') or [])} "
        f"animations={len(doc.get('animations') or [])} skins={len(doc.get('skins') or [])}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.paths:
        if not path.is_file():
            fail(path, "file does not exist")
        inspect(path)


if __name__ == "__main__":
    main()
