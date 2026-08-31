#!/usr/bin/env python3
"""Rig the visually accepted SF3D donor with the exact animated hero armature/actions.

This is a fail-closed alternate candidate path. Instead of forcing the donor texture onto
an unrelated dense mesh, it preserves the donor's geometry, UV seams and PBR appearance,
transfers skin weights from the exact 32-joint animated source, subdivides the donor before
binding, and exports a separate candidate with the source armature/actions.

Production manifest activation is intentionally outside this script.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree

import blender_transfer_pbr_candidate as base


EXPECTED_JOINTS = 32
MIN_SUBDIV_VERTICES = 100_000


def args_after_dash() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument('--motion', type=Path, required=True)
    ap.add_argument('--donor', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--qc', type=Path, required=True)
    ap.add_argument('--preview-dir', type=Path, required=True)
    ap.add_argument('--subdivision-levels', type=int, default=2)
    return ap.parse_args(argv)


def bounds(obj: bpy.types.Object) -> tuple[Vector, Vector]:
    return base.local_bounds(obj)


def fit_donor_to_motion(donor: bpy.types.Object, target: bpy.types.Object) -> dict[str, Any]:
    """Uniformly scale by height and center the donor on the animated source."""
    tmn, tmx = bounds(target)
    dmn, dmx = bounds(donor)
    text = tmx - tmn
    dext = dmx - dmn
    if min(text.x, text.y, text.z, dext.x, dext.y, dext.z) <= 1e-8:
        raise RuntimeError('Degenerate target/donor bounds')

    # Blender glTF import is Z-up here. Height-fit preserves donor proportions/identity;
    # using target depth would over-stretch the muzzle/tail axis.
    scale = text.z / dext.z
    tc = (tmn + tmx) * 0.5
    dc = (dmn + dmx) * 0.5
    for v in donor.data.vertices:
        v.co = tc + (v.co - dc) * scale
    donor.data.update()
    amn, amx = bounds(donor)
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
    denom = d00 * d11 - d01 * d01
    if abs(denom) < 1e-12:
        return 1.0, 0.0, 0.0
    v = (d11 * d20 - d01 * d21) / denom
    w = (d00 * d21 - d01 * d20) / denom
    return 1.0 - v - w, v, w


def transfer_skin_weights(
    source: bpy.types.Object,
    donor: bpy.types.Object,
    armature: bpy.types.Object,
) -> dict[str, Any]:
    """Nearest-triangle barycentric transfer for only the ~10k donor vertices."""
    if len(source.vertex_groups) != EXPECTED_JOINTS:
        raise RuntimeError(f'Expected {EXPECTED_JOINTS} source vertex groups, got {len(source.vertex_groups)}')

    donor.vertex_groups.clear()
    group_names = [g.name for g in source.vertex_groups]
    for name in group_names:
        donor.vertex_groups.new(name=name)

    source_verts = [v.co.copy() for v in source.data.vertices]
    triangles: list[tuple[int, int, int]] = []
    polygon_indices: list[int] = []
    for poly in source.data.polygons:
        ids = tuple(poly.vertices)
        if len(ids) == 3:
            triangles.append(ids)
            polygon_indices.append(poly.index)
        elif len(ids) > 3:
            for i in range(1, len(ids) - 1):
                triangles.append((ids[0], ids[i], ids[i + 1]))
                polygon_indices.append(poly.index)
    if not triangles:
        raise RuntimeError('Animated source has no triangles')
    bvh = BVHTree.FromPolygons(source_verts, triangles, all_triangles=True)

    misses = 0
    distances: list[float] = []
    nonzero_group_hits = [0] * len(group_names)
    for dv in donor.data.vertices:
        nearest = bvh.find_nearest(dv.co)
        if nearest is None:
            misses += 1
            continue
        loc, _normal, face_index, distance = nearest
        tri = triangles[face_index]
        a, b, c = source_verts[tri[0]], source_verts[tri[1]], source_verts[tri[2]]
        u, v, w = barycentric(loc, a, b, c)
        coeffs = (u, v, w)
        accum: dict[int, float] = {}
        for coeff, svi in zip(coeffs, tri):
            for membership in source.data.vertices[svi].groups:
                weight = max(0.0, float(membership.weight) * float(coeff))
                if weight > 1e-8:
                    accum[membership.group] = accum.get(membership.group, 0.0) + weight
        if not accum:
            # Use the nearest triangle vertex's strongest group rather than leaving an
            # unweighted production vertex.
            nearest_vi = min(tri, key=lambda idx: (source_verts[idx] - loc).length_squared)
            source_groups = list(source.data.vertices[nearest_vi].groups)
            if not source_groups:
                raise RuntimeError(f'No source weights near donor vertex {dv.index}')
            strongest = max(source_groups, key=lambda g: g.weight)
            accum[int(strongest.group)] = float(strongest.weight)

        # Match glTF's practical four-influence skin limit and normalize exactly.
        strongest_items = sorted(accum.items(), key=lambda item: item[1], reverse=True)[:4]
        total = sum(weight for _idx, weight in strongest_items)
        if total <= 1e-8:
            raise RuntimeError(f'Degenerate transferred weights on donor vertex {dv.index}')
        for group_idx, weight in strongest_items:
            normalized = weight / total
            donor.vertex_groups[group_idx].add([dv.index], normalized, 'REPLACE')
            if normalized > 1e-6:
                nonzero_group_hits[group_idx] += 1
        distances.append(float(distance))

    if misses:
        raise RuntimeError(f'Skin transfer missed {misses} donor vertices')

    # Source groups intentionally include controller-only joints that may have no mesh
    # weights. Record rather than fabricate facial weights that did not exist upstream.
    used_groups = [group_names[i] for i, count in enumerate(nonzero_group_hits) if count > 0]
    unused_groups = [group_names[i] for i, count in enumerate(nonzero_group_hits) if count == 0]

    donor.parent = armature
    donor.parent_type = 'OBJECT'
    arm_mod = donor.modifiers.new(name='POLICE_DOG_ARMATURE', type='ARMATURE')
    arm_mod.object = armature

    return {
        'sourceVertices': len(source.data.vertices),
        'donorVerticesBeforeSubdivision': len(donor.data.vertices),
        'triangles': len(triangles),
        'misses': misses,
        'meanSurfaceDistance': sum(distances) / max(1, len(distances)),
        'maxSurfaceDistance': max(distances, default=0.0),
        'usedWeightGroups': used_groups,
        'unusedWeightGroups': unused_groups,
    }


def apply_subdivision(donor: bpy.types.Object, levels: int) -> dict[str, int]:
    if levels < 1 or levels > 3:
        raise RuntimeError(f'Subdivision levels must be 1..3, got {levels}')
    before_v = len(donor.data.vertices)
    before_p = len(donor.data.polygons)

    # Apply subdivision before armature deformation while preserving UVs and interpolated
    # vertex-group weights. Temporarily move the armature modifier after subdivision.
    arm_mods = [m for m in donor.modifiers if m.type == 'ARMATURE']
    for m in arm_mods:
        donor.modifiers.remove(m)
    sub = donor.modifiers.new(name='CINEMATIC_SUBDIVISION', type='SUBSURF')
    sub.subdivision_type = 'CATMULL_CLARK'
    sub.levels = levels
    sub.render_levels = levels
    sub.show_only_control_edges = False
    bpy.ops.object.select_all(action='DESELECT')
    donor.select_set(True)
    bpy.context.view_layer.objects.active = donor
    result = bpy.ops.object.modifier_apply(modifier=sub.name)
    if 'FINISHED' not in result:
        raise RuntimeError(f'Failed to apply donor subdivision: {result}')

    for poly in donor.data.polygons:
        poly.use_smooth = True

    # Rebind the exact source armature after topology refinement.
    armature = donor.parent
    arm_mod = donor.modifiers.new(name='POLICE_DOG_ARMATURE', type='ARMATURE')
    arm_mod.object = armature

    after_v = len(donor.data.vertices)
    after_p = len(donor.data.polygons)
    if after_v < MIN_SUBDIV_VERTICES:
        raise RuntimeError(f'Subdivided donor is below cinematic density gate: {after_v} < {MIN_SUBDIV_VERTICES}')
    return {
        'verticesBefore': before_v,
        'polygonsBefore': before_p,
        'verticesAfter': after_v,
        'polygonsAfter': after_p,
        'levels': levels,
    }


def weighted_ratio(obj: bpy.types.Object) -> tuple[int, float, int]:
    weighted = 0
    influences = 0
    for v in obj.data.vertices:
        valid = [g for g in v.groups if float(g.weight) > 1e-8]
        if valid:
            weighted += 1
            influences += len(valid)
    return weighted, weighted / max(1, len(obj.data.vertices)), influences


def ensure_material_payload(donor: bpy.types.Object) -> dict[str, int]:
    if donor.data.uv_layers.active is None:
        raise RuntimeError('SF3D donor lost its UV map')
    mats = [m for m in donor.data.materials if m]
    if not mats:
        raise RuntimeError('SF3D donor lost its PBR material')
    images = set()
    texture_nodes = 0
    for mat in mats:
        if not mat.use_nodes:
            continue
        for node in mat.node_tree.nodes:
            if node.type == 'TEX_IMAGE' and getattr(node, 'image', None):
                texture_nodes += 1
                images.add(node.image)
                try:
                    _ = float(node.image.pixels[0])
                    node.image.pack()
                except Exception as exc:
                    raise RuntimeError(f'PBR image cannot be decoded/packed: {exc}') from exc
    if texture_nodes < 2 or len(images) < 2:
        raise RuntimeError(f'Expected BaseColor+Normal payload, got nodes={texture_nodes}, images={len(images)}')
    return {'materials': len(mats), 'textureNodes': texture_nodes, 'images': len(images)}


def setup_preview(target_mesh: bpy.types.Object, preview_dir: Path) -> list[str]:
    preview_dir.mkdir(parents=True, exist_ok=True)
    for obj in bpy.data.objects:
        obj.hide_render = obj != target_mesh and obj.type not in {'LIGHT', 'CAMERA'}
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

    mn, mx = bounds(target_mesh)
    center = (mn + mx) * 0.5
    radius = max((mx - mn).x, (mx - mn).y, (mx - mn).z) * 1.35
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
    files: list[str] = []
    for name, offset in views:
        cam.location = center + offset
        point(cam, center)
        path = preview_dir / f'police_dog.rigged_sf3d.{name}.png'
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        files.append(path.name)
    return files


def main() -> int:
    args = args_after_dash()
    if not args.motion.is_file() or not args.donor.is_file():
        raise SystemExit('Motion or donor GLB missing')

    bpy.ops.wm.read_factory_settings(use_empty=True)
    motion_objects = base.import_glb(args.motion)
    motion_meshes = base.mesh_objects(motion_objects)
    motion_arms = base.armature_objects(motion_objects)
    source_mesh, source_diagnostics = base.select_skinned_hero(motion_meshes, motion_arms)
    if len(motion_arms) != 1:
        raise RuntimeError(f'Expected exactly one animated armature, got {len(motion_arms)}')
    armature = motion_arms[0]
    source_actions = sorted({a.name for a in bpy.data.actions})
    if len(source_actions) != 26:
        raise RuntimeError(f'Expected exact 26 source actions, got {len(source_actions)}: {source_actions}')

    donor_objects = base.import_glb(args.donor)
    donor_meshes = base.mesh_objects(donor_objects)
    if not donor_meshes:
        raise RuntimeError('SF3D donor contains no mesh')
    donor = max(donor_meshes, key=lambda obj: len(obj.data.vertices))
    donor.name = 'POLICE_DOG_RIGGED_SF3D_CANDIDATE'
    original_material = ensure_material_payload(donor)
    alignment = fit_donor_to_motion(donor, source_mesh)
    weight_qc = transfer_skin_weights(source_mesh, donor, armature)
    subdivision = apply_subdivision(donor, args.subdivision_levels)
    material_qc = ensure_material_payload(donor)
    weighted_vertices, ratio, influences = weighted_ratio(donor)
    if ratio < 0.999:
        raise RuntimeError(f'Rigged SF3D weighted-vertex gate failed: ratio={ratio}')

    # Delete source/helper meshes but retain the exact armature/actions. This candidate is
    # intentionally donor-geometry-first to preserve the accepted SF3D identity/PBR.
    for mesh in motion_meshes:
        if mesh.name in bpy.data.objects:
            bpy.data.objects.remove(mesh, do_unlink=True)
    for obj in donor_objects:
        if obj != donor and obj.name in bpy.data.objects:
            bpy.data.objects.remove(obj, do_unlink=True)

    previews = setup_preview(donor, args.preview_dir)
    for name in ('QC_CAMERA', 'QC_KEY', 'QC_FILL', 'QC_RIM'):
        obj = bpy.data.objects.get(name)
        if obj:
            bpy.data.objects.remove(obj, do_unlink=True)

    bpy.ops.object.select_all(action='DESELECT')
    donor.select_set(True)
    armature.select_set(True)
    bpy.context.view_layer.objects.active = donor
    args.output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(args.output),
        export_format='GLB',
        use_selection=True,
        export_yup=True,
        export_apply=False,
        export_animations=True,
        export_nla_strips=True,
        export_materials='EXPORT',
        export_image_format='AUTO',
        export_texcoords=True,
        export_normals=True,
        export_skins=True,
        export_all_influences=False,
    )
    if not args.output.is_file() or args.output.stat().st_size < 1_000_000:
        raise RuntimeError('Rigged SF3D output GLB missing or unexpectedly small')

    actions_after = sorted({a.name for a in bpy.data.actions})
    if actions_after != source_actions:
        raise RuntimeError('Source action set changed while rigging SF3D donor')

    qc = {
        'productionReady': False,
        'purpose': 'rig-first-sf3d-identity-candidate',
        'freeOnly': True,
        'paidFallbackAllowed': False,
        'manifestActivation': False,
        'sourceMotionHero': args.motion.name,
        'sourcePbrDonor': args.donor.name,
        'sourceMeshDiagnostics': source_diagnostics,
        'alignment': alignment,
        'skinTransfer': weight_qc,
        'subdivision': subdivision,
        'materialBefore': original_material,
        'materialAfter': material_qc,
        'weightedVertices': weighted_vertices,
        'weightedRatio': ratio,
        'totalInfluences': influences,
        'armatureBones': len(armature.data.bones),
        'actions': actions_after,
        'previewFiles': previews,
        'knownGaps': [
            'Rendered identity and uniform fidelity must pass manual visual review',
            'Transferred deformation must pass motion/facial review before production',
            'Source rig has controller joints with no source mesh weights; no fake weights are fabricated',
            'Physical Android frame pacing remains open',
        ],
        'productionGate': 'CLOSED',
    }
    args.qc.parent.mkdir(parents=True, exist_ok=True)
    args.qc.write_text(json.dumps(qc, indent=2), encoding='utf-8')
    print(json.dumps(qc, indent=2), flush=True)
    print('RIGGED_SF3D_CANDIDATE_GATE=PASS', flush=True)
    print('PRODUCTION_GATE=CLOSED', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
