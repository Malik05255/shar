#!/usr/bin/env python3
"""Apply deterministic reference-derived PBR maps to the exact animated hero.

This is a zero-cost visual candidate path used only when legitimate free cloud texture providers
are unavailable. It preserves the accepted target geometry, armature, skin weights and animations.
The front projection is authoritative only for visible front-facing identity; side/back surfaces and
true groom/facial deformation remain production blockers.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=Path, required=True)
    ap.add_argument("--basecolor", type=Path, required=True)
    ap.add_argument("--roughness", type=Path, required=True)
    ap.add_argument("--normal", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--qc", type=Path, required=True)
    ap.add_argument("--preview-dir", type=Path, required=True)
    return ap.parse_args(argv)


def import_glb(path: Path) -> list[bpy.types.Object]:
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    return [obj for obj in bpy.data.objects if obj not in before]


def bounds_local(mesh_obj: bpy.types.Object) -> tuple[Vector, Vector]:
    verts = mesh_obj.data.vertices
    if not verts:
        raise RuntimeError("Target mesh has no vertices")
    mn = Vector((math.inf, math.inf, math.inf))
    mx = Vector((-math.inf, -math.inf, -math.inf))
    for v in verts:
        c = v.co
        mn.x = min(mn.x, c.x); mn.y = min(mn.y, c.y); mn.z = min(mn.z, c.z)
        mx.x = max(mx.x, c.x); mx.y = max(mx.y, c.y); mx.z = max(mx.z, c.z)
    return mn, mx


def apply_front_planar_uv(mesh_obj: bpy.types.Object) -> dict[str, object]:
    """Project +X horizontally and +Z vertically; camera/front is along -Y."""
    mesh = mesh_obj.data
    mn, mx = bounds_local(mesh_obj)
    dx = max(mx.x - mn.x, 1e-8)
    dz = max(mx.z - mn.z, 1e-8)

    uv_layer = mesh.uv_layers.get("ReferenceFrontUV") or mesh.uv_layers.new(name="ReferenceFrontUV")
    mesh.uv_layers.active = uv_layer
    per_vertex: list[tuple[float, float]] = []
    for v in mesh.vertices:
        u = (v.co.x - mn.x) / dx
        w = (v.co.z - mn.z) / dz
        per_vertex.append((float(u), float(w)))
    for loop in mesh.loops:
        u, v = per_vertex[loop.vertex_index]
        uv_layer.data[loop.index].uv = (u, v)

    return {
        "projection": "front-planar",
        "frontAxis": "-Y",
        "horizontalAxis": "+X",
        "verticalAxis": "+Z",
        "uvLayer": uv_layer.name,
        "boundsMin": [float(mn.x), float(mn.y), float(mn.z)],
        "boundsMax": [float(mx.x), float(mx.y), float(mx.z)],
    }


def load_image(path: Path, colorspace: str) -> bpy.types.Image:
    image = bpy.data.images.load(str(path), check_existing=False)
    try:
        image.colorspace_settings.name = colorspace
    except Exception:
        pass
    try:
        image.pack()
    except Exception:
        pass
    return image


def make_material(base_path: Path, rough_path: Path, normal_path: Path) -> tuple[bpy.types.Material, dict[str, object]]:
    mat = bpy.data.materials.new("PoliceDogReferencePBR")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    nodes.clear()

    out = nodes.new("ShaderNodeOutputMaterial")
    out.location = (680, 0)
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.location = (360, 0)
    links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])

    # Stable across Blender 3.x/4.x.
    if "Metallic" in bsdf.inputs:
        bsdf.inputs["Metallic"].default_value = 0.0

    texcoord = nodes.new("ShaderNodeTexCoord")
    texcoord.location = (-900, 0)
    mapping = nodes.new("ShaderNodeMapping")
    mapping.location = (-700, 0)
    links.new(texcoord.outputs["UV"], mapping.inputs["Vector"])

    base_img = load_image(base_path, "sRGB")
    rough_img = load_image(rough_path, "Non-Color")
    normal_img = load_image(normal_path, "Non-Color")

    base = nodes.new("ShaderNodeTexImage")
    base.name = "ReferenceBaseColor"
    base.label = "Reference Base Color"
    base.image = base_img
    base.interpolation = "Linear"
    base.extension = "EXTEND"
    base.location = (-430, 230)
    links.new(mapping.outputs["Vector"], base.inputs["Vector"])
    links.new(base.outputs["Color"], bsdf.inputs["Base Color"])

    rough = nodes.new("ShaderNodeTexImage")
    rough.name = "ReferenceRoughness"
    rough.label = "Reference-derived Roughness"
    rough.image = rough_img
    rough.interpolation = "Linear"
    rough.extension = "EXTEND"
    rough.location = (-430, -40)
    links.new(mapping.outputs["Vector"], rough.inputs["Vector"])
    links.new(rough.outputs["Color"], bsdf.inputs["Roughness"])

    normal_tex = nodes.new("ShaderNodeTexImage")
    normal_tex.name = "ReferenceNormal"
    normal_tex.label = "Reference-derived Micro Normal"
    normal_tex.image = normal_img
    normal_tex.interpolation = "Linear"
    normal_tex.extension = "EXTEND"
    normal_tex.location = (-430, -310)
    links.new(mapping.outputs["Vector"], normal_tex.inputs["Vector"])

    normal_map = nodes.new("ShaderNodeNormalMap")
    normal_map.location = (80, -260)
    normal_map.inputs["Strength"].default_value = 0.32
    links.new(normal_tex.outputs["Color"], normal_map.inputs["Color"])
    links.new(normal_map.outputs["Normal"], bsdf.inputs["Normal"])

    return mat, {
        "material": mat.name,
        "baseColorImage": base_img.name,
        "roughnessImage": rough_img.name,
        "normalImage": normal_img.name,
        "normalStrength": 0.32,
        "metallic": 0.0,
        "textureNodes": 3,
    }


def assign_material(mesh_obj: bpy.types.Object, mat: bpy.types.Material) -> None:
    mesh_obj.data.materials.clear()
    mesh_obj.data.materials.append(mat)
    for poly in mesh_obj.data.polygons:
        poly.material_index = 0


def set_eevee(scene: bpy.types.Scene) -> str:
    # Ubuntu-hosted runners may provide either Blender 3.x (EEVEE) or 4.x (EEVEE Next).
    for engine in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        try:
            scene.render.engine = engine
            return engine
        except Exception:
            continue
    raise RuntimeError("No Eevee render engine is available")


def point_at(obj: bpy.types.Object, target: Vector) -> None:
    obj.rotation_euler = (target - obj.location).to_track_quat("-Z", "Y").to_euler()


def render_previews(mesh_obj: bpy.types.Object, preview_dir: Path) -> tuple[list[str], str]:
    preview_dir.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    engine = set_eevee(scene)
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world.color = (0.025, 0.025, 0.03)
    scene.frame_set(0)

    # Hide any imported non-target renderable mesh, but keep armature evaluation alive.
    for obj in bpy.data.objects:
        if obj.type == "MESH":
            obj.hide_render = obj != mesh_obj

    mn, mx = bounds_local(mesh_obj)
    center = (mn + mx) * 0.5
    ext = mx - mn
    radius = max(ext.x, ext.y, ext.z) * 1.35

    cam_data = bpy.data.cameras.new("REFERENCE_QC_CAMERA")
    cam = bpy.data.objects.new("REFERENCE_QC_CAMERA", cam_data)
    bpy.context.collection.objects.link(cam)
    cam.data.lens = 62
    scene.camera = cam

    key_data = bpy.data.lights.new("REFERENCE_QC_KEY", type="AREA")
    key_data.energy = 1050
    key_data.shape = "DISK"
    key_data.size = radius * 0.85
    key = bpy.data.objects.new("REFERENCE_QC_KEY", key_data)
    bpy.context.collection.objects.link(key)
    key.location = center + Vector((radius * 1.35, -radius * 1.25, radius * 1.45))
    point_at(key, center)

    fill_data = bpy.data.lights.new("REFERENCE_QC_FILL", type="AREA")
    fill_data.energy = 520
    fill_data.size = radius * 0.8
    fill = bpy.data.objects.new("REFERENCE_QC_FILL", fill_data)
    bpy.context.collection.objects.link(fill)
    fill.location = center + Vector((-radius * 1.15, -radius * 0.8, radius * 0.65))
    point_at(fill, center)

    rim_data = bpy.data.lights.new("REFERENCE_QC_RIM", type="AREA")
    rim_data.energy = 620
    rim_data.size = radius * 0.6
    rim = bpy.data.objects.new("REFERENCE_QC_RIM", rim_data)
    bpy.context.collection.objects.link(rim)
    rim.location = center + Vector((radius * 0.2, radius * 1.5, radius * 1.1))
    point_at(rim, center)

    views = [
        ("front", Vector((0.0, -radius * 2.5, radius * 0.12))),
        ("three_quarter", Vector((radius * 1.35, -radius * 2.1, radius * 0.18))),
        ("side", Vector((radius * 2.45, -radius * 0.10, radius * 0.15))),
    ]
    outputs: list[str] = []
    for name, offset in views:
        cam.location = center + offset
        point_at(cam, center)
        path = preview_dir / f"police_dog.reference_pbr.{name}.png"
        scene.render.filepath = str(path)
        bpy.ops.render.render(write_still=True)
        outputs.append(path.name)

    for name in ("REFERENCE_QC_CAMERA", "REFERENCE_QC_KEY", "REFERENCE_QC_FILL", "REFERENCE_QC_RIM"):
        obj = bpy.data.objects.get(name)
        if obj is not None:
            bpy.data.objects.remove(obj, do_unlink=True)
    return outputs, engine


def animation_names() -> list[str]:
    return sorted({a.name for a in bpy.data.actions if a.name})


def main() -> int:
    args = parse_args()
    for path in (args.target, args.basecolor, args.roughness, args.normal):
        if not path.is_file() or path.stat().st_size <= 0:
            raise SystemExit(f"Required input missing: {path}")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    imported = import_glb(args.target)
    meshes = [o for o in imported if o.type == "MESH"]
    armatures = [o for o in imported if o.type == "ARMATURE"]
    if len(meshes) != 1:
        raise RuntimeError(f"Expected exactly one target mesh, got {len(meshes)}")
    if not armatures:
        raise RuntimeError("Target contains no armature")
    mesh = meshes[0]

    vertex_count = len(mesh.data.vertices)
    polygon_count = len(mesh.data.polygons)
    actions_before = animation_names()
    uv_meta = apply_front_planar_uv(mesh)
    mat, material_meta = make_material(args.basecolor, args.roughness, args.normal)
    assign_material(mesh, mat)

    if len(mesh.data.vertices) != vertex_count or len(mesh.data.polygons) != polygon_count:
        raise RuntimeError("Topology changed while applying local PBR candidate")

    preview_files, render_engine = render_previews(mesh, args.preview_dir)

    # Select only the original imported target objects. Do not export QC lights/camera.
    bpy.ops.object.select_all(action="DESELECT")
    live_imported: list[bpy.types.Object] = []
    for obj in imported:
        if bpy.data.objects.get(obj.name) is not None:
            obj.select_set(True)
            live_imported.append(obj)
    bpy.context.view_layer.objects.active = mesh

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
        raise RuntimeError("Reference-PBR motion GLB missing or unexpectedly small")

    actions_after = animation_names()
    qc = {
        "sourceMotionHero": args.target.name,
        "productionReady": False,
        "purpose": "zero-cost reference-projected PBR motion candidate",
        "freeOnly": True,
        "cloudProviderUsed": False,
        "paidFallbackAllowed": False,
        "geometryPreserved": True,
        "decimationApplied": False,
        "remeshApplied": False,
        "targetVerticesBeforeAfter": [vertex_count, len(mesh.data.vertices)],
        "targetPolygonsBeforeAfter": [polygon_count, len(mesh.data.polygons)],
        "armatureCount": len(armatures),
        "actionsBefore": actions_before,
        "actionsAfter": actions_after,
        "uv": uv_meta,
        "material": material_meta,
        "previewFiles": preview_files,
        "renderEngine": render_engine,
        "knownGaps": [
            "single-view front planar projection is not authoritative for occluded side/back surfaces",
            "reference-derived normal map is a micro-detail approximation, not a geometry-aware generated normal",
            "strand/groom fur is not authored yet",
            "true eyelid, independent eyeball, muzzle/cheek and tongue deformation remain open",
            "physical Android frame pacing and deformation acceptance remain open"
        ],
        "productionGate": "CLOSED"
    }
    args.qc.parent.mkdir(parents=True, exist_ok=True)
    args.qc.write_text(json.dumps(qc, indent=2), encoding="utf-8")
    print(json.dumps(qc, indent=2))
    print("REFERENCE_PBR_APPLICATION_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
