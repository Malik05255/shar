#!/usr/bin/env python3
"""Transfer Hunyuan PBR material/UV appearance onto the exact animated runtime hero.

The donor geometry is used only as a spatial UV/material reference. The output retains the target
mesh, armature, skin weights and animation actions. No decimation, remesh or topology replacement is
performed. This remains a visual candidate until rendered views and physical-device playback pass.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector
from mathutils.bvhtree import BVHTree


def args_after_dash() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=Path, required=True)
    ap.add_argument("--donor", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--qc", type=Path, required=True)
    ap.add_argument("--preview-dir", type=Path, required=True)
    return ap.parse_args(argv)


def import_glb(path: Path) -> list[bpy.types.Object]:
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    return [obj for obj in bpy.data.objects if obj not in before]


def mesh_objects(objects: list[bpy.types.Object]) -> list[bpy.types.Object]:
    return [o for o in objects if o.type == "MESH"]


def armature_objects(objects: list[bpy.types.Object]) -> list[bpy.types.Object]:
    return [o for o in objects if o.type == "ARMATURE"]


def local_bounds(obj: bpy.types.Object) -> tuple[Vector, Vector]:
    verts = obj.data.vertices
    if not verts:
        raise RuntimeError(f"Mesh {obj.name} has no vertices")
    mn = Vector((math.inf, math.inf, math.inf))
    mx = Vector((-math.inf, -math.inf, -math.inf))
    for v in verts:
        c = v.co
        mn.x = min(mn.x, c.x); mn.y = min(mn.y, c.y); mn.z = min(mn.z, c.z)
        mx.x = max(mx.x, c.x); mx.y = max(mx.y, c.y); mx.z = max(mx.z, c.z)
    return mn, mx


def normalize_point(p: Vector, mn: Vector, mx: Vector) -> Vector:
    ext = mx - mn
    return Vector((
        (p.x - mn.x) / max(ext.x, 1e-8),
        (p.y - mn.y) / max(ext.y, 1e-8),
        (p.z - mn.z) / max(ext.z, 1e-8),
    ))


def donor_vertex_uvs(donor: bpy.types.Object) -> tuple[bpy.types.MeshUVLoopLayer, list[Vector]]:
    mesh = donor.data
    uv_layer = mesh.uv_layers.active
    if uv_layer is None:
        raise RuntimeError("Textured donor has no active UV map")
    sums = [Vector((0.0, 0.0)) for _ in mesh.vertices]
    counts = [0 for _ in mesh.vertices]
    for loop in mesh.loops:
        uv = uv_layer.data[loop.index].uv
        sums[loop.vertex_index] += Vector((uv.x, uv.y))
        counts[loop.vertex_index] += 1
    averaged = [
        sums[i] / counts[i] if counts[i] else Vector((0.0, 0.0))
        for i in range(len(mesh.vertices))
    ]
    return uv_layer, averaged


def barycentric(p: Vector, a: Vector, b: Vector, c: Vector) -> tuple[float, float, float]:
    v0, v1, v2 = b - a, c - a, p - a
    d00, d01, d11 = v0.dot(v0), v0.dot(v1), v1.dot(v1)
    d20, d21 = v2.dot(v0), v2.dot(v1)
    denom = d00 * d11 - d01 * d01
    if abs(denom) < 1e-12:
        return 1.0, 0.0, 0.0
    v = (d11 * d20 - d01 * d21) / denom
    w = (d00 * d21 - d01 * d20) / denom
    u = 1.0 - v - w
    return u, v, w


def transfer_uvs(target: bpy.types.Object, donor: bpy.types.Object) -> dict[str, float | int]:
    tmesh = target.data
    dmesh = donor.data
    if not dmesh.materials:
        raise RuntimeError("Donor mesh has no material slots")
    _, duvs = donor_vertex_uvs(donor)

    tmn, tmx = local_bounds(target)
    dmn, dmx = local_bounds(donor)
    dnorm = [normalize_point(v.co, dmn, dmx) for v in dmesh.vertices]
    polygons = [tuple(p.vertices) for p in dmesh.polygons if len(p.vertices) >= 3]
    # GLB primitives are triangles; triangulate any rare n-gon fan conservatively for BVH only.
    triangles: list[tuple[int, int, int]] = []
    poly_map: list[tuple[int, int, int]] = []
    for poly in polygons:
        for i in range(1, len(poly) - 1):
            tri = (poly[0], poly[i], poly[i + 1])
            triangles.append(tri)
            poly_map.append(tri)
    if not triangles:
        raise RuntimeError("Donor mesh has no triangles")
    bvh = BVHTree.FromPolygons(dnorm, triangles, all_triangles=True)

    uv = tmesh.uv_layers.active or tmesh.uv_layers.new(name="HunyuanPBR_UV")
    per_vertex_uv: list[Vector] = [Vector((0.0, 0.0)) for _ in tmesh.vertices]
    distances: list[float] = []
    misses = 0
    for i, vert in enumerate(tmesh.vertices):
        p = normalize_point(vert.co, tmn, tmx)
        nearest = bvh.find_nearest(p)
        if nearest is None:
            misses += 1
            continue
        loc, _normal, face_index, distance = nearest
        tri = poly_map[face_index]
        a, b, c = dnorm[tri[0]], dnorm[tri[1]], dnorm[tri[2]]
        u, v, w = barycentric(loc, a, b, c)
        # Clamp tiny numerical overshoot without destroying continuity.
        u = max(-0.05, min(1.05, u)); v = max(-0.05, min(1.05, v)); w = max(-0.05, min(1.05, w))
        total = u + v + w
        if abs(total) > 1e-8:
            u, v, w = u / total, v / total, w / total
        per_vertex_uv[i] = duvs[tri[0]] * u + duvs[tri[1]] * v + duvs[tri[2]] * w
        distances.append(float(distance))

    for loop in tmesh.loops:
        uv.data[loop.index].uv = per_vertex_uv[loop.vertex_index]

    if misses:
        raise RuntimeError(f"UV transfer missed {misses} target vertices")
    return {
        "targetVertices": len(tmesh.vertices),
        "donorVertices": len(dmesh.vertices),
        "donorTriangles": len(triangles),
        "meanNormalizedSurfaceDistance": sum(distances) / max(1, len(distances)),
        "maxNormalizedSurfaceDistance": max(distances, default=0.0),
    }


def copy_materials(target: bpy.types.Object, donor: bpy.types.Object) -> int:
    target.data.materials.clear()
    for material in donor.data.materials:
        if material is not None:
            target.data.materials.append(material.copy())
    if not target.data.materials:
        raise RuntimeError("No donor materials copied")
    for poly in target.data.polygons:
        poly.material_index = min(poly.material_index, len(target.data.materials) - 1)
    return len(target.data.materials)


def ensure_images_packed(materials: list[bpy.types.Material]) -> tuple[int, int]:
    images = set()
    texture_nodes = 0
    for mat in materials:
        if not mat or not mat.use_nodes:
            continue
        for node in mat.node_tree.nodes:
            if node.type == "TEX_IMAGE" and getattr(node, "image", None):
                texture_nodes += 1
                images.add(node.image)
                try:
                    node.image.pack()
                except Exception:
                    pass
    return texture_nodes, len(images)


def setup_preview(target_mesh: bpy.types.Object, preview_dir: Path) -> list[str]:
    preview_dir.mkdir(parents=True, exist_ok=True)
    # Hide armature in render; mesh remains deformed at rest pose.
    for obj in bpy.data.objects:
        obj.hide_render = obj != target_mesh and obj.type not in {"LIGHT", "CAMERA"}

    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE_NEXT"
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.world.color = (0.025, 0.025, 0.025)

    mn, mx = local_bounds(target_mesh)
    center = (mn + mx) * 0.5
    ext = mx - mn
    radius = max(ext.x, ext.y, ext.z) * 1.35

    cam_data = bpy.data.cameras.new("QC_CAMERA")
    cam = bpy.data.objects.new("QC_CAMERA", cam_data)
    bpy.context.collection.objects.link(cam)
    scene.camera = cam
    cam.data.lens = 62

    key_data = bpy.data.lights.new("QC_KEY", type="AREA")
    key_data.energy = 1200
    key_data.shape = "DISK"
    key_data.size = radius * 0.8
    key = bpy.data.objects.new("QC_KEY", key_data)
    bpy.context.collection.objects.link(key)

    fill_data = bpy.data.lights.new("QC_FILL", type="AREA")
    fill_data.energy = 700
    fill_data.size = radius * 0.7
    fill = bpy.data.objects.new("QC_FILL", fill_data)
    bpy.context.collection.objects.link(fill)

    def point(obj: bpy.types.Object, target: Vector) -> None:
        obj.rotation_euler = (target - obj.location).to_track_quat('-Z', 'Y').to_euler()

    key.location = center + Vector((radius * 1.4, -radius * 1.2, radius * 1.4))
    fill.location = center + Vector((-radius * 1.0, -radius * 0.7, radius * 0.6))
    point(key, center); point(fill, center)

    views = [
        ("front", Vector((0.0, -radius * 2.4, radius * 0.12))),
        ("three_quarter", Vector((radius * 1.4, -radius * 2.0, radius * 0.18))),
        ("side", Vector((radius * 2.3, 0.0, radius * 0.15))),
    ]
    outputs: list[str] = []
    for name, offset in views:
        cam.location = center + offset
        point(cam, center)
        path = preview_dir / f"police_dog.pbr.{name}.png"
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        outputs.append(path.name)
    return outputs


def main() -> int:
    args = args_after_dash()
    if not args.target.is_file() or not args.donor.is_file():
        raise SystemExit("Target or donor GLB missing")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    target_objects = import_glb(args.target)
    target_meshes = mesh_objects(target_objects)
    target_arms = armature_objects(target_objects)
    if len(target_meshes) != 1 or not target_arms:
        raise RuntimeError(f"Unexpected target structure: meshes={len(target_meshes)} armatures={len(target_arms)}")
    target_mesh = target_meshes[0]
    action_names_before = sorted({a.name for a in bpy.data.actions})
    target_vertex_count = len(target_mesh.data.vertices)
    target_poly_count = len(target_mesh.data.polygons)

    donor_objects = import_glb(args.donor)
    donor_meshes = mesh_objects(donor_objects)
    if not donor_meshes:
        raise RuntimeError("Donor import contains no mesh")
    donor_mesh = max(donor_meshes, key=lambda o: len(o.data.vertices))

    uv_qc = transfer_uvs(target_mesh, donor_mesh)
    material_count = copy_materials(target_mesh, donor_mesh)
    texture_nodes, image_count = ensure_images_packed(list(target_mesh.data.materials))
    if texture_nodes < 1 or image_count < 1:
        raise RuntimeError("Transferred material has no image texture nodes")

    # Donor is now discarded. It must never leak into runtime geometry.
    for obj in donor_objects:
        bpy.data.objects.remove(obj, do_unlink=True)

    if len(target_mesh.data.vertices) != target_vertex_count or len(target_mesh.data.polygons) != target_poly_count:
        raise RuntimeError("Target topology changed during PBR transfer")

    preview_files = setup_preview(target_mesh, args.preview_dir)

    # Remove QC render helpers before runtime export.
    for name in ("QC_CAMERA", "QC_KEY", "QC_FILL"):
        obj = bpy.data.objects.get(name)
        if obj:
            bpy.data.objects.remove(obj, do_unlink=True)

    for obj in bpy.context.selected_objects:
        obj.select_set(False)
    for obj in target_objects:
        if obj.name in bpy.data.objects:
            obj.select_set(True)
    bpy.context.view_layer.objects.active = target_mesh

    args.output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(args.output),
        export_format="GLB",
        use_selection=True,
        export_yup=True,
        export_apply=False,
        export_animations=True,
        export_nla_strips=True,
        export_materials="EXPORT",
        export_image_format="AUTO",
        export_texcoords=True,
        export_normals=True,
        export_skins=True,
        export_all_influences=False,
    )
    if not args.output.is_file() or args.output.stat().st_size < 1_000_000:
        raise RuntimeError("PBR output GLB missing or unexpectedly small")

    action_names_after = sorted({a.name for a in bpy.data.actions})
    qc = {
        "sourceMotionHero": args.target.name,
        "sourcePbrDonor": args.donor.name,
        "productionReady": False,
        "purpose": "PBR-transfer-and-render-review-candidate",
        "freeOnly": True,
        "paidFallbackAllowed": False,
        "geometryPreserved": True,
        "decimationApplied": False,
        "remeshApplied": False,
        "targetVerticesBeforeAfter": [target_vertex_count, len(target_mesh.data.vertices)],
        "targetPolygonsBeforeAfter": [target_poly_count, len(target_mesh.data.polygons)],
        "armatureCount": len(target_arms),
        "materials": material_count,
        "textureImageNodes": texture_nodes,
        "images": image_count,
        "uvTransfer": uv_qc,
        "actionsBefore": action_names_before,
        "actionsAfter": action_names_after,
        "previewFiles": preview_files,
        "knownGaps": [
            "PBR appearance must pass rendered visual identity review",
            "fur is represented by PBR surface appearance; strand/groom geometry is not yet authored",
            "true eyelid, eyeball and muzzle/cheek/tongue facial deformation remain open",
            "physical Android frame pacing and deformation acceptance remain open"
        ],
        "productionGate": "CLOSED"
    }
    args.qc.write_text(json.dumps(qc, indent=2), encoding="utf-8")
    print(json.dumps(qc, indent=2))
    print("PBR_TRANSFER_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
