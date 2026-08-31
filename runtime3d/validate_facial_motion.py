#!/usr/bin/env python3
"""Strict GLB gate for layer-safe facial motion used by Runtime3DOfficeStage.

The runtime applies BODY, autonomic GAZE/BLINK, then FACE sequentially. Therefore facial
clips must be sparse: a viseme that contains pelvis/spine/limb transforms can overwrite
the body pose even if the animation name is correct. This validator rejects that class
of failure and verifies that the formerly zero-weight facial controls actually influence
geometry.
"""
from __future__ import annotations

import argparse
import json
import math
import struct
from pathlib import Path
from typing import Any

COMPONENT = {
    5120: ("b", 1),
    5121: ("B", 1),
    5122: ("h", 2),
    5123: ("H", 2),
    5125: ("I", 4),
    5126: ("f", 4),
}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
VISEMES = ["VisemeRest", "VisemeOpen", "VisemeWide", "VisemeRound", "VisemeClosed"]
FACIAL_NODES = {"jaw", "muzzle_ctrl", "eye.L", "eye.R"}
VISEME_NODES = {"jaw", "muzzle_ctrl"}
EYE_NODES = {"eye.L", "eye.R"}


def load_glb(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    if len(raw) < 20:
        raise RuntimeError("GLB too small")
    magic, version, total = struct.unpack_from("<III", raw, 0)
    if magic != 0x46546C67 or version != 2 or total != len(raw):
        raise RuntimeError("Invalid GLB header")
    off = 12
    document = None
    binary = b""
    while off < len(raw):
        length, chunk_type = struct.unpack_from("<II", raw, off)
        off += 8
        chunk = raw[off:off + length]
        off += length
        if chunk_type == 0x4E4F534A:
            document = json.loads(chunk.decode("utf-8").rstrip("\x00 \t\r\n"))
        elif chunk_type == 0x004E4942:
            binary = chunk
    if document is None:
        raise RuntimeError("GLB JSON chunk missing")
    return document, binary


def read_accessor(doc: dict[str, Any], binary: bytes, index: int) -> list[tuple[float, ...]]:
    accessor = doc["accessors"][index]
    view = doc["bufferViews"][accessor["bufferView"]]
    fmt, scalar_bytes = COMPONENT[accessor["componentType"]]
    components = NCOMP[accessor["type"]]
    count = int(accessor["count"])
    base = int(view.get("byteOffset", 0)) + int(accessor.get("byteOffset", 0))
    stride = int(view.get("byteStride", scalar_bytes * components))
    out: list[tuple[float, ...]] = []
    for i in range(count):
        pos = base + i * stride
        values = struct.unpack_from("<" + fmt * components, binary, pos)
        out.append(tuple(float(v) for v in values))
    return out


def max_component_span(values: list[tuple[float, ...]]) -> float:
    if not values:
        return 0.0
    width = len(values[0])
    return max(max(v[j] for v in values) - min(v[j] for v in values) for j in range(width))


def first_channel_value(doc: dict[str, Any], binary: bytes, animation: dict[str, Any],
                        node_name: str, path: str) -> tuple[float, ...] | None:
    nodes = doc.get("nodes") or []
    for channel in animation.get("channels") or []:
        target = channel.get("target") or {}
        node_index = target.get("node")
        name = nodes[node_index].get("name") if isinstance(node_index, int) and node_index < len(nodes) else None
        if name == node_name and target.get("path") == path:
            sampler = animation["samplers"][channel["sampler"]]
            values = read_accessor(doc, binary, sampler["output"])
            return values[0] if values else None
    return None


def channel_span(doc: dict[str, Any], binary: bytes, animation: dict[str, Any],
                 node_name: str, path: str) -> float:
    nodes = doc.get("nodes") or []
    for channel in animation.get("channels") or []:
        target = channel.get("target") or {}
        node_index = target.get("node")
        name = nodes[node_index].get("name") if isinstance(node_index, int) and node_index < len(nodes) else None
        if name == node_name and target.get("path") == path:
            sampler = animation["samplers"][channel["sampler"]]
            return max_component_span(read_accessor(doc, binary, sampler["output"]))
    return 0.0


def distance(a: tuple[float, ...] | None, b: tuple[float, ...] | None) -> float:
    if a is None or b is None or len(a) != len(b):
        return math.inf
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("glb", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    doc, binary = load_glb(args.glb)
    nodes = doc.get("nodes") or []
    animations = {a.get("name"): a for a in (doc.get("animations") or []) if a.get("name")}
    missing = [name for name in VISEMES + ["Blink", "EyeSaccade"] if name not in animations]
    if missing:
        raise SystemExit(f"Missing facial animations: {missing}")

    # Layer safety: FACE clips may only touch jaw/muzzle; blink/saccade may only touch eyes.
    clip_targets: dict[str, list[dict[str, str]]] = {}
    violations: dict[str, list[str]] = {}
    for clip_name in VISEMES + ["Blink", "EyeSaccade"]:
        animation = animations[clip_name]
        targets = []
        for channel in animation.get("channels") or []:
            target = channel.get("target") or {}
            node_index = target.get("node")
            node_name = nodes[node_index].get("name") if isinstance(node_index, int) and node_index < len(nodes) else "<unknown>"
            targets.append({"node": node_name, "path": str(target.get("path"))})
        clip_targets[clip_name] = targets
        allowed = VISEME_NODES if clip_name in VISEMES else EYE_NODES
        bad = sorted({t["node"] for t in targets if t["node"] not in allowed})
        if bad:
            violations[clip_name] = bad
    if violations:
        raise SystemExit(f"Facial animation layering violation: {violations}")

    for name in VISEMES:
        target_nodes = {t["node"] for t in clip_targets[name]}
        if not VISEME_NODES.issubset(target_nodes):
            raise SystemExit(f"{name} must target both jaw and muzzle_ctrl: {sorted(target_nodes)}")
    for name in ("Blink", "EyeSaccade"):
        target_nodes = {t["node"] for t in clip_targets[name]}
        if not EYE_NODES.issubset(target_nodes):
            raise SystemExit(f"{name} must target both eye controls: {sorted(target_nodes)}")

    # Skin influence evidence for the facial controls.
    skins = doc.get("skins") or []
    if len(skins) != 1:
        raise SystemExit(f"Expected one skin, got {len(skins)}")
    joint_nodes = skins[0].get("joints") or []
    joint_names = [nodes[i].get("name") for i in joint_nodes]
    joint_name_by_index = {i: name for i, name in enumerate(joint_names)}
    influence_counts = {name: 0 for name in FACIAL_NODES}
    influence_sums = {name: 0.0 for name in FACIAL_NODES}
    for mesh in doc.get("meshes") or []:
        for primitive in mesh.get("primitives") or []:
            attrs = primitive.get("attributes") or {}
            if "JOINTS_0" not in attrs or "WEIGHTS_0" not in attrs:
                continue
            joints = read_accessor(doc, binary, attrs["JOINTS_0"])
            weights = read_accessor(doc, binary, attrs["WEIGHTS_0"])
            for row_j, row_w in zip(joints, weights):
                for joint_raw, weight in zip(row_j, row_w):
                    if weight <= 1e-5:
                        continue
                    name = joint_name_by_index.get(int(joint_raw))
                    if name in influence_counts:
                        influence_counts[name] += 1
                        influence_sums[name] += weight

    floors = {"jaw": 1000, "muzzle_ctrl": 500, "eye.L": 100, "eye.R": 100}
    failed_influences = {name: influence_counts.get(name, 0) for name, floor in floors.items()
                         if influence_counts.get(name, 0) < floor}
    if failed_influences:
        raise SystemExit(f"Facial skin influence floor failed: {failed_influences}")

    # Blink must actually close/open by changing eye scale through the clip.
    blink = animations["Blink"]
    blink_spans = {eye: channel_span(doc, binary, blink, eye, "scale") for eye in EYE_NODES}
    if min(blink_spans.values()) < 0.25:
        raise SystemExit(f"Blink deformation too small: {blink_spans}")

    # Saccade must animate independent eye rotations rather than the head.
    saccade = animations["EyeSaccade"]
    saccade_spans = {eye: channel_span(doc, binary, saccade, eye, "rotation") for eye in EYE_NODES}
    if min(saccade_spans.values()) < 0.01:
        raise SystemExit(f"EyeSaccade deformation too small: {saccade_spans}")

    # Every viseme is a static facial pose, but the requested poses must be genuinely distinct.
    signatures = {}
    for name in VISEMES:
        anim = animations[name]
        signatures[name] = {
            "jawRotation": first_channel_value(doc, binary, anim, "jaw", "rotation"),
            "muzzleScale": first_channel_value(doc, binary, anim, "muzzle_ctrl", "scale"),
        }
    rest = signatures["VisemeRest"]
    for name in VISEMES[1:]:
        sig = signatures[name]
        combined = distance(sig["jawRotation"], rest["jawRotation"]) + distance(sig["muzzleScale"], rest["muzzleScale"])
        if combined < 0.025:
            raise SystemExit(f"{name} is not sufficiently distinct from VisemeRest: {combined}")
    if distance(signatures["VisemeWide"]["muzzleScale"], signatures["VisemeRound"]["muzzleScale"]) < 0.10:
        raise SystemExit("VisemeWide and VisemeRound muzzle silhouettes are not distinct enough")

    report = {
        "pass": True,
        "glbBytes": args.glb.stat().st_size,
        "animationCount": len(animations),
        "facialLayerSafe": True,
        "facialTargets": clip_targets,
        "influenceCounts": influence_counts,
        "influenceWeightSums": influence_sums,
        "blinkScaleSpan": blink_spans,
        "eyeSaccadeRotationSpan": saccade_spans,
        "visemeSignatures": signatures,
        "productionReady": False,
        "productionGate": "CLOSED",
    }
    text = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    print(text, end="")


if __name__ == "__main__":
    main()
