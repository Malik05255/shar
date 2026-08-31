#!/usr/bin/env python3
"""Transfer a free Stable Fast 3D donor appearance onto the exact animated hero.

This wrapper keeps the topology/rig/animation preservation logic in the shared transfer
module, but replaces Blender's fragile temporary GLB image references with BaseColor and
Normal files extracted directly from the donor GLB. The final shader graph uses standard
glTF-compatible nodes so both textures must be embedded in the exported GLB.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import bpy
from mathutils import Vector

import blender_transfer_pbr_candidate as base


_original_copy_materials = base.copy_materials


def required_texture_path(env_name: str) -> Path:
    raw = os.environ.get(env_name, "").strip()
    if not raw:
        raise RuntimeError(f"Required extracted texture env is missing: {env_name}")
    path = Path(raw).resolve()
    if not path.is_file() or path.stat().st_size < 128:
        raise RuntimeError(f"Required extracted texture is missing/empty: {path}")
    return path


def clear_input_links(tree: bpy.types.NodeTree, socket) -> None:
    for link in list(socket.links):
        tree.links.remove(link)


def rebind_standard_gltf_pbr(mat: bpy.types.Material, base_path: Path, normal_path: Path) -> None:
    if not mat.use_nodes:
        mat.use_nodes = True
    tree = mat.node_tree
    bsdf = next((n for n in tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
    if bsdf is None:
        raise RuntimeError(f"Material {mat.name} has no Principled BSDF")

    # Remove donor-imported image/normal nodes. Those may reference transient Blender
    # image buffers that rendered but were omitted from the final GLB.
    for node in list(tree.nodes):
        if node.type in {'TEX_IMAGE', 'NORMAL_MAP'}:
            tree.nodes.remove(node)

    base_img = bpy.data.images.load(str(base_path), check_existing=False)
    normal_img = bpy.data.images.load(str(normal_path), check_existing=False)
    try:
        base_img.colorspace_settings.name = 'sRGB'
    except Exception:
        pass
    try:
        normal_img.colorspace_settings.name = 'Non-Color'
    except Exception:
        pass
    base_img.name = 'SF3D_BaseColor_Embedded'
    normal_img.name = 'SF3D_Normal_Embedded'

    # Force decoding now. A zero-sized/unloaded image is rejected before render/export.
    base_img.reload(); normal_img.reload()
    if not base_img.has_data or min(base_img.size) <= 0:
        raise RuntimeError(f"BaseColor image failed to decode: {base_path}")
    if not normal_img.has_data or min(normal_img.size) <= 0:
        raise RuntimeError(f"Normal image failed to decode: {normal_path}")

    base_node = tree.nodes.new('ShaderNodeTexImage')
    base_node.name = 'SF3D_BASECOLOR_FILE'
    base_node.label = 'SF3D BaseColor — extracted from donor GLB'
    base_node.image = base_img
    base_node.interpolation = 'Linear'

    normal_node = tree.nodes.new('ShaderNodeTexImage')
    normal_node.name = 'SF3D_NORMAL_FILE'
    normal_node.label = 'SF3D Normal — extracted from donor GLB'
    normal_node.image = normal_img
    normal_node.interpolation = 'Linear'

    normal_map = tree.nodes.new('ShaderNodeNormalMap')
    normal_map.name = 'SF3D_NORMAL_MAP'
    normal_map.space = 'TANGENT'
    normal_map.inputs['Strength'].default_value = 1.0

    clear_input_links(tree, bsdf.inputs['Base Color'])
    clear_input_links(tree, bsdf.inputs['Normal'])
    tree.links.new(base_node.outputs['Color'], bsdf.inputs['Base Color'])
    tree.links.new(normal_node.outputs['Color'], normal_map.inputs['Color'])
    tree.links.new(normal_map.outputs['Normal'], bsdf.inputs['Normal'])

    rough = bsdf.inputs.get('Roughness')
    if rough is not None and float(rough.default_value) < 0.62:
        rough.default_value = 0.62
    metallic = bsdf.inputs.get('Metallic')
    if metallic is not None:
        metallic.default_value = 0.0
    spec = bsdf.inputs.get('Specular IOR Level') or bsdf.inputs.get('Specular')
    if spec is not None and float(spec.default_value) > 0.38:
        spec.default_value = 0.38

    print(
        f"SF3D_PBR_REBOUND material={mat.name!r} "
        f"base={base_path.name}:{base_img.size[0]}x{base_img.size[1]} "
        f"normal={normal_path.name}:{normal_img.size[0]}x{normal_img.size[1]}",
        flush=True,
    )


def copy_materials_with_physical_defaults(target: bpy.types.Object, donor: bpy.types.Object) -> int:
    count = _original_copy_materials(target, donor)
    base_path = required_texture_path('SF3D_BASECOLOR_PATH')
    normal_path = required_texture_path('SF3D_NORMAL_PATH')
    for mat in target.data.materials:
        if mat is not None:
            rebind_standard_gltf_pbr(mat, base_path, normal_path)
    return count


def ensure_file_backed_images_ready(materials: list[bpy.types.Material]) -> tuple[int, int]:
    images = set()
    texture_nodes = 0
    for mat in materials:
        if not mat or not mat.use_nodes:
            continue
        for node in mat.node_tree.nodes:
            if node.type != 'TEX_IMAGE' or getattr(node, 'image', None) is None:
                continue
            image = node.image
            texture_nodes += 1
            images.add(image)
            if not image.has_data or min(image.size) <= 0:
                raise RuntimeError(f"Texture node {node.name} has no decoded image data")
            resolved = Path(bpy.path.abspath(image.filepath)).resolve() if image.filepath else None
            if resolved is None or not resolved.is_file() or resolved.stat().st_size < 128:
                raise RuntimeError(f"Texture node {node.name} is not backed by a durable file: {resolved}")
    if texture_nodes < 2 or len(images) < 2:
        raise RuntimeError(f"Expected BaseColor+Normal file textures, got nodes={texture_nodes} images={len(images)}")
    print(f"SF3D_TEXTURE_FILES_READY nodes={texture_nodes} images={len(images)}", flush=True)
    return texture_nodes, len(images)


def setup_preview_compat(target_mesh: bpy.types.Object, preview_dir):
    preview_dir.mkdir(parents=True, exist_ok=True)
    for obj in bpy.data.objects:
        obj.hide_render = obj != target_mesh and obj.type not in {'LIGHT', 'CAMERA'}

    scene = bpy.context.scene
    if scene.world is None:
        scene.world = bpy.data.worlds.new('QC_WORLD')
    scene.world.color = (0.025, 0.025, 0.025)
    try:
        scene.render.engine = 'BLENDER_EEVEE_NEXT'
    except (TypeError, ValueError):
        scene.render.engine = 'BLENDER_EEVEE'
    except Exception:
        scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.film_transparent = False

    mn, mx = base.local_bounds(target_mesh)
    center = (mn + mx) * 0.5
    ext = mx - mn
    radius = max(ext.x, ext.y, ext.z) * 1.35

    cam_data = bpy.data.cameras.new('QC_CAMERA')
    cam = bpy.data.objects.new('QC_CAMERA', cam_data)
    bpy.context.collection.objects.link(cam)
    scene.camera = cam
    cam.data.lens = 62

    def point(obj: bpy.types.Object, target: Vector) -> None:
        obj.rotation_euler = (target - obj.location).to_track_quat('-Z', 'Y').to_euler()

    lights = [
        ('QC_KEY', 1100, 0.85, Vector((1.35, -1.15, 1.35))),
        ('QC_FILL', 500, 0.75, Vector((-1.0, -0.7, 0.65))),
        ('QC_RIM', 700, 0.60, Vector((-0.35, 1.15, 1.2))),
    ]
    for name, energy, size_scale, direction in lights:
        ld = bpy.data.lights.new(name, type='AREA')
        ld.energy = energy
        ld.size = radius * size_scale
        lo = bpy.data.objects.new(name, ld)
        bpy.context.collection.objects.link(lo)
        lo.location = center + direction * radius
        point(lo, center)

    views = [
        ('front', Vector((0.0, -radius * 2.4, radius * 0.12))),
        ('three_quarter', Vector((radius * 1.4, -radius * 2.0, radius * 0.18))),
        ('side', Vector((radius * 2.3, 0.0, radius * 0.15))),
        ('back', Vector((0.0, radius * 2.4, radius * 0.12))),
    ]
    outputs = []
    for name, offset in views:
        cam.location = center + offset
        point(cam, center)
        path = preview_dir / f'police_dog.sf3d_pbr.{name}.png'
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        outputs.append(path.name)
    return outputs


base.copy_materials = copy_materials_with_physical_defaults
base.ensure_images_packed = ensure_file_backed_images_ready
base.setup_preview = setup_preview_compat

if __name__ == '__main__':
    raise SystemExit(base.main())
