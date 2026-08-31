#!/usr/bin/env python3
"""Rig-first SF3D hero candidate with durable embedded PBR textures.

Keeps the visually accepted SF3D geometry/UV/PBR, transfers the exact animated hero skin
weights/armature/actions, refines geometry with Catmull-Clark subdivision, and exports a
separate fail-closed candidate. Production activation is intentionally impossible here.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree

import blender_transfer_pbr_candidate as base
import blender_transfer_sf3d_candidate as sf3d

EXPECTED_JOINTS = 32
MIN_SUBDIV_VERTICES = 100_000


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    p = argparse.ArgumentParser()
    p.add_argument('--motion', type=Path, required=True)
    p.add_argument('--donor', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--qc', type=Path, required=True)
    p.add_argument('--preview-dir', type=Path, required=True)
    p.add_argument('--subdivision-levels', type=int, default=2)
    return p.parse_args(argv)


def local_bounds(obj: bpy.types.Object) -> tuple[Vector, Vector]:
    return base.local_bounds(obj)


def required_env_path(name: str) -> Path:
    raw = os.environ.get(name, '').strip()
    if not raw:
        raise RuntimeError(f'Missing required durable texture env: {name}')
    path = Path(raw).resolve()
    if not path.is_file() or path.stat().st_size < 128:
        raise RuntimeError(f'Durable texture missing/empty for {name}: {path}')
    return path


def rebind_durable_pbr(donor: bpy.types.Object) -> dict[str, Any]:
    base_path = required_env_path('SF3D_BASECOLOR_PATH')
    normal_path = required_env_path('SF3D_NORMAL_PATH')
    mats = [m for m in donor.data.materials if m]
    if not mats:
        raise RuntimeError('SF3D donor has no material')
    for mat in mats:
        sf3d.rebind_standard_gltf_pbr(mat, base_path, normal_path)
    nodes, images = sf3d.ensure_file_backed_images_ready(mats)
    if nodes < 2 or images < 2:
        raise RuntimeError(f'Durable PBR rebind failed: nodes={nodes}, images={images}')
    return {
        'materials': len(mats),
        'textureNodes': nodes,
        'images': images,
        'baseColorFile': base_path.name,
        'baseColorBytes': base_path.stat().st_size,
        'normalFile': normal_path.name,
        'normalBytes': normal_path.stat().st_size,
    }


def fit_by_height_and_center(donor: bpy.types.Object, target: bpy.types.Object) -> dict[str, Any]:
    tmn, tmx = local_bounds(target)
    dmn, dmx = local_bounds(donor)
    te, de = tmx - tmn, dmx - dmn
    if min(te.x, te.y, te.z, de.x, de.y, de.z) <= 1e-8:
        raise RuntimeError('Degenerate target/donor bounds')
    scale = te.z / de.z
    tc, dc = (tmn + tmx) * 0.5, (dmn + dmx) * 0.5
    for v in donor.data.vertices:
        v.co = tc + (v.co - dc) * scale
    donor.data.update()
    amn, amx = local_bounds(donor)
    return {
        'uniformScale': float(scale),
        'targetMin': list(map(float, tmn)),
        'targetMax': list(map(float, tmx)),
        'donorBeforeMin': list(map(float, dmn)),
        'donorBeforeMax': list(map(float, dmx)),
        'donorAfterMin': list(map(float, amn)),
        'donorAfterMax': list(map(float, amx)),
    }


def barycentric(p: Vector, a: Vector, b: Vector, c: Vector) -> tuple[float, float, float]:
    v0, v1, v2 = b - a, c - a, p - a
    d00, d01, d11 = v0.dot(v0), v0.dot(v1), v1.dot(v1)
    d20, d21 = v2.dot(v0), v2.dot(v1)
    den = d00 * d11 - d01 * d01
    if abs(den) < 1e-12:
        return 1.0, 0.0, 0.0
    v = (d11 * d20 - d01 * d21) / den
    w = (d00 * d21 - d01 * d20) / den
    return 1.0 - v - w, v, w


def transfer_weights(source: bpy.types.Object, donor: bpy.types.Object, armature: bpy.types.Object) -> dict[str, Any]:
    if len(source.vertex_groups) != EXPECTED_JOINTS:
        raise RuntimeError(f'Expected {EXPECTED_JOINTS} source groups, got {len(source.vertex_groups)}')
    donor.vertex_groups.clear()
    names = [g.name for g in source.vertex_groups]
    for name in names:
        donor.vertex_groups.new(name=name)

    src = [v.co.copy() for v in source.data.vertices]
    triangles: list[tuple[int, int, int]] = []
    for poly in source.data.polygons:
        ids = tuple(poly.vertices)
        if len(ids) == 3:
            triangles.append(ids)
        elif len(ids) > 3:
            triangles.extend((ids[0], ids[i], ids[i + 1]) for i in range(1, len(ids) - 1))
    if not triangles:
        raise RuntimeError('Animated source contains no triangles')
    bvh = BVHTree.FromPolygons(src, triangles, all_triangles=True)

    hits = [0] * len(names)
    distances: list[float] = []
    for dv in donor.data.vertices:
        nearest = bvh.find_nearest(dv.co)
        if nearest is None:
            raise RuntimeError(f'No source surface found for donor vertex {dv.index}')
        loc, _normal, face_index, distance = nearest
        tri = triangles[face_index]
        coeffs = barycentric(loc, src[tri[0]], src[tri[1]], src[tri[2]])
        accum: dict[int, float] = {}
        for coeff, source_vi in zip(coeffs, tri):
            for membership in source.data.vertices[source_vi].groups:
                value = max(0.0, float(coeff) * float(membership.weight))
                if value > 1e-8:
                    accum[int(membership.group)] = accum.get(int(membership.group), 0.0) + value
        if not accum:
            closest_vi = min(tri, key=lambda i: (src[i] - loc).length_squared)
            memberships = list(source.data.vertices[closest_vi].groups)
            if not memberships:
                raise RuntimeError(f'Nearest source vertex has no skin weights for donor vertex {dv.index}')
            strongest = max(memberships, key=lambda g: g.weight)
            accum[int(strongest.group)] = float(strongest.weight)
        strongest4 = sorted(accum.items(), key=lambda item: item[1], reverse=True)[:4]
        total = sum(w for _g, w in strongest4)
        if total <= 1e-8:
            raise RuntimeError(f'Degenerate skin weights for donor vertex {dv.index}')
        for group_idx, weight in strongest4:
            value = weight / total
            donor.vertex_groups[group_idx].add([dv.index], value, 'REPLACE')
            if value > 1e-6:
                hits[group_idx] += 1
        distances.append(float(distance))

    donor.parent = armature
    donor.parent_type = 'OBJECT'
    mod = donor.modifiers.new(name='POLICE_DOG_ARMATURE', type='ARMATURE')
    mod.object = armature
    return {
        'sourceVertices': len(source.data.vertices),
        'donorVertices': len(donor.data.vertices),
        'sourceTriangles': len(triangles),
        'meanSurfaceDistance': sum(distances) / max(1, len(distances)),
        'maxSurfaceDistance': max(distances, default=0.0),
        'usedWeightGroups': [names[i] for i, count in enumerate(hits) if count > 0],
        'unusedWeightGroups': [names[i] for i, count in enumerate(hits) if count == 0],
    }


def subdivide(donor: bpy.types.Object, levels: int) -> dict[str, int]:
    if levels not in (1, 2, 3):
        raise RuntimeError(f'Invalid subdivision level: {levels}')
    before_v, before_p = len(donor.data.vertices), len(donor.data.polygons)
    armature = donor.parent
    for mod in list(donor.modifiers):
        if mod.type == 'ARMATURE':
            donor.modifiers.remove(mod)
    sub = donor.modifiers.new(name='CINEMATIC_SUBDIVISION', type='SUBSURF')
    sub.subdivision_type = 'CATMULL_CLARK'
    sub.levels = levels
    sub.render_levels = levels
    bpy.ops.object.select_all(action='DESELECT')
    donor.select_set(True)
    bpy.context.view_layer.objects.active = donor
    result = bpy.ops.object.modifier_apply(modifier=sub.name)
    if 'FINISHED' not in result:
        raise RuntimeError(f'Subdivision apply failed: {result}')
    for poly in donor.data.polygons:
        poly.use_smooth = True
    arm_mod = donor.modifiers.new(name='POLICE_DOG_ARMATURE', type='ARMATURE')
    arm_mod.object = armature
    after_v, after_p = len(donor.data.vertices), len(donor.data.polygons)
    if after_v < MIN_SUBDIV_VERTICES:
        raise RuntimeError(f'Cinematic density gate failed: {after_v} < {MIN_SUBDIV_VERTICES}')
    return {'levels': levels, 'verticesBefore': before_v, 'polygonsBefore': before_p,
            'verticesAfter': after_v, 'polygonsAfter': after_p}


def weight_stats(obj: bpy.types.Object) -> tuple[int, float, int]:
    weighted = influences = 0
    for v in obj.data.vertices:
        memberships = [g for g in v.groups if float(g.weight) > 1e-8]
        if memberships:
            weighted += 1
            influences += len(memberships)
    return weighted, weighted / max(1, len(obj.data.vertices)), influences


def render_qc(mesh: bpy.types.Object, output_dir: Path) -> list[str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    for obj in bpy.data.objects:
        obj.hide_render = obj != mesh and obj.type not in {'LIGHT', 'CAMERA'}
    scene = bpy.context.scene
    if scene.world is None:
        scene.world = bpy.data.worlds.new('QC_WORLD')
    scene.world.color = (0.025, 0.025, 0.025)
    try:
        scene.render.engine = 'BLENDER_EEVEE_NEXT'
    except Exception:
        scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.film_transparent = False

    mn, mx = local_bounds(mesh)
    center = (mn + mx) * 0.5
    radius = max((mx - mn).x, (mx - mn).y, (mx - mn).z) * 1.35
    cam_data = bpy.data.cameras.new('QC_CAMERA')
    cam = bpy.data.objects.new('QC_CAMERA', cam_data)
    bpy.context.collection.objects.link(cam)
    scene.camera = cam
    cam.data.lens = 62

    def point(obj: bpy.types.Object, target: Vector) -> None:
        obj.rotation_euler = (target - obj.location).to_track_quat('-Z', 'Y').to_euler()

    for name, energy, size_scale, direction in [
        ('QC_KEY', 1100, 0.85, Vector((1.35, -1.15, 1.35))),
        ('QC_FILL', 500, 0.75, Vector((-1.0, -0.7, 0.65))),
        ('QC_RIM', 700, 0.60, Vector((-0.35, 1.15, 1.2))),
    ]:
        ld = bpy.data.lights.new(name, type='AREA')
        ld.energy, ld.size = energy, radius * size_scale
        obj = bpy.data.objects.new(name, ld)
        bpy.context.collection.objects.link(obj)
        obj.location = center + direction * radius
        point(obj, center)

    files: list[str] = []
    for name, offset in [
        ('front', Vector((0.0, -radius * 2.4, radius * 0.12))),
        ('three_quarter', Vector((radius * 1.4, -radius * 2.0, radius * 0.18))),
        ('side', Vector((radius * 2.3, 0.0, radius * 0.15))),
        ('back', Vector((0.0, radius * 2.4, radius * 0.12))),
    ]:
        cam.location = center + offset
        point(cam, center)
        path = output_dir / f'police_dog.rigged_sf3d.{name}.png'
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        files.append(path.name)
    return files


def main() -> int:
    args = parse_args()
    if not args.motion.is_file() or not args.donor.is_file():
        raise SystemExit('Motion or donor GLB missing')

    bpy.ops.wm.read_factory_settings(use_empty=True)
    motion_objects = base.import_glb(args.motion)
    motion_meshes = base.mesh_objects(motion_objects)
    armatures = base.armature_objects(motion_objects)
    source_mesh, source_diag = base.select_skinned_hero(motion_meshes, armatures)
    if len(armatures) != 1:
        raise RuntimeError(f'Expected one armature, got {len(armatures)}')
    armature = armatures[0]
    actions_before = sorted(a.name for a in bpy.data.actions)
    if len(actions_before) != 26:
        raise RuntimeError(f'Expected exact 26 actions, got {len(actions_before)}')

    donor_objects = base.import_glb(args.donor)
    donor_meshes = base.mesh_objects(donor_objects)
    if not donor_meshes:
        raise RuntimeError('SF3D donor contains no mesh')
    donor = max(donor_meshes, key=lambda obj: len(obj.data.vertices))
    donor.name = 'POLICE_DOG_RIGGED_SF3D_CANDIDATE'

    pbr_before = rebind_durable_pbr(donor)
    alignment = fit_by_height_and_center(donor, source_mesh)
    skin = transfer_weights(source_mesh, donor, armature)
    subdivision = subdivide(donor, args.subdivision_levels)
    pbr_after = rebind_durable_pbr(donor)
    weighted, ratio, influences = weight_stats(donor)
    if ratio < 0.999:
        raise RuntimeError(f'Weighted vertex gate failed: ratio={ratio}')

    # Remove only the obsolete source/helper meshes. Keep the exact source armature/actions.
    for mesh in motion_meshes:
        if mesh.name in bpy.data.objects:
            bpy.data.objects.remove(mesh, do_unlink=True)
    for obj in donor_objects:
        if obj != donor and obj.name in bpy.data.objects:
            bpy.data.objects.remove(obj, do_unlink=True)

    previews = render_qc(donor, args.preview_dir)
    for name in ('QC_CAMERA', 'QC_KEY', 'QC_FILL', 'QC_RIM'):
        obj = bpy.data.objects.get(name)
        if obj:
            bpy.data.objects.remove(obj, do_unlink=True)

    # Re-assert durable PBR immediately before export; rendering must not be allowed to
    # leave transient imported GLB image buffers as the export source.
    pbr_export = rebind_durable_pbr(donor)
    bpy.ops.object.select_all(action='DESELECT')
    donor.select_set(True)
    armature.select_set(True)
    bpy.context.view_layer.objects.active = donor
    args.output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(args.output), export_format='GLB', use_selection=True,
        export_yup=True, export_apply=False, export_animations=True,
        export_nla_strips=True, export_materials='EXPORT', export_image_format='AUTO',
        export_texcoords=True, export_normals=True, export_skins=True,
        export_all_influences=False,
    )
    if not args.output.is_file() or args.output.stat().st_size < 1_000_000:
        raise RuntimeError('Rigged SF3D GLB missing or unexpectedly small')

    actions_after = sorted(a.name for a in bpy.data.actions)
    if actions_after != actions_before:
        raise RuntimeError('Action set changed during rig-first pipeline')

    qc = {
        'productionReady': False,
        'purpose': 'rig-first-sf3d-identity-candidate-v2',
        'freeOnly': True,
        'paidFallbackAllowed': False,
        'manifestActivation': False,
        'sourceMotionHero': args.motion.name,
        'sourcePbrDonor': args.donor.name,
        'sourceMeshDiagnostics': source_diag,
        'alignment': alignment,
        'skinTransfer': skin,
        'subdivision': subdivision,
        'pbrBefore': pbr_before,
        'pbrAfterSubdivision': pbr_after,
        'pbrAtExport': pbr_export,
        'weightedVertices': weighted,
        'weightedRatio': ratio,
        'totalInfluences': influences,
        'armatureBones': len(armature.data.bones),
        'actions': actions_after,
        'previewFiles': previews,
        'knownGaps': [
            'Static four-view identity must pass manual visual review',
            'Representative animation deformation must pass rendered motion review',
            'Controller-only source joints with no upstream mesh weights are not fabricated',
            'Physical Android frame pacing remains open',
        ],
        'productionGate': 'CLOSED',
    }
    args.qc.parent.mkdir(parents=True, exist_ok=True)
    args.qc.write_text(json.dumps(qc, indent=2), encoding='utf-8')
    print(json.dumps(qc, indent=2), flush=True)
    print('RIGGED_SF3D_CANDIDATE_V2_GATE=PASS', flush=True)
    print('PRODUCTION_GATE=CLOSED', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
