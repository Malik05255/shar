#!/usr/bin/env python3
"""Transfer a free Stable Fast 3D donor appearance onto the exact animated hero.

This wrapper keeps the topology/rig/animation preservation logic in the shared transfer
module, but replaces Blender's fragile temporary GLB image references with BaseColor and
Normal files extracted directly from the donor GLB. The final shader graph uses standard
glTF-compatible nodes so both textures must be embedded in the exported GLB.

For the very dense animated hero, UV projection is executed with Blender's native Data
Transfer modifier against normalized temporary mesh copies. This preserves the original
nearest-face/interpolated intent while moving the expensive surface lookup from a
1.3M-iteration Python loop into Blender's compiled geometry code. The target topology,
coordinates, skin weights, armature, and actions are never replaced or decimated.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import bpy
import numpy as np
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


def _normalized_mesh_proxy(source: bpy.types.Object, name: str) -> tuple[bpy.types.Object, bpy.types.Mesh]:
    """Create a temporary geometry-identical mesh normalized to a unit bounding box."""
    mesh = source.data.copy()
    proxy = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(proxy)
    proxy.matrix_world.identity()

    count = len(mesh.vertices)
    if count == 0:
        raise RuntimeError(f"Cannot normalize empty mesh proxy: {name}")
    coords = np.empty(count * 3, dtype=np.float64)
    mesh.vertices.foreach_get('co', coords)
    xyz = coords.reshape((-1, 3))
    mn = xyz.min(axis=0)
    mx = xyz.max(axis=0)
    ext = np.maximum(mx - mn, 1e-8)
    xyz = (xyz - mn) / ext
    mesh.vertices.foreach_set('co', np.ascontiguousarray(xyz.reshape(-1), dtype=np.float64))
    mesh.update()
    return proxy, mesh


def _enum_identifiers(owner, property_name: str) -> list[str]:
    prop = owner.bl_rna.properties.get(property_name)
    if prop is None:
        return []
    try:
        return [item.identifier for item in prop.enum_items]
    except Exception:
        return []


def _configure_uv_layer_selection(
    mod: bpy.types.DataTransferModifier,
    source_uv_name: str,
    destination_uv_name: str,
) -> dict[str, object]:
    """Configure Blender-version-safe source-layer and destination matching enums."""
    src_ids = _enum_identifiers(mod, 'layers_uv_select_src')
    dst_ids = _enum_identifiers(mod, 'layers_uv_select_dst')

    if source_uv_name in src_ids:
        mod.layers_uv_select_src = source_uv_name
        src_choice = source_uv_name
    elif 'ALL' in src_ids:
        # Safe only because the donor contract currently requires exactly one active UV
        # layer for this transfer. Fail if that invariant changes.
        if len(mod.object.data.uv_layers) != 1:
            raise RuntimeError(
                f"Blender cannot select source UV {source_uv_name!r}; available={src_ids}; "
                f"donorUvLayers={[uv.name for uv in mod.object.data.uv_layers]}"
            )
        mod.layers_uv_select_src = 'ALL'
        src_choice = 'ALL'
    else:
        raise RuntimeError(
            f"No valid Blender source UV selector for {source_uv_name!r}; available={src_ids}"
        )

    # Blender 4.0 exposes destination mapping as NAME/INDEX rather than the source layer
    # names. Prefer NAME after ensuring a destination layer with the exact source name.
    if 'NAME' in dst_ids:
        mod.layers_uv_select_dst = 'NAME'
        dst_choice = 'NAME'
    elif destination_uv_name in dst_ids:
        mod.layers_uv_select_dst = destination_uv_name
        dst_choice = destination_uv_name
    elif 'INDEX' in dst_ids:
        mod.layers_uv_select_dst = 'INDEX'
        dst_choice = 'INDEX'
    else:
        raise RuntimeError(
            f"No valid Blender destination UV selector for {destination_uv_name!r}; available={dst_ids}"
        )

    info = {
        'sourceUv': source_uv_name,
        'destinationUv': destination_uv_name,
        'sourceEnumAvailable': src_ids,
        'destinationEnumAvailable': dst_ids,
        'sourceSelection': src_choice,
        'destinationSelection': dst_choice,
    }
    print('SF3D_UV_LAYER_ENUMS=' + json.dumps(info, indent=2), flush=True)
    return info


def transfer_uvs_native(target: bpy.types.Object, donor: bpy.types.Object) -> dict[str, object]:
    """Transfer donor UVs with Blender's compiled nearest-face interpolation.

    Temporary normalized target/donor copies reproduce the unit-bounds spatial mapping
    used by the former Python BVH implementation. Data Transfer operates on loop UVs,
    retaining donor UV seams instead of averaging them down to one UV per vertex.
    Only the resulting UV loop data is copied back to the real animated target.
    """
    if not donor.data.materials:
        raise RuntimeError("Donor mesh has no material slots")
    if donor.data.uv_layers.active is None:
        raise RuntimeError("Textured donor has no active UV map")

    target_proxy = donor_proxy = None
    target_proxy_mesh = donor_proxy_mesh = None
    try:
        target_proxy, target_proxy_mesh = _normalized_mesh_proxy(target, 'SF3D_UV_TARGET_PROXY')
        donor_proxy, donor_proxy_mesh = _normalized_mesh_proxy(donor, 'SF3D_UV_DONOR_PROXY')

        source_uv = donor_proxy_mesh.uv_layers.active
        if source_uv is None:
            raise RuntimeError("Normalized donor proxy lost its active UV map")
        source_uv_name = source_uv.name

        # Match by the exact source UV name on the disposable target proxy. This avoids
        # Blender 4.0's version-specific ACTIVE enum and makes NAME matching deterministic.
        destination_uv = target_proxy_mesh.uv_layers.get(source_uv_name)
        if destination_uv is None:
            destination_uv = target_proxy_mesh.uv_layers.new(name=source_uv_name)
        target_proxy_mesh.uv_layers.active_index = target_proxy_mesh.uv_layers.find(destination_uv.name)

        mod = target_proxy.modifiers.new(name='SF3D_NATIVE_UV_TRANSFER', type='DATA_TRANSFER')
        mod.object = donor_proxy
        mod.use_loop_data = True
        mod.data_types_loops = {'UV'}
        mod.loop_mapping = 'POLYINTERP_NEAREST'
        selector_qc = _configure_uv_layer_selection(mod, source_uv_name, destination_uv.name)
        mod.mix_mode = 'REPLACE'
        mod.mix_factor = 1.0

        bpy.ops.object.select_all(action='DESELECT')
        target_proxy.select_set(True)
        bpy.context.view_layer.objects.active = target_proxy
        result = bpy.ops.object.modifier_apply(modifier=mod.name)
        if 'FINISHED' not in result:
            raise RuntimeError(f"Native UV Data Transfer did not finish: {result}")

        transferred_uv = target_proxy_mesh.uv_layers.get(destination_uv.name)
        if transferred_uv is None:
            transferred_uv = target_proxy_mesh.uv_layers.active
        if transferred_uv is None:
            raise RuntimeError("Native UV Data Transfer produced no destination UV layer")
        if len(transferred_uv.data) != len(target.data.loops):
            raise RuntimeError(
                f"UV loop cardinality changed on proxy: {len(transferred_uv.data)} != {len(target.data.loops)}"
            )

        uv_buffer = np.empty(len(transferred_uv.data) * 2, dtype=np.float32)
        transferred_uv.data.foreach_get('uv', uv_buffer)
        if not bool(np.isfinite(uv_buffer).all()):
            raise RuntimeError("Native UV Data Transfer produced non-finite UV coordinates")
        uv_pairs = uv_buffer.reshape((-1, 2))
        uv_min = uv_pairs.min(axis=0)
        uv_max = uv_pairs.max(axis=0)
        uv_span = uv_max - uv_min
        if float(max(uv_span[0], uv_span[1])) < 1e-6:
            raise RuntimeError(f"Native UV Data Transfer produced a degenerate UV map: span={uv_span.tolist()}")

        real_uv = target.data.uv_layers.get(destination_uv.name)
        if real_uv is None:
            real_uv = target.data.uv_layers.new(name=destination_uv.name)
        target.data.uv_layers.active_index = target.data.uv_layers.find(real_uv.name)
        if len(real_uv.data) != len(transferred_uv.data):
            raise RuntimeError("Real target UV loop count does not match normalized transfer proxy")
        real_uv.data.foreach_set('uv', uv_buffer)
        target.data.update()

        qc = {
            'targetVertices': len(target.data.vertices),
            'targetLoops': len(target.data.loops),
            'donorVertices': len(donor.data.vertices),
            'donorPolygons': len(donor.data.polygons),
            'method': 'BLENDER_DATA_TRANSFER_POLYINTERP_NEAREST_NORMALIZED',
            'compiledGeometryLookup': True,
            'loopUvSeamsPreserved': True,
            'targetTopologyChanged': False,
            'uvMin': [float(uv_min[0]), float(uv_min[1])],
            'uvMax': [float(uv_max[0]), float(uv_max[1])],
            'layerSelection': selector_qc,
        }
        print('SF3D_NATIVE_UV_TRANSFER=' + json.dumps(qc, indent=2), flush=True)
        return qc
    finally:
        for proxy in (target_proxy, donor_proxy):
            if proxy is not None and proxy.name in bpy.data.objects:
                bpy.data.objects.remove(proxy, do_unlink=True)
        for mesh in (target_proxy_mesh, donor_proxy_mesh):
            if mesh is not None and mesh.users == 0:
                bpy.data.meshes.remove(mesh)


def force_cpu_decode_and_pack(image: bpy.types.Image, path: Path, label: str) -> None:
    if not path.is_file() or path.stat().st_size < 128:
        raise RuntimeError(f"{label} source file disappeared: {path}")
    try:
        width, height = int(image.size[0]), int(image.size[1])
        first_pixel = float(image.pixels[0])
    except Exception as exc:
        raise RuntimeError(f"{label} image failed CPU pixel decode: {path}: {exc}") from exc
    if width <= 0 or height <= 0:
        raise RuntimeError(f"{label} image has invalid decoded dimensions {width}x{height}: {path}")
    try:
        image.pack()
    except Exception as exc:
        raise RuntimeError(f"{label} image could not be packed after decode: {path}: {exc}") from exc
    print(
        f"SF3D_IMAGE_DECODED label={label} file={path.name} size={width}x{height} "
        f"bytes={path.stat().st_size} firstPixel={first_pixel:.6f}",
        flush=True,
    )


def rebind_standard_gltf_pbr(mat: bpy.types.Material, base_path: Path, normal_path: Path) -> None:
    if not mat.use_nodes:
        mat.use_nodes = True
    tree = mat.node_tree
    bsdf = next((n for n in tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
    if bsdf is None:
        raise RuntimeError(f"Material {mat.name} has no Principled BSDF")

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

    force_cpu_decode_and_pack(base_img, base_path, 'BaseColor')
    force_cpu_decode_and_pack(normal_img, normal_path, 'Normal')

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
            resolved = Path(bpy.path.abspath(image.filepath)).resolve() if image.filepath else None
            if resolved is None or not resolved.is_file() or resolved.stat().st_size < 128:
                raise RuntimeError(f"Texture node {node.name} is not backed by a durable file: {resolved}")
            try:
                width, height = int(image.size[0]), int(image.size[1])
                _ = float(image.pixels[0])
            except Exception as exc:
                raise RuntimeError(f"Texture node {node.name} cannot decode its durable source: {exc}") from exc
            if width <= 0 or height <= 0:
                raise RuntimeError(f"Texture node {node.name} has invalid dimensions {width}x{height}")
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


base.transfer_uvs = transfer_uvs_native
base.copy_materials = copy_materials_with_physical_defaults
base.ensure_images_packed = ensure_file_backed_images_ready
base.setup_preview = setup_preview_compat

if __name__ == '__main__':
    raise SystemExit(base.main())
