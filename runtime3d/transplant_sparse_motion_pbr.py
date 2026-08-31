#!/usr/bin/env python3
"""Transplant validated sparse Motion V2 animations onto a PBR GLB without Blender rebaking.

Blender's glTF re-export can force-sample every armature bone into every action, which
breaks the runtime's BODY/GAZE/FACE layering contract even when the source actions are
sparse. This tool keeps the already validated PBR geometry/material payload, copies only
the animation accessors/bufferViews from the validated Motion V2 source, remaps animation
channels by stable node name, and refuses to proceed unless the animated skeleton rest
pose is equivalent within a strict tolerance.
"""
from __future__ import annotations

import argparse
import copy
import json
import struct
from pathlib import Path
from typing import Any

JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942
MAGIC = 0x46546C67


def read_glb(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    if len(raw) < 20:
        raise RuntimeError(f"GLB too small: {path}")
    magic, version, total = struct.unpack_from("<III", raw, 0)
    if magic != MAGIC or version != 2 or total != len(raw):
        raise RuntimeError(f"Invalid GLB header: {path}")
    offset = 12
    document: dict[str, Any] | None = None
    binary = b""
    while offset < len(raw):
        length, chunk_type = struct.unpack_from("<II", raw, offset)
        offset += 8
        chunk = raw[offset:offset + length]
        offset += length
        if chunk_type == JSON_CHUNK:
            document = json.loads(chunk.decode("utf-8").rstrip("\x00 \t\r\n"))
        elif chunk_type == BIN_CHUNK:
            binary = chunk
    if document is None:
        raise RuntimeError("Missing JSON chunk")
    return document, binary


def write_glb(path: Path, document: dict[str, Any], binary: bytes) -> None:
    binary = bytes(binary)
    document.setdefault("buffers", [{}])
    if len(document["buffers"]) != 1:
        raise RuntimeError("Only single-buffer GLB is supported")
    document["buffers"][0]["byteLength"] = len(binary)
    document["buffers"][0].pop("uri", None)

    json_bytes = json.dumps(document, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    json_bytes += b" " * ((-len(json_bytes)) % 4)
    binary_bytes = binary + b"\x00" * ((-len(binary)) % 4)
    total = 12 + 8 + len(json_bytes) + (8 + len(binary_bytes) if binary_bytes else 0)
    output = bytearray(struct.pack("<III", MAGIC, 2, total))
    output += struct.pack("<II", len(json_bytes), JSON_CHUNK) + json_bytes
    if binary_bytes:
        output += struct.pack("<II", len(binary_bytes), BIN_CHUNK) + binary_bytes
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(output)


def node_name_map(document: dict[str, Any]) -> dict[str, int]:
    result: dict[str, int] = {}
    for index, node in enumerate(document.get("nodes") or []):
        name = node.get("name")
        if not name:
            continue
        if name in result:
            raise RuntimeError(f"Duplicate node name: {name}")
        result[name] = index
    return result


def parent_map(document: dict[str, Any]) -> dict[int, int]:
    result: dict[int, int] = {}
    for index, node in enumerate(document.get("nodes") or []):
        for child in node.get("children") or []:
            if child in result:
                raise RuntimeError(f"Node {child} has multiple parents")
            result[child] = index
    return result


def default_trs(node: dict[str, Any], key: str) -> list[float]:
    if key == "translation":
        return node.get(key, [0.0, 0.0, 0.0])
    if key == "scale":
        return node.get(key, [1.0, 1.0, 1.0])
    if key == "rotation":
        return node.get(key, [0.0, 0.0, 0.0, 1.0])
    raise KeyError(key)


def max_abs(left: list[float], right: list[float]) -> float:
    return max((abs(float(a) - float(b)) for a, b in zip(left, right)), default=0.0)


def quaternion_diff(left: list[float], right: list[float]) -> float:
    direct = max_abs(left, right)
    negated = max_abs(left, [-float(value) for value in right])
    return min(direct, negated)


def validate_skeleton_equivalence(
    source: dict[str, Any],
    target: dict[str, Any],
    animated_nodes: set[int],
    tolerance: float = 1e-5,
) -> dict[str, Any]:
    source_names = node_name_map(source)
    target_names = node_name_map(target)
    source_parents = parent_map(source)
    target_parents = parent_map(target)
    max_delta = 0.0
    checked: list[str] = []

    for source_index in sorted(animated_nodes):
        source_node = source["nodes"][source_index]
        name = source_node.get("name")
        if not name or name not in target_names:
            raise RuntimeError(f"Animated source node missing in PBR skeleton: {name or source_index}")
        target_index = target_names[name]
        target_node = target["nodes"][target_index]

        source_parent = source["nodes"][source_parents[source_index]].get("name") if source_index in source_parents else None
        target_parent = target["nodes"][target_parents[target_index]].get("name") if target_index in target_parents else None
        if source_parent != target_parent:
            raise RuntimeError(f"Parent mismatch for {name}: {source_parent} != {target_parent}")

        translation_delta = max_abs(default_trs(source_node, "translation"), default_trs(target_node, "translation"))
        scale_delta = max_abs(default_trs(source_node, "scale"), default_trs(target_node, "scale"))
        rotation_delta = quaternion_diff(default_trs(source_node, "rotation"), default_trs(target_node, "rotation"))
        delta = max(translation_delta, scale_delta, rotation_delta)
        max_delta = max(max_delta, delta)
        if delta > tolerance:
            raise RuntimeError(f"Rest transform mismatch for {name}: {delta} > {tolerance}")
        checked.append(name)

    return {
        "checkedAnimatedNodes": len(checked),
        "maxRestTransformDelta": max_delta,
        "tolerance": tolerance,
    }


def append_aligned(target: bytearray, payload: bytes, alignment: int = 4) -> int:
    padding = (-len(target)) % alignment
    if padding:
        target.extend(b"\x00" * padding)
    offset = len(target)
    target.extend(payload)
    return offset


def transplant_animations(
    source_document: dict[str, Any],
    source_binary: bytes,
    target_document: dict[str, Any],
    target_binary: bytes,
) -> tuple[bytes, dict[str, Any]]:
    source_accessors = source_document.get("accessors") or []
    source_views = source_document.get("bufferViews") or []
    target_document.setdefault("accessors", [])
    target_document.setdefault("bufferViews", [])
    view_map: dict[int, int] = {}
    accessor_map: dict[int, int] = {}
    output_binary = bytearray(target_binary)

    def copy_view(source_index: int) -> int:
        if source_index in view_map:
            return view_map[source_index]
        if not 0 <= source_index < len(source_views):
            raise RuntimeError(f"Invalid source bufferView {source_index}")
        view = copy.deepcopy(source_views[source_index])
        if int(view.get("buffer", 0)) != 0:
            raise RuntimeError("Animation bufferView is not in GLB buffer 0")
        start = int(view.get("byteOffset", 0))
        length = int(view.get("byteLength", 0))
        payload = source_binary[start:start + length]
        if len(payload) != length:
            raise RuntimeError(f"Truncated source bufferView {source_index}")
        new_offset = append_aligned(output_binary, payload, 4)
        view["buffer"] = 0
        view["byteOffset"] = new_offset
        new_index = len(target_document["bufferViews"])
        target_document["bufferViews"].append(view)
        view_map[source_index] = new_index
        return new_index

    def copy_accessor(source_index: int) -> int:
        if source_index in accessor_map:
            return accessor_map[source_index]
        if not 0 <= source_index < len(source_accessors):
            raise RuntimeError(f"Invalid source accessor {source_index}")
        accessor = copy.deepcopy(source_accessors[source_index])
        if isinstance(accessor.get("bufferView"), int):
            accessor["bufferView"] = copy_view(accessor["bufferView"])
        sparse = accessor.get("sparse")
        if isinstance(sparse, dict):
            for key in ("indices", "values"):
                part = sparse.get(key)
                if isinstance(part, dict) and isinstance(part.get("bufferView"), int):
                    part["bufferView"] = copy_view(part["bufferView"])
        new_index = len(target_document["accessors"])
        target_document["accessors"].append(accessor)
        accessor_map[source_index] = new_index
        return new_index

    target_names = node_name_map(target_document)
    animations = copy.deepcopy(source_document.get("animations") or [])
    if not animations:
        raise RuntimeError("Motion source contains no animations")
    animated_nodes: set[int] = set()
    channel_count = 0

    for animation in animations:
        for sampler in animation.get("samplers") or []:
            sampler["input"] = copy_accessor(int(sampler["input"]))
            sampler["output"] = copy_accessor(int(sampler["output"]))
        for channel in animation.get("channels") or []:
            target = channel.get("target") or {}
            source_node_index = target.get("node")
            if not isinstance(source_node_index, int):
                raise RuntimeError(f"Animation {animation.get('name')} has a non-node target")
            animated_nodes.add(source_node_index)
            node_name = source_document["nodes"][source_node_index].get("name")
            if not node_name or node_name not in target_names:
                raise RuntimeError(f"Cannot remap animated node {source_node_index}: {node_name}")
            target["node"] = target_names[node_name]
            channel_count += 1

    skeleton_qc = validate_skeleton_equivalence(source_document, target_document, animated_nodes)
    target_document["animations"] = animations
    return bytes(output_binary), {
        "animationCount": len(animations),
        "channelCount": channel_count,
        "copiedAccessors": len(accessor_map),
        "copiedBufferViews": len(view_map),
        **skeleton_qc,
    }


def tune_materials(
    document: dict[str, Any],
    metallic: float,
    roughness: float,
    normal_scale: float,
) -> dict[str, Any]:
    materials = document.get("materials") or []
    if not materials:
        raise RuntimeError("PBR source has no materials")
    base_color_present = False
    normal_present = False
    touched = 0
    for material in materials:
        pbr = material.setdefault("pbrMetallicRoughness", {})
        if isinstance((pbr.get("baseColorTexture") or {}).get("index"), int):
            base_color_present = True
        pbr["metallicFactor"] = metallic
        pbr["roughnessFactor"] = roughness
        normal = material.get("normalTexture")
        if isinstance(normal, dict) and isinstance(normal.get("index"), int):
            normal["scale"] = normal_scale
            normal_present = True
        touched += 1
    if not base_color_present or not normal_present:
        raise RuntimeError("Expected BaseColor and Normal textures are missing")
    return {
        "materialsTuned": touched,
        "metallicFactor": metallic,
        "roughnessFactor": roughness,
        "normalScale": normal_scale,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--motion-source", type=Path, required=True)
    parser.add_argument("--pbr-source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--metallic", type=float, default=0.0)
    parser.add_argument("--roughness", type=float, default=0.68)
    parser.add_argument("--normal-scale", type=float, default=0.28)
    args = parser.parse_args()

    source_document, source_binary = read_glb(args.motion_source)
    target_document, target_binary = read_glb(args.pbr_source)
    if len(source_document.get("animations") or []) != 26:
        raise RuntimeError("Motion source must contain exactly 26 animations")

    output_binary, motion_qc = transplant_animations(
        source_document,
        source_binary,
        target_document,
        target_binary,
    )
    material_qc = tune_materials(
        target_document,
        args.metallic,
        args.roughness,
        args.normal_scale,
    )
    write_glb(args.output, target_document, output_binary)

    output_document, _ = read_glb(args.output)
    animation_names = [animation.get("name") for animation in output_document.get("animations") or []]
    qc = {
        "productionReady": False,
        "purpose": "sparse-motion-transplant-onto-embedded-pbr",
        "sourceMotion": args.motion_source.name,
        "sourcePbr": args.pbr_source.name,
        "outputBytes": args.output.stat().st_size,
        "animationNames": animation_names,
        "textures": len(output_document.get("textures") or []),
        "images": len(output_document.get("images") or []),
        "skins": len(output_document.get("skins") or []),
        "motion": motion_qc,
        "material": material_qc,
        "productionGate": "CLOSED",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(qc, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(qc, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
