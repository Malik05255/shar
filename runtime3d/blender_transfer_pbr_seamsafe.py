#!/usr/bin/env python3
"""Seam-safe PBR transfer for the exact Motion V2 hero.

This adapter keeps the proven topology/skin/animation pipeline but replaces the old
per-donor-vertex UV averaging. glTF UVs live on mesh loops/corners, so averaging a
vertex that belongs to multiple UV islands destroys seams and smears the texture.

The target topology is never changed. We first find one nearest donor triangle per
high-detail target vertex (same BVH complexity as the previous transfer). Then each
target polygon chooses the best donor triangle already discovered by its vertices and
writes UVs per TARGET LOOP using the chosen donor triangle's three LOOP UVs. Adjacent
target polygons can therefore place a shared geometric vertex on different UV islands
without duplicating or remeshing geometry.
"""
from __future__ import annotations

import json
import math
import sys
from array import array
from pathlib import Path

import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

# Importing the embedded adapter hydrates donor BaseColor/Normal image payloads and
# patches base.copy_materials deterministically.
import blender_transfer_pbr_embedded as embedded

base = embedded.base


def closest_point_on_triangle(p: Vector, a: Vector, b: Vector, c: Vector) -> Vector:
    """Return the closest point on triangle ABC to P (Ericson region tests)."""
    ab = b - a
    ac = c - a
    ap = p - a
    d1 = ab.dot(ap)
    d2 = ac.dot(ap)
    if d1 <= 0.0 and d2 <= 0.0:
        return a.copy()

    bp = p - b
    d3 = ab.dot(bp)
    d4 = ac.dot(bp)
    if d3 >= 0.0 and d4 <= d3:
        return b.copy()

    vc = d1 * d4 - d3 * d2
    if vc <= 0.0 and d1 >= 0.0 and d3 <= 0.0:
        denom = d1 - d3
        v = d1 / denom if abs(denom) > 1e-20 else 0.0
        return a + ab * v

    cp = p - c
    d5 = ab.dot(cp)
    d6 = ac.dot(cp)
    if d6 >= 0.0 and d5 <= d6:
        return c.copy()

    vb = d5 * d2 - d1 * d6
    if vb <= 0.0 and d2 >= 0.0 and d6 <= 0.0:
        denom = d2 - d6
        w = d2 / denom if abs(denom) > 1e-20 else 0.0
        return a + ac * w

    va = d3 * d6 - d5 * d4
    if va <= 0.0 and (d4 - d3) >= 0.0 and (d5 - d6) >= 0.0:
        denom = (d4 - d3) + (d5 - d6)
        w = (d4 - d3) / denom if abs(denom) > 1e-20 else 0.0
        return b + (c - b) * w

    denom = va + vb + vc
    if abs(denom) <= 1e-20:
        return a.copy()
    inv = 1.0 / denom
    v = vb * inv
    w = vc * inv
    return a + ab * v + ac * w


def donor_seam_diagnostics(mesh: bpy.types.Mesh, uv_layer: bpy.types.MeshUVLoopLayer) -> tuple[int, int, float]:
    per_vertex: list[set[tuple[float, float]]] = [set() for _ in mesh.vertices]
    for loop in mesh.loops:
        uv = uv_layer.data[loop.index].uv
        per_vertex[loop.vertex_index].add((round(float(uv.x), 6), round(float(uv.y), 6)))
    seam_vertices = 0
    seam_uv_variants = 0
    max_span = 0.0
    for variants in per_vertex:
        if len(variants) <= 1:
            continue
        seam_vertices += 1
        seam_uv_variants += len(variants)
        vals = list(variants)
        for i in range(len(vals)):
            for j in range(i + 1, len(vals)):
                du = vals[i][0] - vals[j][0]
                dv = vals[i][1] - vals[j][1]
                max_span = max(max_span, math.sqrt(du * du + dv * dv))
    return seam_vertices, seam_uv_variants, max_span


def transfer_uvs(target: bpy.types.Object, donor: bpy.types.Object) -> dict[str, float | int | str]:
    tmesh, dmesh = target.data, donor.data
    if not dmesh.materials:
        raise RuntimeError("Donor mesh has no material slots")
    donor_uv = dmesh.uv_layers.active
    if donor_uv is None:
        raise RuntimeError("Textured donor has no active UV map")

    dmesh.calc_loop_triangles()
    loop_tris = list(dmesh.loop_triangles)
    if not loop_tris:
        raise RuntimeError("Donor mesh has no loop triangles")

    tmn, tmx = base.local_bounds(target)
    dmn, dmx = base.local_bounds(donor)
    dnorm = [base.normalize_point(v.co, dmn, dmx) for v in dmesh.vertices]
    triangles = [tuple(int(v) for v in tri.vertices) for tri in loop_tris]
    triangle_uvs: list[tuple[Vector, Vector, Vector]] = []
    for tri in loop_tris:
        lis = tuple(int(i) for i in tri.loops)
        triangle_uvs.append(tuple(Vector((donor_uv.data[i].uv.x, donor_uv.data[i].uv.y)) for i in lis))

    bvh = BVHTree.FromPolygons(dnorm, triangles, all_triangles=True)

    # Compact arrays keep the 1.3M-vertex hero memory budget predictable.
    nearest_tri = array('i')
    nearest_distance = array('f')
    distance_sum = 0.0
    max_distance = 0.0
    misses = 0
    for vert in tmesh.vertices:
        p = base.normalize_point(vert.co, tmn, tmx)
        nearest = bvh.find_nearest(p)
        if nearest is None:
            nearest_tri.append(-1)
            nearest_distance.append(float('inf'))
            misses += 1
            continue
        _loc, _normal, face_index, distance = nearest
        nearest_tri.append(int(face_index))
        nearest_distance.append(float(distance))
        distance_sum += float(distance)
        max_distance = max(max_distance, float(distance))
    if misses:
        raise RuntimeError(f"UV transfer missed {misses} target vertices")

    # Use a dedicated active render UV layer. UV data is per-loop, so no topology
    # change is required to preserve a seam at a shared geometric vertex.
    uv = tmesh.uv_layers.get("SeamSafePBR_UV")
    if uv is None:
        uv = tmesh.uv_layers.new(name="SeamSafePBR_UV")
    try:
        tmesh.uv_layers.active = uv
    except Exception:
        pass
    try:
        uv.active_render = True
    except Exception:
        pass

    first_u = array('f', [float('nan')]) * len(tmesh.vertices)
    first_v = array('f', [float('nan')]) * len(tmesh.vertices)
    seam_target_vertices = bytearray(len(tmesh.vertices))
    loops_written = 0
    fallback_polygons = 0

    for poly in tmesh.polygons:
        vertex_ids = tuple(int(v) for v in poly.vertices)
        # A high-detail target polygon is tiny relative to the 10k-vertex donor. Pick
        # the donor triangle belonging to the corner with the smallest projection
        # distance. This avoids an extra BVH query for every target polygon while still
        # allowing adjacent polygons to choose opposite sides of a donor UV seam.
        chosen_vertex = min(vertex_ids, key=lambda vi: nearest_distance[vi])
        tri_index = int(nearest_tri[chosen_vertex])
        if tri_index < 0 or tri_index >= len(triangles):
            fallback_polygons += 1
            continue
        tri = triangles[tri_index]
        tri_uv = triangle_uvs[tri_index]
        a, b, c = dnorm[tri[0]], dnorm[tri[1]], dnorm[tri[2]]

        for loop_index in poly.loop_indices:
            vi = int(tmesh.loops[loop_index].vertex_index)
            p = base.normalize_point(tmesh.vertices[vi].co, tmn, tmx)
            q = closest_point_on_triangle(p, a, b, c)
            bu, bv, bw = base.barycentric(q, a, b, c)
            total = bu + bv + bw
            if abs(total) <= 1e-12:
                bu, bv, bw = 1.0, 0.0, 0.0
            else:
                bu, bv, bw = bu / total, bv / total, bw / total
            mapped = tri_uv[0] * bu + tri_uv[1] * bv + tri_uv[2] * bw
            uv.data[loop_index].uv = mapped
            loops_written += 1

            old_u = first_u[vi]
            old_v = first_v[vi]
            if math.isnan(old_u):
                first_u[vi] = float(mapped.x)
                first_v[vi] = float(mapped.y)
            elif abs(float(mapped.x) - old_u) > 1e-5 or abs(float(mapped.y) - old_v) > 1e-5:
                seam_target_vertices[vi] = 1

    if fallback_polygons:
        raise RuntimeError(f"Seam-safe UV transfer could not map {fallback_polygons} target polygons")
    if loops_written != len(tmesh.loops):
        raise RuntimeError(f"UV loop write mismatch: {loops_written} != {len(tmesh.loops)}")

    donor_seam_vertices, donor_seam_variants, donor_max_uv_span = donor_seam_diagnostics(dmesh, donor_uv)
    created_target_seams = int(sum(seam_target_vertices))
    if donor_seam_vertices > 0 and created_target_seams <= 0:
        raise RuntimeError("Donor has UV seams but target loop mapping created none")

    qc = {
        "algorithm": "nearest-donor-triangle + per-target-polygon loop-corner UV",
        "targetVertices": len(tmesh.vertices),
        "targetPolygons": len(tmesh.polygons),
        "targetLoops": len(tmesh.loops),
        "loopsWritten": loops_written,
        "donorVertices": len(dmesh.vertices),
        "donorTriangles": len(triangles),
        "donorUvSeamVertices": donor_seam_vertices,
        "donorUvSeamVariants": donor_seam_variants,
        "donorMaxUvSeamSpan": donor_max_uv_span,
        "targetVerticesWithPerLoopUvSplits": created_target_seams,
        "meanNormalizedSurfaceDistance": distance_sum / max(1, len(tmesh.vertices)),
        "maxNormalizedSurfaceDistance": max_distance,
    }
    print("SEAM_SAFE_UV_QC=" + json.dumps(qc, indent=2), flush=True)
    return qc


# Make this run visually comparable to the later sparse-finalization stage. The donor
# payload remains the source, but dog fur/uniform should not render as polished metal.
_hydrated_copy_materials = base.copy_materials


def matte_copy_materials(target: bpy.types.Object, donor: bpy.types.Object) -> int:
    count = _hydrated_copy_materials(target, donor)
    for material in target.data.materials:
        if material is None or not material.use_nodes:
            continue
        for node in material.node_tree.nodes:
            if node.type == "BSDF_PRINCIPLED":
                if node.inputs.get("Metallic") is not None:
                    node.inputs["Metallic"].default_value = 0.0
                if node.inputs.get("Roughness") is not None:
                    node.inputs["Roughness"].default_value = 0.68
            elif node.type == "NORMAL_MAP" and node.inputs.get("Strength") is not None:
                node.inputs["Strength"].default_value = 0.28
    return count


base.transfer_uvs = transfer_uvs
base.copy_materials = matte_copy_materials

if __name__ == "__main__":
    raise SystemExit(base.main())
