#!/usr/bin/env python3
"""Semantic + normal-aware PBR transfer for the exact Motion V2 hero.

The previous nearest-surface transfer proved that UV seam handling was not the main
visual problem: the SF3D donor is a different surface, so a geometrically-near point
can belong to the wrong semantic body part.  This adapter keeps the exact high-detail
Motion V2 topology/skin/animations and uses information already present in its rig to
constrain texture correspondence.

Pipeline:
1. classify every target vertex from its dominant skin group into head / torso /
   left/right arm / left/right leg / tail;
2. learn each target region's normalized spatial distribution;
3. assign donor triangles to the nearest learned semantic region;
4. learn a local donor distribution per semantic region and warp target query points
   into that region (part-local normalization rather than one global body box);
5. split donor triangles by dominant surface-normal direction so front/back/side
   surfaces cannot freely steal texture from one another;
6. write UVs per target loop/corner, preserving any UV discontinuities without
   remeshing, decimation, or topology changes.

The script deliberately remains a candidate generator.  Rendered visual review is the
production gate; numerical success never enables runtime3d automatically.
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

# Hydrates packed donor BaseColor/Normal payloads and patches base.copy_materials.
import blender_transfer_pbr_embedded as embedded

base = embedded.base

CATEGORY_NAMES = ("head", "torso", "arm.L", "arm.R", "leg.L", "leg.R", "tail")
CAT_HEAD, CAT_TORSO, CAT_ARM_L, CAT_ARM_R, CAT_LEG_L, CAT_LEG_R, CAT_TAIL = range(7)
NORMAL_NAMES = ("+X", "-X", "+Y", "-Y", "+Z", "-Z")


def category_for_group_name(name: str) -> int:
    n = (name or "").lower()
    if n.startswith("tail"):
        return CAT_TAIL
    if n in {"neck", "head", "jaw", "muzzle_ctrl", "eye.l", "eye.r", "ear.l", "ear.r"}:
        return CAT_HEAD
    if n.endswith(".l") and any(k in n for k in ("clavicle", "upper_arm", "forearm", "hand")):
        return CAT_ARM_L
    if n.endswith(".r") and any(k in n for k in ("clavicle", "upper_arm", "forearm", "hand")):
        return CAT_ARM_R
    if n.endswith(".l") and any(k in n for k in ("thigh", "shin", "foot", "toe")):
        return CAT_LEG_L
    if n.endswith(".r") and any(k in n for k in ("thigh", "shin", "foot", "toe")):
        return CAT_LEG_R
    return CAT_TORSO


def normalized_normal(normal: Vector, extent: Vector) -> Vector:
    # x' = x / extent => n' = inverse(transpose(A)) n = extent * n.
    n = Vector((normal.x * extent.x, normal.y * extent.y, normal.z * extent.z))
    if n.length_squared <= 1e-20:
        return Vector((0.0, 0.0, 1.0))
    return n.normalized()


def normal_bucket(n: Vector) -> int:
    vals = (abs(n.x), abs(n.y), abs(n.z))
    axis = max(range(3), key=lambda i: vals[i])
    value = (n.x, n.y, n.z)[axis]
    return axis * 2 + (0 if value >= 0.0 else 1)


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


def moments_to_stats(counts: list[int], sums: list[list[float]], sums2: list[list[float]]) -> tuple[list[Vector], list[Vector]]:
    means: list[Vector] = []
    stds: list[Vector] = []
    for ci in range(len(counts)):
        c = max(1, counts[ci])
        mean = Vector(tuple(sums[ci][a] / c for a in range(3)))
        var = [max(0.0, sums2[ci][a] / c - mean[a] * mean[a]) for a in range(3)]
        # A floor prevents a compact region (notably tail/head) from becoming an
        # infinitely strong attractor/rejector during donor classification.
        std = Vector(tuple(max(0.075, math.sqrt(v)) for v in var))
        means.append(mean)
        stds.append(std)
    return means, stds


def standardized_region_distance(p: Vector, mean: Vector, std: Vector) -> float:
    # Depth is useful, but lateral position and height are more semantically stable
    # between independently reconstructed character surfaces.  We do not assume a
    # fixed Blender axis for height; using all axes with robust std keeps this generic.
    return sum(((p[a] - mean[a]) / max(0.075, std[a])) ** 2 for a in range(3))


def build_target_semantics(target: bpy.types.Object, tmn: Vector, tmx: Vector):
    mesh = target.data
    group_to_category = [category_for_group_name(g.name) for g in target.vertex_groups]
    categories = bytearray(len(mesh.vertices))
    counts = [0] * len(CATEGORY_NAMES)
    sums = [[0.0, 0.0, 0.0] for _ in CATEGORY_NAMES]
    sums2 = [[0.0, 0.0, 0.0] for _ in CATEGORY_NAMES]

    for v in mesh.vertices:
        best_group = None
        best_weight = -1.0
        for link in v.groups:
            if link.weight > best_weight:
                best_weight = float(link.weight)
                best_group = int(link.group)
        if best_group is None or best_group < 0 or best_group >= len(group_to_category):
            ci = CAT_TORSO
        else:
            ci = group_to_category[best_group]
        categories[v.index] = ci
        p = base.normalize_point(v.co, tmn, tmx)
        counts[ci] += 1
        for a in range(3):
            value = float(p[a])
            sums[ci][a] += value
            sums2[ci][a] += value * value

    missing = [CATEGORY_NAMES[i] for i, c in enumerate(counts) if c <= 0]
    if missing:
        raise RuntimeError(f"Target semantic skin categories missing vertices: {missing}")
    means, stds = moments_to_stats(counts, sums, sums2)
    return categories, counts, means, stds


def classify_donor_triangles(
    triangles: list[tuple[int, int, int]],
    dnorm: list[Vector],
    target_means: list[Vector],
    target_stds: list[Vector],
):
    triangle_categories = bytearray(len(triangles))
    triangle_buckets = bytearray(len(triangles))
    by_category: list[list[int]] = [[] for _ in CATEGORY_NAMES]
    by_category_bucket: list[list[list[int]]] = [[[] for _ in NORMAL_NAMES] for _ in CATEGORY_NAMES]
    counts = [0] * len(CATEGORY_NAMES)
    sums = [[0.0, 0.0, 0.0] for _ in CATEGORY_NAMES]
    sums2 = [[0.0, 0.0, 0.0] for _ in CATEGORY_NAMES]

    for ti, tri in enumerate(triangles):
        a, b, c = (dnorm[tri[0]], dnorm[tri[1]], dnorm[tri[2]])
        centroid = (a + b + c) / 3.0
        ci = min(
            range(len(CATEGORY_NAMES)),
            key=lambda idx: standardized_region_distance(centroid, target_means[idx], target_stds[idx]),
        )
        n = (b - a).cross(c - a)
        if n.length_squared <= 1e-20:
            bucket = 0
        else:
            bucket = normal_bucket(n.normalized())
        triangle_categories[ti] = ci
        triangle_buckets[ti] = bucket
        by_category[ci].append(ti)
        by_category_bucket[ci][bucket].append(ti)
        counts[ci] += 1
        for axis in range(3):
            value = float(centroid[axis])
            sums[ci][axis] += value
            sums2[ci][axis] += value * value

    missing = [CATEGORY_NAMES[i] for i, c in enumerate(counts) if c < 12]
    if missing:
        raise RuntimeError(f"Donor semantic classification too sparse: {missing}; counts={counts}")
    donor_means, donor_stds = moments_to_stats(counts, sums, sums2)
    return triangle_categories, triangle_buckets, by_category, by_category_bucket, counts, donor_means, donor_stds


def make_subset_bvh(dnorm: list[Vector], triangles: list[tuple[int, int, int]], indices: list[int]):
    if not indices:
        return None
    polygons = [triangles[i] for i in indices]
    return BVHTree.FromPolygons(dnorm, polygons, all_triangles=True), indices


def warp_query_point(
    p: Vector,
    ci: int,
    target_means: list[Vector],
    target_stds: list[Vector],
    donor_means: list[Vector],
    donor_stds: list[Vector],
) -> Vector:
    out = Vector((0.0, 0.0, 0.0))
    for a in range(3):
        scale = donor_stds[ci][a] / max(0.075, target_stds[ci][a])
        scale = min(1.65, max(0.60, scale))
        out[a] = donor_means[ci][a] + (p[a] - target_means[ci][a]) * scale
    return out


def transfer_uvs(target: bpy.types.Object, donor: bpy.types.Object) -> dict[str, object]:
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
    textent = tmx - tmn
    dnorm = [base.normalize_point(v.co, dmn, dmx) for v in dmesh.vertices]
    triangles = [tuple(int(v) for v in tri.vertices) for tri in loop_tris]
    triangle_uvs: list[tuple[Vector, Vector, Vector]] = []
    for tri in loop_tris:
        lis = tuple(int(i) for i in tri.loops)
        triangle_uvs.append(tuple(Vector((donor_uv.data[i].uv.x, donor_uv.data[i].uv.y)) for i in lis))

    target_categories, target_counts, target_means, target_stds = build_target_semantics(target, tmn, tmx)
    (
        _triangle_categories,
        _triangle_buckets,
        donor_by_category,
        donor_by_category_bucket,
        donor_counts,
        donor_means,
        donor_stds,
    ) = classify_donor_triangles(triangles, dnorm, target_means, target_stds)

    category_bvhs = [make_subset_bvh(dnorm, triangles, donor_by_category[ci]) for ci in range(len(CATEGORY_NAMES))]
    bucket_bvhs = [
        [make_subset_bvh(dnorm, triangles, donor_by_category_bucket[ci][bi]) for bi in range(len(NORMAL_NAMES))]
        for ci in range(len(CATEGORY_NAMES))
    ]

    nearest_tri = array('i')
    nearest_distance = array('f')
    nearest_bucket = bytearray(len(tmesh.vertices))
    distance_sum = 0.0
    max_distance = 0.0
    misses = 0
    normal_bucket_fallbacks = 0
    category_query_counts = [0] * len(CATEGORY_NAMES)
    bucket_query_counts = [[0] * len(NORMAL_NAMES) for _ in CATEGORY_NAMES]

    for vert in tmesh.vertices:
        ci = int(target_categories[vert.index])
        p = base.normalize_point(vert.co, tmn, tmx)
        query = warp_query_point(p, ci, target_means, target_stds, donor_means, donor_stds)
        tn = normalized_normal(vert.normal, textent)
        bi = normal_bucket(tn)
        nearest_bucket[vert.index] = bi

        subset = bucket_bvhs[ci][bi]
        # Very small directional subsets are fragile; use the semantic region BVH but
        # never the unrestricted whole-body BVH.
        if subset is None or len(subset[1]) < 16:
            subset = category_bvhs[ci]
            normal_bucket_fallbacks += 1
        if subset is None:
            nearest_tri.append(-1)
            nearest_distance.append(float('inf'))
            misses += 1
            continue
        bvh, original_indices = subset
        nearest = bvh.find_nearest(query)
        if nearest is None:
            nearest_tri.append(-1)
            nearest_distance.append(float('inf'))
            misses += 1
            continue
        _loc, _normal, subset_face_index, distance = nearest
        if subset_face_index < 0 or subset_face_index >= len(original_indices):
            nearest_tri.append(-1)
            nearest_distance.append(float('inf'))
            misses += 1
            continue
        original_tri = int(original_indices[int(subset_face_index)])
        nearest_tri.append(original_tri)
        nearest_distance.append(float(distance))
        distance_sum += float(distance)
        max_distance = max(max_distance, float(distance))
        category_query_counts[ci] += 1
        bucket_query_counts[ci][bi] += 1

    if misses:
        raise RuntimeError(f"Semantic UV transfer missed {misses} target vertices")

    uv = tmesh.uv_layers.get("SemanticPBR_UV")
    if uv is None:
        uv = tmesh.uv_layers.new(name="SemanticPBR_UV")
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
    split_target_vertices = bytearray(len(tmesh.vertices))
    loops_written = 0
    fallback_polygons = 0

    for poly in tmesh.polygons:
        vertex_ids = tuple(int(v) for v in poly.vertices)
        # Keep a polygon within one target semantic category; this removes rare border
        # contamination when a triangle straddles two skin-weight regions.
        cat_votes: dict[int, int] = {}
        for vi in vertex_ids:
            c = int(target_categories[vi])
            cat_votes[c] = cat_votes.get(c, 0) + 1
        poly_cat = max(cat_votes, key=cat_votes.get)
        eligible = [vi for vi in vertex_ids if int(target_categories[vi]) == poly_cat]
        chosen_vertex = min(eligible or list(vertex_ids), key=lambda vi: nearest_distance[vi])
        tri_index = int(nearest_tri[chosen_vertex])
        if tri_index < 0 or tri_index >= len(triangles):
            fallback_polygons += 1
            continue
        tri = triangles[tri_index]
        tri_uv = triangle_uvs[tri_index]
        a, b, c = dnorm[tri[0]], dnorm[tri[1]], dnorm[tri[2]]

        for loop_index in poly.loop_indices:
            vi = int(tmesh.loops[loop_index].vertex_index)
            ci = int(target_categories[vi])
            p = base.normalize_point(tmesh.vertices[vi].co, tmn, tmx)
            query = warp_query_point(p, ci, target_means, target_stds, donor_means, donor_stds)
            q = closest_point_on_triangle(query, a, b, c)
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
                split_target_vertices[vi] = 1

    if fallback_polygons:
        raise RuntimeError(f"Semantic UV transfer could not map {fallback_polygons} target polygons")
    if loops_written != len(tmesh.loops):
        raise RuntimeError(f"UV loop write mismatch: {loops_written} != {len(tmesh.loops)}")

    qc = {
        "algorithm": "skin-semantic + part-local-normalization + surface-normal-bucket + loop-corner UV",
        "targetVertices": len(tmesh.vertices),
        "targetPolygons": len(tmesh.polygons),
        "targetLoops": len(tmesh.loops),
        "loopsWritten": loops_written,
        "donorVertices": len(dmesh.vertices),
        "donorTriangles": len(triangles),
        "targetVerticesByCategory": {CATEGORY_NAMES[i]: int(target_counts[i]) for i in range(len(CATEGORY_NAMES))},
        "donorTrianglesByCategory": {CATEGORY_NAMES[i]: int(donor_counts[i]) for i in range(len(CATEGORY_NAMES))},
        "donorTrianglesByCategoryNormal": {
            CATEGORY_NAMES[ci]: {NORMAL_NAMES[bi]: len(donor_by_category_bucket[ci][bi]) for bi in range(len(NORMAL_NAMES))}
            for ci in range(len(CATEGORY_NAMES))
        },
        "categoryQueries": {CATEGORY_NAMES[i]: int(category_query_counts[i]) for i in range(len(CATEGORY_NAMES))},
        "normalBucketFallbackVertices": int(normal_bucket_fallbacks),
        "targetVerticesWithPerLoopUvSplits": int(sum(split_target_vertices)),
        "meanSemanticSurfaceDistance": distance_sum / max(1, len(tmesh.vertices)),
        "maxSemanticSurfaceDistance": max_distance,
        "targetCategoryMeans": {CATEGORY_NAMES[i]: [float(v) for v in target_means[i]] for i in range(len(CATEGORY_NAMES))},
        "donorCategoryMeans": {CATEGORY_NAMES[i]: [float(v) for v in donor_means[i]] for i in range(len(CATEGORY_NAMES))},
        "partLocalScale": {
            CATEGORY_NAMES[i]: [
                float(min(1.65, max(0.60, donor_stds[i][a] / max(0.075, target_stds[i][a])))) for a in range(3)
            ]
            for i in range(len(CATEGORY_NAMES))
        },
    }
    print("SEMANTIC_NORMAL_UV_QC=" + json.dumps(qc, indent=2), flush=True)
    return qc


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
