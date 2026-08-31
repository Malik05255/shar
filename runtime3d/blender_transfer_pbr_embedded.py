#!/usr/bin/env python3
"""Deterministic PBR transfer adapter that hydrates embedded glTF images before export.

The previous donor material copy kept Blender image datablocks whose backing payload was
not reliably available in headless rendering/export, producing magenta previews and a
GLB with zero images/textures. This adapter extracts the donor's embedded glTF image
bytes, rebuilds a standard Principled BSDF material from those bytes, packs the loaded
images, then delegates all UV/topology/animation preservation to the proven transfer
pipeline.
"""
from __future__ import annotations

import base64
import json
import struct
import sys
from pathlib import Path
from typing import Any

import bpy

# Blender's --python execution does not consistently add the script directory to
# sys.path. Resolve the sibling transfer module explicitly so headless CI behaves the
# same regardless of the runner's working directory.
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_transfer_pbr_candidate as base

ARGS = base.args_after_dash()
base.args_after_dash = lambda: ARGS


def _read_glb(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    if len(raw) < 20:
        raise RuntimeError(f"Donor GLB too small: {path}")
    magic, version, declared = struct.unpack_from("<III", raw, 0)
    if magic != 0x46546C67 or version != 2 or declared != len(raw):
        raise RuntimeError(f"Invalid GLB header: {path}")
    offset = 12
    document: dict[str, Any] | None = None
    binary = b""
    while offset < len(raw):
        length, chunk_type = struct.unpack_from("<II", raw, offset)
        offset += 8
        chunk = raw[offset:offset + length]
        offset += length
        if chunk_type == 0x4E4F534A:
            document = json.loads(chunk.decode("utf-8").rstrip("\x00 \t\r\n"))
        elif chunk_type == 0x004E4942:
            binary = chunk
    if document is None:
        raise RuntimeError("Donor GLB has no JSON chunk")
    return document, binary


def _image_bytes(doc: dict[str, Any], binary: bytes, donor_path: Path, image_index: int) -> tuple[bytes, str]:
    images = doc.get("images") or []
    if not (0 <= image_index < len(images)):
        raise RuntimeError(f"Invalid donor image index {image_index}")
    image = images[image_index]
    mime = str(image.get("mimeType") or "")
    if isinstance(image.get("bufferView"), int):
        view = (doc.get("bufferViews") or [])[image["bufferView"]]
        start = int(view.get("byteOffset", 0))
        length = int(view.get("byteLength", 0))
        payload = binary[start:start + length]
    else:
        uri = image.get("uri")
        if not isinstance(uri, str) or not uri:
            raise RuntimeError(f"Donor image {image_index} has no bufferView or URI")
        if uri.startswith("data:"):
            header, encoded = uri.split(",", 1)
            if not mime and ";" in header:
                mime = header[5:].split(";", 1)[0]
            payload = base64.b64decode(encoded) if ";base64" in header else encoded.encode("utf-8")
        else:
            image_path = (donor_path.parent / uri).resolve()
            if not image_path.is_file():
                raise RuntimeError(f"External donor image is missing: {image_path}")
            payload = image_path.read_bytes()
    if len(payload) < 128:
        raise RuntimeError(f"Donor image {image_index} payload is unexpectedly small")
    ext = {
        "image/jpeg": ".jpg",
        "image/jpg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
    }.get(mime.lower())
    if ext is None:
        if payload.startswith(b"\xff\xd8\xff"):
            ext = ".jpg"
        elif payload.startswith(b"\x89PNG\r\n\x1a\n"):
            ext = ".png"
        else:
            raise RuntimeError(f"Unsupported embedded image MIME/signature: {mime!r}")
    return payload, ext


def _texture_source(doc: dict[str, Any], texture_index: int) -> int:
    textures = doc.get("textures") or []
    if not (0 <= texture_index < len(textures)):
        raise RuntimeError(f"Invalid donor texture index {texture_index}")
    texture = textures[texture_index]
    source = texture.get("source")
    if not isinstance(source, int):
        ext = (texture.get("extensions") or {}).get("EXT_texture_webp") or {}
        source = ext.get("source")
    if not isinstance(source, int):
        raise RuntimeError(f"Texture {texture_index} has no supported image source")
    return source


def _select_pbr_material(doc: dict[str, Any]) -> dict[str, Any]:
    materials = doc.get("materials") or []
    for material in materials:
        pbr = material.get("pbrMetallicRoughness") or {}
        if isinstance((pbr.get("baseColorTexture") or {}).get("index"), int):
            return material
    raise RuntimeError("Donor GLB has no material with a BaseColor texture")


def _write_embedded_image(doc: dict[str, Any], binary: bytes, donor_path: Path, image_index: int, label: str) -> Path:
    payload, ext = _image_bytes(doc, binary, donor_path, image_index)
    out_dir = ARGS.preview_dir / "embedded-pbr-source"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{label}{ext}"
    out.write_bytes(payload)
    if out.stat().st_size != len(payload):
        raise RuntimeError(f"Failed to materialize embedded donor image {label}")
    return out


def _load_packed_image(path: Path, name: str, non_color: bool) -> bpy.types.Image:
    image = bpy.data.images.load(str(path), check_existing=False)
    image.name = name
    if non_color:
        try:
            image.colorspace_settings.name = "Non-Color"
        except Exception:
            pass
    else:
        try:
            image.colorspace_settings.name = "sRGB"
        except Exception:
            pass
    image.pack()
    if image.packed_file is None:
        raise RuntimeError(f"Blender did not pack hydrated image {name}")
    return image


def _set_socket_default(node: bpy.types.Node, name: str, value: Any) -> None:
    socket = node.inputs.get(name)
    if socket is not None:
        socket.default_value = value


def deterministic_copy_materials(target: bpy.types.Object, donor: bpy.types.Object) -> int:
    del donor  # Geometry/material datablocks are intentionally not trusted for image hydration.
    doc, binary = _read_glb(ARGS.donor)
    source_material = _select_pbr_material(doc)
    pbr = source_material.get("pbrMetallicRoughness") or {}
    base_texture_index = (pbr.get("baseColorTexture") or {}).get("index")
    normal_texture_index = (source_material.get("normalTexture") or {}).get("index")
    if not isinstance(base_texture_index, int) or not isinstance(normal_texture_index, int):
        raise RuntimeError("Selected donor must contain both BaseColor and Normal textures")

    base_image_index = _texture_source(doc, base_texture_index)
    normal_image_index = _texture_source(doc, normal_texture_index)
    base_path = _write_embedded_image(doc, binary, ARGS.donor, base_image_index, "base_color")
    normal_path = _write_embedded_image(doc, binary, ARGS.donor, normal_image_index, "normal")
    base_image = _load_packed_image(base_path, "Runtime3D_BaseColor", non_color=False)
    normal_image = _load_packed_image(normal_path, "Runtime3D_Normal", non_color=True)

    material = bpy.data.materials.new(name="Runtime3D_Embedded_PBR")
    material.use_nodes = True
    material.diffuse_color = tuple((pbr.get("baseColorFactor") or [1.0, 1.0, 1.0, 1.0]))
    material.use_backface_culling = not bool(source_material.get("doubleSided", False))
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()

    output = nodes.new("ShaderNodeOutputMaterial")
    output.name = "Material Output"
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.name = "Principled BSDF"
    links.new(bsdf.outputs["BSDF"], output.inputs["Surface"])

    base_factor = pbr.get("baseColorFactor") or [1.0, 1.0, 1.0, 1.0]
    _set_socket_default(bsdf, "Base Color", tuple(float(v) for v in base_factor[:4]))
    _set_socket_default(bsdf, "Metallic", float(pbr.get("metallicFactor", 1.0)))
    _set_socket_default(bsdf, "Roughness", float(pbr.get("roughnessFactor", 1.0)))

    base_tex = nodes.new("ShaderNodeTexImage")
    base_tex.name = "BaseColorTexture"
    base_tex.image = base_image
    base_tex.interpolation = "Linear"
    links.new(base_tex.outputs["Color"], bsdf.inputs["Base Color"])

    normal_tex = nodes.new("ShaderNodeTexImage")
    normal_tex.name = "NormalTexture"
    normal_tex.image = normal_image
    normal_tex.interpolation = "Linear"
    normal_map = nodes.new("ShaderNodeNormalMap")
    normal_map.name = "Normal Map"
    normal_info = source_material.get("normalTexture") or {}
    _set_socket_default(normal_map, "Strength", float(normal_info.get("scale", 1.0)))
    links.new(normal_tex.outputs["Color"], normal_map.inputs["Color"])
    links.new(normal_map.outputs["Normal"], bsdf.inputs["Normal"])

    target.data.materials.clear()
    target.data.materials.append(material)
    for poly in target.data.polygons:
        poly.material_index = 0

    print(json.dumps({
        "PBR_IMAGE_HYDRATION": "PASS",
        "baseImageBytes": base_path.stat().st_size,
        "normalImageBytes": normal_path.stat().st_size,
        "baseImagePacked": base_image.packed_file is not None,
        "normalImagePacked": normal_image.packed_file is not None,
        "metallicFactor": float(pbr.get("metallicFactor", 1.0)),
        "roughnessFactor": float(pbr.get("roughnessFactor", 1.0)),
    }, indent=2), flush=True)
    return 1


base.copy_materials = deterministic_copy_materials

if __name__ == "__main__":
    raise SystemExit(base.main())
