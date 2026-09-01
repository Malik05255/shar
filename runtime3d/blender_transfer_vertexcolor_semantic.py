#!/usr/bin/env python3
"""Bake SF3D donor appearance into stable semantic corner colors on Motion V2.

Why this exists:
The donor and hero have different topology/UV islands. Even semantically constrained UV
transfer can interpolate across unrelated donor islands and create white/blue patches.
This adapter intentionally does *not* ship the transferred donor UV as the appearance
source. It reuses the proven semantic correspondence only to sample donor detail once,
then freezes that detail into a target-owned CORNER color attribute.

The sampled donor hue is not trusted blindly. The target skin rig provides a stronger
semantic signal for the final palette:
- head/ears/tail -> warm K9 fur tones;
- jaw/muzzle/eyes -> dark facial tones;
- torso/upper limbs/upper legs -> matte navy police uniform;
- hands -> charcoal gloves;
- feet/toes -> black duty boots;
- waist band -> dark duty-belt tone.
Donor luminance contributes bounded micro-variation only, preventing a shoe/face texel
from turning a trouser or sleeve white while still preserving surface richness.

No decimation, remesh, or geometry changes are performed. Production remains gated by
rendered visual review.
"""
from __future__ import annotations

import json
import sys
from array import array
from pathlib import Path

import bpy

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_transfer_pbr_seamsafe as seam

base = seam.base

COLOR_ATTR = "HeroSemanticColor"
ALGORITHM = "semantic-region donor luminance -> target corner vertex color"


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if v < lo else hi if v > hi else v


def dominant_group_names(target: bpy.types.Object) -> list[str]:
    names = [g.name for g in target.vertex_groups]
    out = ["torso"] * len(target.data.vertices)
    for v in target.data.vertices:
        best_group = -1
        best_weight = -1.0
        for link in v.groups:
            w = float(link.weight)
            if w > best_weight:
                best_weight = w
                best_group = int(link.group)
        if 0 <= best_group < len(names):
            out[v.index] = names[best_group]
    return out


def semantic_class(group_name: str, normalized_z: float) -> str:
    n = (group_name or "").lower()
    if n.startswith("eye."):
        return "eye"
    if n in {"jaw", "muzzle_ctrl"}:
        return "muzzle"
    if n.startswith("ear.") or n in {"head", "neck"}:
        return "fur"
    if n.startswith("tail"):
        return "fur"
    if "hand." in n:
        return "glove"
    if any(k in n for k in ("foot.", "toe.")):
        return "boot"
    if 0.405 <= normalized_z <= 0.485 and n in {"pelvis", "root", "spine_01", "spine_02", "chest"}:
        return "belt"
    return "uniform"


def find_base_color_image(donor: bpy.types.Object) -> bpy.types.Image:
    candidates: list[bpy.types.Image] = []
    for mat in donor.data.materials:
        if mat is None or not mat.use_nodes:
            continue
        for node in mat.node_tree.nodes:
            if node.type != "TEX_IMAGE" or node.image is None:
                continue
            img = node.image
            candidates.append(img)
            try:
                cs = (img.colorspace_settings.name or "").lower()
            except Exception:
                cs = ""
            if "non-color" not in cs and "normal" not in (img.name or "").lower():
                return img
    if not candidates:
        raise RuntimeError("Donor has no image texture node to sample")
    return candidates[0]


def read_pixels(image: bpy.types.Image) -> tuple[int, int, array]:
    width, height = int(image.size[0]), int(image.size[1])
    if width <= 1 or height <= 1:
        raise RuntimeError(f"Invalid donor image size: {width}x{height}")
    total = width * height * 4
    pixels = array('f', [0.0]) * total
    image.pixels.foreach_get(pixels)
    return width, height, pixels


def sampled_rgb(pixels: array, width: int, height: int, u: float, v: float) -> tuple[float, float, float]:
    u = float(u) % 1.0
    v = float(v) % 1.0
    x = min(width - 1, max(0, int(u * (width - 1) + 0.5)))
    y = min(height - 1, max(0, int(v * (height - 1) + 0.5)))
    i = (y * width + x) * 4
    return float(pixels[i]), float(pixels[i + 1]), float(pixels[i + 2])


def scale_color(base_rgb: tuple[float, float, float], detail: float) -> tuple[float, float, float, float]:
    return tuple(clamp(c * detail) for c in base_rgb) + (1.0,)


def recolor(source: tuple[float, float, float], klass: str, group_name: str, normalized_z: float) -> tuple[float, float, float, float]:
    r, g, b = source
    lum = clamp(max(r, g, b))
    detail = clamp(0.82 + 0.34 * lum, 0.82, 1.14)

    if klass == "uniform":
        return scale_color((0.070, 0.145, 0.245), detail)
    if klass == "belt":
        return scale_color((0.025, 0.035, 0.045), clamp(0.88 + 0.20 * lum, 0.88, 1.06))
    if klass == "glove":
        return scale_color((0.035, 0.045, 0.055), clamp(0.86 + 0.20 * lum, 0.86, 1.05))
    if klass == "boot":
        return scale_color((0.020, 0.026, 0.033), clamp(0.86 + 0.18 * lum, 0.86, 1.04))
    if klass == "eye":
        return (0.012, 0.008, 0.006, 1.0)
    if klass == "muzzle":
        return scale_color((0.055, 0.032, 0.020), clamp(0.83 + 0.22 * lum, 0.83, 1.04))

    n = (group_name or "").lower()
    warm = r > b * 1.12 and r > g * 0.82
    dark_source = lum < 0.18
    dark_tip = n in {"tail_03", "ear.l", "ear.r"} and normalized_z > 0.90
    if dark_source or dark_tip:
        palette = (0.075, 0.038, 0.020)
        fur_detail = clamp(0.86 + 0.28 * lum, 0.86, 1.07)
    elif warm:
        palette = (0.430, 0.215, 0.075)
        fur_detail = clamp(0.86 + 0.30 * lum, 0.86, 1.10)
    else:
        palette = (0.335, 0.155, 0.055)
        fur_detail = clamp(0.90 + 0.20 * lum, 0.90, 1.08)
    return scale_color(palette, fur_detail)


def bake_semantic_colors(target: bpy.types.Object, donor: bpy.types.Object) -> dict[str, object]:
    correspondence = seam.transfer_uvs(target, donor)
    mesh = target.data
    uv = mesh.uv_layers.get("SemanticPBR_UV") or mesh.uv_layers.active
    if uv is None:
        raise RuntimeError("Semantic correspondence did not create a UV lookup")

    image = find_base_color_image(donor)
    width, height, pixels = read_pixels(image)
    group_names = dominant_group_names(target)
    tmn, tmx = base.local_bounds(target)

    existing = mesh.color_attributes.get(COLOR_ATTR)
    if existing is not None:
        mesh.color_attributes.remove(existing)
    colors = mesh.color_attributes.new(name=COLOR_ATTR, type='BYTE_COLOR', domain='CORNER')

    packed = array('f', [0.0]) * (len(mesh.loops) * 4)
    counts: dict[str, int] = {k: 0 for k in ("fur", "muzzle", "eye", "uniform", "belt", "glove", "boot")}
    source_blue_or_white = 0

    for li, loop in enumerate(mesh.loops):
        vi = int(loop.vertex_index)
        suv = uv.data[li].uv
        src = sampled_rgb(pixels, width, height, suv.x, suv.y)
        if src[2] > src[0] * 1.12 or max(src) > 0.82:
            source_blue_or_white += 1
        p = base.normalize_point(mesh.vertices[vi].co, tmn, tmx)
        group_name = group_names[vi]
        klass = semantic_class(group_name, float(p.z))
        counts[klass] = counts.get(klass, 0) + 1
        rgba = recolor(src, klass, group_name, float(p.z))
        j = li * 4
        packed[j] = rgba[0]
        packed[j + 1] = rgba[1]
        packed[j + 2] = rgba[2]
        packed[j + 3] = rgba[3]

    colors.data.foreach_set("color", packed)
    try:
        mesh.color_attributes.active_color = colors
    except Exception:
        pass
    try:
        mesh.color_attributes.active = colors
    except Exception:
        pass

    qc = {
        "algorithm": ALGORITHM,
        "targetVertices": len(mesh.vertices),
        "targetPolygons": len(mesh.polygons),
        "targetLoops": len(mesh.loops),
        "colorLoopsWritten": len(mesh.loops),
        "colorAttribute": COLOR_ATTR,
        "colorDomain": "CORNER",
        "donorImage": image.name,
        "donorImageSize": [width, height],
        "semanticClassLoops": counts,
        "sourceBlueOrWhiteLoopsNeutralized": int(source_blue_or_white),
        "correspondence": correspondence,
        "appearancePolicy": "donor luminance bounded; rig semantic palette authoritative",
    }
    print("SEMANTIC_VERTEX_COLOR_QC=" + json.dumps(qc, indent=2), flush=True)
    return qc


def vertex_color_material(target: bpy.types.Object, donor: bpy.types.Object) -> int:
    del donor
    mesh = target.data
    mesh.materials.clear()
    mat = bpy.data.materials.new("POLICE_DOG_SEMANTIC_VERTEX_PBR")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    nodes.clear()

    out = nodes.new("ShaderNodeOutputMaterial")
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    vcol = nodes.new("ShaderNodeVertexColor")
    vcol.layer_name = COLOR_ATTR
    links.new(vcol.outputs["Color"], bsdf.inputs["Base Color"])
    links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    if bsdf.inputs.get("Metallic") is not None:
        bsdf.inputs["Metallic"].default_value = 0.0
    if bsdf.inputs.get("Roughness") is not None:
        bsdf.inputs["Roughness"].default_value = 0.72
    if bsdf.inputs.get("Specular IOR Level") is not None:
        bsdf.inputs["Specular IOR Level"].default_value = 0.28
    mesh.materials.append(mat)
    for poly in mesh.polygons:
        poly.material_index = 0
    return 1


base.transfer_uvs = bake_semantic_colors
base.copy_materials = vertex_color_material

if __name__ == "__main__":
    raise SystemExit(base.main())
