#!/usr/bin/env python3
"""Author target-owned procedural PBR for the exact Motion V2 hero.

This is deliberately independent of donor UV islands. The high-detail hero rig is the
source of truth for semantic regions. Each region receives its own repeating target
material (fur, muzzle, eyes, navy uniform, duty belt, gloves, boots), and a box-projected
UV generated from the hero's own local coordinates. BaseColor and tangent-space normal
textures are synthesized deterministically and packed into the GLB.

No decimation, remesh, subdivision, or skin changes are performed. Rendered visual
inspection remains mandatory and production stays closed.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import bpy
import numpy as np
from mathutils import Vector

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_transfer_pbr_candidate as base

UV_NAME = "HeroBoxUV"
MATERIAL_ORDER = ("fur", "muzzle", "eye", "uniform", "belt", "glove", "boot")
MATERIAL_INDEX = {name: i for i, name in enumerate(MATERIAL_ORDER)}
TEXTURE_SIZE = 768


def dominant_group_names(target: bpy.types.Object) -> list[str]:
    names = [g.name for g in target.vertex_groups]
    result = ["torso"] * len(target.data.vertices)
    for v in target.data.vertices:
        best_group = -1
        best_weight = -1.0
        for link in v.groups:
            w = float(link.weight)
            if w > best_weight:
                best_weight = w
                best_group = int(link.group)
        if 0 <= best_group < len(names):
            result[v.index] = names[best_group]
    return result


def semantic_class(group_name: str, normalized_z: float) -> str:
    n = (group_name or "").lower()
    if n.startswith("eye."):
        return "eye"
    if n in {"jaw", "muzzle_ctrl"}:
        return "muzzle"
    if n.startswith("ear.") or n in {"head", "neck"} or n.startswith("tail"):
        return "fur"
    if "hand." in n:
        return "glove"
    if any(k in n for k in ("foot.", "toe.")):
        return "boot"
    if 0.39 <= normalized_z <= 0.50 and n in {"pelvis", "root", "spine_01", "spine_02", "chest"}:
        return "belt"
    return "uniform"


def dominant_axis(normal: Vector) -> int:
    values = (abs(normal.x), abs(normal.y), abs(normal.z))
    return max(range(3), key=lambda i: values[i])


def repeat_scale(klass: str) -> float:
    return {
        "fur": 10.0,
        "muzzle": 8.0,
        "eye": 2.0,
        "uniform": 13.0,
        "belt": 8.0,
        "glove": 9.0,
        "boot": 7.0,
    }[klass]


def ensure_target_materials(target: bpy.types.Object) -> int:
    """Create material slots before assigning polygon indices.

    Blender clamps polygon.material_index to zero when the mesh has no material slots.
    The previous pipeline assigned semantic indices first and created slots afterwards,
    causing all 440k polygons to remain on material 0 and the glTF exporter to discard
    the six unused materials. This function is intentionally called before UV/semantic
    assignment and is idempotent when base.main later invokes copy_materials().
    """
    expected_names = [f"POLICE_DOG_{kind.upper()}_PBR" for kind in MATERIAL_ORDER]
    current = [mat.name if mat else "" for mat in target.data.materials]
    if current == expected_names:
        return len(current)

    target.data.materials.clear()
    for kind in MATERIAL_ORDER:
        target.data.materials.append(build_material(kind))

    current = [mat.name if mat else "" for mat in target.data.materials]
    if current != expected_names:
        raise RuntimeError(f"Failed to author exact target PBR material slots: {current}")
    return len(current)


def transfer_target_uvs(target: bpy.types.Object, _donor: bpy.types.Object) -> dict[str, object]:
    mesh = target.data

    # Critical ordering invariant: material slots must exist before setting material_index.
    ensure_target_materials(target)

    mn, mx = base.local_bounds(target)
    extent = mx - mn
    extent = Vector(tuple(max(1e-6, float(v)) for v in extent))
    groups = dominant_group_names(target)

    vertex_class: list[str] = []
    for v in mesh.vertices:
        p = base.normalize_point(v.co, mn, mx)
        vertex_class.append(semantic_class(groups[v.index], float(p.z)))

    uv = mesh.uv_layers.get(UV_NAME)
    if uv is None:
        uv = mesh.uv_layers.new(name=UV_NAME)
    mesh.uv_layers.active = uv
    try:
        uv.active_render = True
    except Exception:
        pass

    polygon_counts: Counter[str] = Counter()
    direction_counts: Counter[str] = Counter()
    slot_counts: Counter[int] = Counter()
    loops_written = 0

    for poly in mesh.polygons:
        votes = Counter(vertex_class[int(vi)] for vi in poly.vertices)
        klass = votes.most_common(1)[0][0]
        wanted_index = MATERIAL_INDEX[klass]
        poly.material_index = wanted_index
        if int(poly.material_index) != wanted_index:
            raise RuntimeError(
                f"Blender rejected material index {wanted_index} for {klass}; "
                f"mesh has {len(mesh.materials)} slots"
            )
        polygon_counts[klass] += 1
        slot_counts[int(poly.material_index)] += 1
        axis = dominant_axis(poly.normal)
        direction_counts[f"{klass}:{axis}"] += 1
        scale = repeat_scale(klass)

        for li in poly.loop_indices:
            vi = int(mesh.loops[li].vertex_index)
            co = mesh.vertices[vi].co
            nx = (float(co.x) - float(mn.x)) / float(extent.x)
            ny = (float(co.y) - float(mn.y)) / float(extent.y)
            nz = (float(co.z) - float(mn.z)) / float(extent.z)
            if axis == 0:
                u, v = ny, nz
            elif axis == 1:
                u, v = nx, nz
            else:
                u, v = nx, ny
            uv.data[li].uv = (u * scale, v * scale)
            loops_written += 1

    missing = [name for name in MATERIAL_ORDER if polygon_counts[name] <= 0]
    if missing:
        raise RuntimeError(f"Target semantic PBR classes have no polygons: {missing}; {dict(polygon_counts)}")
    if loops_written != len(mesh.loops):
        raise RuntimeError(f"Target UV loop mismatch: {loops_written} != {len(mesh.loops)}")

    expected_slots = set(range(len(MATERIAL_ORDER)))
    used_slots = {index for index, count in slot_counts.items() if count > 0}
    if used_slots != expected_slots:
        raise RuntimeError(
            f"Semantic material slots not all used: used={sorted(used_slots)} expected={sorted(expected_slots)} "
            f"counts={dict(slot_counts)}"
        )

    qc = {
        "algorithm": "target-rig semantic materials + target box UV",
        "uvName": UV_NAME,
        "targetVertices": len(mesh.vertices),
        "targetPolygons": len(mesh.polygons),
        "targetLoops": len(mesh.loops),
        "loopsWritten": loops_written,
        "semanticPolygons": dict(polygon_counts),
        "materialSlotPolygons": {str(i): int(slot_counts[i]) for i in range(len(MATERIAL_ORDER))},
        "materialSlots": len(mesh.materials),
        "projectionBuckets": dict(direction_counts),
        "materialOrder": list(MATERIAL_ORDER),
        "donorUvUsedAtRuntime": False,
        "geometryChanged": False,
    }
    print("TARGET_PBR_UV_QC=" + json.dumps(qc, indent=2), flush=True)
    return qc


def periodic_field(x: np.ndarray, y: np.ndarray, terms: tuple[tuple[float, float, float, float], ...]) -> np.ndarray:
    out = np.zeros_like(x, dtype=np.float32)
    total = 0.0
    for fx, fy, amplitude, phase in terms:
        out += amplitude * np.sin(2.0 * np.pi * (fx * x + fy * y) + phase)
        total += abs(amplitude)
    if total <= 1e-8:
        return out
    return out / total


def texture_fields(kind: str, size: int) -> tuple[np.ndarray, np.ndarray, float]:
    coords = np.arange(size, dtype=np.float32) / float(size)
    x, y = np.meshgrid(coords, coords)
    broad = periodic_field(x, y, ((2, 1, .55, .4), (5, -3, .28, 1.7), (9, 7, .17, .9)))
    fine = periodic_field(x, y, ((31, 2, .35, .1), (47, -4, .32, 1.2), (67, 7, .20, 2.1), (83, -11, .13, .7)))

    if kind == "fur":
        fibers = periodic_field(x, y, ((72, 4, .42, .2), (96, -5, .30, 1.1), (128, 7, .18, 2.0), (41, 1, .10, .6)))
        height = 0.57 * fibers + 0.28 * fine + 0.15 * broad
        # Warm German-shepherd brown that remains readable under cinematic lighting.
        base_color = np.array([0.255, 0.105, 0.030], dtype=np.float32)
        rgb = base_color[None, None, :] * (1.0 + (0.30 * broad + 0.22 * fibers)[..., None])
        guard = np.clip((fibers + fine - .65) * 1.6, 0.0, 1.0)[..., None]
        rgb *= 1.0 - 0.42 * guard
        roughness = 0.78
    elif kind == "muzzle":
        pores = periodic_field(x, y, ((39, 23, .38, .2), (61, -31, .34, 1.0), (91, 57, .28, 2.0)))
        height = 0.45 * pores + 0.30 * fine + 0.25 * broad
        base_color = np.array([0.055, 0.027, 0.014], dtype=np.float32)
        rgb = base_color[None, None, :] * (1.0 + (0.20 * broad + 0.12 * pores)[..., None])
        roughness = 0.64
    elif kind == "eye":
        iris = 0.5 + 0.5 * periodic_field(x, y, ((8, 0, .5, 0), (0, 8, .5, 1.1)))
        height = 0.05 * fine
        base_color = np.array([0.012, 0.008, 0.004], dtype=np.float32)
        amber = np.array([0.110, 0.045, 0.006], dtype=np.float32)
        rgb = base_color[None, None, :] + amber[None, None, :] * (0.34 * iris[..., None])
        roughness = 0.16
    elif kind == "uniform":
        weave = np.sin(2 * np.pi * 96 * x) * np.sin(2 * np.pi * 96 * y)
        diagonal = periodic_field(x, y, ((44, 43, .5, .3), (45, -46, .5, 1.3)))
        height = 0.52 * weave + 0.28 * diagonal + 0.20 * fine
        base_color = np.array([0.025, 0.064, 0.145], dtype=np.float32)
        rgb = base_color[None, None, :] * (1.0 + (0.14 * broad + 0.08 * weave)[..., None])
        roughness = 0.82
    elif kind == "belt":
        grain = periodic_field(x, y, ((19, 13, .38, .4), (37, -29, .34, 1.4), (73, 59, .28, 2.4)))
        height = 0.45 * grain + 0.30 * fine + 0.25 * broad
        base_color = np.array([0.030, 0.036, 0.044], dtype=np.float32)
        rgb = base_color[None, None, :] * (1.0 + (0.20 * broad + 0.12 * grain)[..., None])
        roughness = 0.56
    elif kind == "glove":
        grain = periodic_field(x, y, ((31, 27, .40, .2), (53, -41, .35, 1.5), (89, 71, .25, 2.2)))
        height = 0.48 * grain + 0.32 * fine + 0.20 * broad
        base_color = np.array([0.025, 0.031, 0.038], dtype=np.float32)
        rgb = base_color[None, None, :] * (1.0 + (0.18 * broad + 0.10 * grain)[..., None])
        roughness = 0.62
    else:
        grain = periodic_field(x, y, ((17, 11, .40, .1), (29, -23, .34, 1.4), (59, 47, .26, 2.1)))
        height = 0.45 * grain + 0.32 * fine + 0.23 * broad
        base_color = np.array([0.020, 0.025, 0.032], dtype=np.float32)
        rgb = base_color[None, None, :] * (1.0 + (0.18 * broad + 0.11 * grain)[..., None])
        roughness = 0.50

    rgb = np.clip(rgb, 0.002, 0.70).astype(np.float32)
    return rgb, height.astype(np.float32), roughness


def normal_from_height(height: np.ndarray, strength: float) -> np.ndarray:
    dy, dx = np.gradient(height)
    nx = -dx * strength
    ny = -dy * strength
    nz = np.ones_like(height, dtype=np.float32)
    length = np.sqrt(nx * nx + ny * ny + nz * nz)
    normal = np.stack((nx / length, ny / length, nz / length), axis=-1)
    return (normal * 0.5 + 0.5).astype(np.float32)


def make_image(name: str, rgb: np.ndarray, *, non_color: bool) -> bpy.types.Image:
    h, w, _ = rgb.shape
    image = bpy.data.images.new(name, width=w, height=h, alpha=True, float_buffer=False)
    rgba = np.ones((h, w, 4), dtype=np.float32)
    rgba[..., :3] = rgb
    image.pixels.foreach_set(rgba.reshape(-1))
    image.update()
    try:
        image.colorspace_settings.name = "Non-Color" if non_color else "sRGB"
    except Exception:
        pass
    image.pack()
    return image


def build_material(kind: str) -> bpy.types.Material:
    rgb, height, roughness = texture_fields(kind, TEXTURE_SIZE)
    strength = {
        "fur": 5.2,
        "muzzle": 2.4,
        "eye": 0.15,
        "uniform": 3.6,
        "belt": 2.2,
        "glove": 2.6,
        "boot": 2.0,
    }[kind]
    normal = normal_from_height(height, strength)
    base_img = make_image(f"Hero_{kind}_BaseColor", rgb, non_color=False)
    normal_img = make_image(f"Hero_{kind}_Normal", normal, non_color=True)

    mat = bpy.data.materials.new(f"POLICE_DOG_{kind.upper()}_PBR")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    nodes.clear()
    out = nodes.new("ShaderNodeOutputMaterial")
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    base_tex = nodes.new("ShaderNodeTexImage")
    base_tex.image = base_img
    base_tex.extension = "REPEAT"
    normal_tex = nodes.new("ShaderNodeTexImage")
    normal_tex.image = normal_img
    normal_tex.extension = "REPEAT"
    normal_tex.image.colorspace_settings.name = "Non-Color"
    nmap = nodes.new("ShaderNodeNormalMap")
    nmap.inputs["Strength"].default_value = 0.46 if kind == "fur" else (0.18 if kind == "eye" else 0.30)
    links.new(base_tex.outputs["Color"], bsdf.inputs["Base Color"])
    links.new(normal_tex.outputs["Color"], nmap.inputs["Color"])
    links.new(nmap.outputs["Normal"], bsdf.inputs["Normal"])
    links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])

    if bsdf.inputs.get("Metallic") is not None:
        bsdf.inputs["Metallic"].default_value = 0.0
    if bsdf.inputs.get("Roughness") is not None:
        bsdf.inputs["Roughness"].default_value = roughness
    if bsdf.inputs.get("Specular IOR Level") is not None:
        bsdf.inputs["Specular IOR Level"].default_value = 0.42 if kind == "eye" else 0.24
    if kind == "eye" and bsdf.inputs.get("Coat Weight") is not None:
        bsdf.inputs["Coat Weight"].default_value = 0.35
    return mat


def copy_target_materials(target: bpy.types.Object, _donor: bpy.types.Object) -> int:
    # Idempotent: transfer_target_uvs already creates the slots before assigning indices.
    return ensure_target_materials(target)


base.transfer_uvs = transfer_target_uvs
base.copy_materials = copy_target_materials

if __name__ == "__main__":
    raise SystemExit(base.main())
