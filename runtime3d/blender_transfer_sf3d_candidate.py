#!/usr/bin/env python3
"""Transfer a free Stable Fast 3D donor appearance onto the exact animated hero.

This wraps the existing topology-preserving transfer implementation and only adds
Blender-4.0-compatible QC rendering plus a conservative non-plastic material tune.
It never changes target topology, armature, skin weights or authored animation clips.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Blender executes --python scripts without guaranteeing that the script directory
# is on sys.path. Resolve the sibling transfer module explicitly and fail closed if
# repository layout changes.
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import bpy
from mathutils import Vector

import blender_transfer_pbr_candidate as base


_original_copy_materials = base.copy_materials


def copy_materials_with_physical_defaults(target: bpy.types.Object, donor: bpy.types.Object) -> int:
    count = _original_copy_materials(target, donor)
    # SF3D's single-image donor can render overly glossy under strong QC lights.
    # Keep its image textures/normal map intact, but use a fabric/fur-appropriate
    # dielectric baseline. This is a material-parameter tune only, not a texture fake.
    for mat in target.data.materials:
        if not mat or not mat.use_nodes:
            continue
        for node in mat.node_tree.nodes:
            if node.type != 'BSDF_PRINCIPLED':
                continue
            rough = node.inputs.get('Roughness')
            if rough is not None and float(rough.default_value) < 0.62:
                rough.default_value = 0.62
            metallic = node.inputs.get('Metallic')
            if metallic is not None:
                metallic.default_value = 0.0
            spec = node.inputs.get('Specular IOR Level') or node.inputs.get('Specular')
            if spec is not None and float(spec.default_value) > 0.38:
                spec.default_value = 0.38
    return count


def setup_preview_compat(target_mesh: bpy.types.Object, preview_dir):
    preview_dir.mkdir(parents=True, exist_ok=True)
    for obj in bpy.data.objects:
        obj.hide_render = obj != target_mesh and obj.type not in {'LIGHT', 'CAMERA'}

    scene = bpy.context.scene
    if scene.world is None:
        scene.world = bpy.data.worlds.new('QC_WORLD')
    scene.world.color = (0.025, 0.025, 0.025)
    # Ubuntu 24.04 currently ships Blender 4.0.x where this identifier is BLENDER_EEVEE.
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
base.setup_preview = setup_preview_compat

if __name__ == '__main__':
    raise SystemExit(base.main())
