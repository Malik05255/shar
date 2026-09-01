#!/usr/bin/env python3
"""Apply deterministic reference-derived PBR maps to the exact animated hero.

The visual pass preserves imported topology, armature, skin weights and all animation actions.
Low-vertex helper/gizmo meshes are excluded from UV bounds and QC rendering so they cannot distort
the hero projection, but they are not deleted or remeshed.
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
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
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


def choose_surface_meshes(meshes: list[bpy.types.Object]) -> tuple[list[bpy.types.Object], list[bpy.types.Object]]:
    """Exclude tiny helper/gizmo meshes from visual projection without altering them."""
    if not meshes:
        raise RuntimeError("Target contains no mesh objects")
    max_vertices = max(len(obj.data.vertices) for obj in meshes)
    threshold = max(128, int(max_vertices * 0.0001))
    surface = [obj for obj in meshes if len(obj.data.vertices) >= threshold]
    helpers = [obj for obj in meshes if obj not in surface]
    if not surface:
        surface = [max(meshes, key=lambda obj: len(obj.data.vertices))]
        helpers = [obj for obj in meshes if obj not in surface]
    return surface, helpers


def world_bounds(meshes: list[bpy.types.Object]) -> tuple[Vector, Vector]:
    mn = Vector((math.inf, math.inf, math.inf))
    mx = Vector((-math.inf, -math.inf, -math.inf))
    count = 0
    for obj in meshes:
        matrix = obj.matrix_world
        for vert in obj.data.vertices:
            p = matrix @ vert.co
            mn.x, mn.y, mn.z = min(mn.x, p.x), min(mn.y, p.y), min(mn.z, p.z)
            mx.x, mx.y, mx.z = max(mx.x, p.x), max(mx.y, p.y), max(mx.z, p.z)
            count += 1
    if count == 0:
        raise RuntimeError("Surface meshes contain no vertices")
    return mn, mx


def apply_global_front_planar_uv(
    meshes: list[bpy.types.Object], mn: Vector, mx: Vector
) -> dict[str, object]:
    """Project world +X horizontally and +Z vertically; front view looks along -Y."""
    dx = max(mx.x - mn.x, 1e-8)
    dz = max(mx.z - mn.z, 1e-8)
    layers: dict[str, str] = {}
    for obj in meshes:
        mesh = obj.data
        uv_layer = mesh.uv_layers.get("ReferenceFrontUV") or mesh.uv_layers.new(name="ReferenceFrontUV")
        mesh.uv_layers.active = uv_layer
        try:
            uv_layer.active_render = True
        except Exception:
            pass
        matrix = obj.matrix_world
        per_vertex: list[tuple[float, float]] = []
        for vert in mesh.vertices:
            p = matrix @ vert.co
            per_vertex.append((float((p.x - mn.x) / dx), float((p.z - mn.z) / dz)))
        for loop in mesh.loops:
            uv_layer.data[loop.index].uv = per_vertex[loop.vertex_index]
        layers[obj.name] = uv_layer.name
    return {
        "projection": "dominant-surface-global-front-planar",
        "frontAxis": "-Y",
        "horizontalAxis": "+X",
        "verticalAxis": "+Z",
        "meshUvLayers": layers,
        "worldBoundsMin": [float(mn.x), float(mn.y), float(mn.z)],
        "worldBoundsMax": [float(mx.x), float(mx.y), float(mx.z)],
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


def make_material(
    base_path: Path, rough_path: Path, normal_path: Path
) -> tuple[bpy.types.Material, dict[str, object]]:
    mat = bpy.data.materials.new("PoliceDogReferencePBR")
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    nodes.clear()

    out = nodes.new("ShaderNodeOutputMaterial")
    out.location = (720, 0)
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.location = (390, 0)
    links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    if "Metallic" in bsdf.inputs:
        bsdf.inputs["Metallic"].default_value = 0.0

    uv = nodes.new("ShaderNodeUVMap")
    uv.uv_map = "ReferenceFrontUV"
    uv.location = (-900, 0)

    base_img = load_image(base_path, "sRGB")
    rough_img = load_image(rough_path, "Non-Color")
    normal_img = load_image(normal_path, "Non-Color")

    base = nodes.new("ShaderNodeTexImage")
    base.name, base.label, base.image = "ReferenceBaseColor", "Reference Base Color", base_img
    base.interpolation, base.extension, base.location = "Linear", "EXTEND", (-500, 250)
    links.new(uv.outputs["UV"], base.inputs["Vector"])
    links.new(base.outputs["Color"], bsdf.inputs["Base Color"])

    rough = nodes.new("ShaderNodeTexImage")
    rough.name, rough.label, rough.image = "ReferenceRoughness", "Reference Roughness", rough_img
    rough.interpolation, rough.extension, rough.location = "Linear", "EXTEND", (-500, -20)
    links.new(uv.outputs["UV"], rough.inputs["Vector"])
    links.new(rough.outputs["Color"], bsdf.inputs["Roughness"])

    normal_tex = nodes.new("ShaderNodeTexImage")
    normal_tex.name, normal_tex.label, normal_tex.image = "ReferenceNormal", "Reference Micro Normal", normal_img
    normal_tex.interpolation, normal_tex.extension, normal_tex.location = "Linear", "EXTEND", (-500, -300)
    links.new(uv.outputs["UV"], normal_tex.inputs["Vector"])
    normal_map = nodes.new("ShaderNodeNormalMap")
    normal_map.location = (70, -260)
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
        "uvMap": "ReferenceFrontUV",
    }


def assign_material(meshes: list[bpy.types.Object], mat: bpy.types.Material) -> None:
    for obj in meshes:
        obj.data.materials.clear()
        obj.data.materials.append(mat)
        for poly in obj.data.polygons:
            poly.material_index = 0


def set_eevee(scene: bpy.types.Scene) -> str:
    for engine in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        try:
            scene.render.engine = engine
            return engine
        except Exception:
            continue
    raise RuntimeError("No Eevee render engine is available")


def point_at(obj: bpy.types.Object, target: Vector) -> None:
    obj.rotation_euler = (target - obj.location).to_track_quat("-Z", "Y").to_euler()


def render_previews(
    surface_meshes: list[bpy.types.Object],
    helper_meshes: list[bpy.types.Object],
    mn: Vector,
    mx: Vector,
    preview_dir: Path,
) -> tuple[list[str], str]:
    preview_dir.mkdir(parents=True, exist_ok=True)
    scene = bpy.context.scene
    engine = set_eevee(scene)
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1024
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    if scene.world is None:
        scene.world = bpy.data.worlds.new("REFERENCE_QC_WORLD")
    scene.world.use_nodes = False
    scene.world.color = (0.025, 0.025, 0.03)
    scene.frame_set(0)

    surface_names = {obj.name for obj in surface_meshes}
    helper_names = {obj.name for obj in helper_meshes}
    for obj in bpy.data.objects:
        if obj.type == "MESH":
            obj.hide_render = obj.name not in surface_names or obj.name in helper_names

    center = (mn + mx) * 0.5
    ext = mx - mn
    radius = max(ext.x, ext.y, ext.z) * 1.35

    cam_data = bpy.data.cameras.new("REFERENCE_QC_CAMERA")
    cam = bpy.data.objects.new("REFERENCE_QC_CAMERA", cam_data)
    bpy.context.collection.objects.link(cam)
    cam.data.lens = 62
    scene.camera = cam

    lights = [
        ("REFERENCE_QC_KEY", 1050, radius * 0.85, Vector((1.35, -1.25, 1.45))),
        ("REFERENCE_QC_FILL", 520, radius * 0.80, Vector((-1.15, -0.80, 0.65))),
        ("REFERENCE_QC_RIM", 620, radius * 0.60, Vector((0.20, 1.50, 1.10))),
    ]
    for name, energy, size, direction in lights:
        data = bpy.data.lights.new(name, type="AREA")
        data.energy, data.size = energy, size
        if name == "REFERENCE_QC_KEY":
            data.shape = "DISK"
        obj = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(obj)
        obj.location = center + Vector(
            (direction.x * radius, direction.y * radius, direction.z * radius)
        )
        point_at(obj, center)

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
        if not path.is_file() or path.stat().st_size < 10_000:
            raise RuntimeError(f"QC render missing or unexpectedly small: {path}")
        outputs.append(path.name)

    for name in ("REFERENCE_QC_CAMERA", "REFERENCE_QC_KEY", "REFERENCE_QC_FILL", "REFERENCE_QC_RIM"):
        obj = bpy.data.objects.get(name)
        if obj is not None:
            bpy.data.objects.remove(obj, do_unlink=True)
    return outputs, engine


def animation_names() -> list[str]:
    return sorted({action.name for action in bpy.data.actions if action.name})


def topology_snapshot(meshes: list[bpy.types.Object]) -> dict[str, dict[str, int]]:
    return {
        obj.name: {"vertices": len(obj.data.vertices), "polygons": len(obj.data.polygons)}
        for obj in meshes
    }


def topology_totals(snapshot: dict[str, dict[str, int]]) -> tuple[int, int]:
    return (
        sum(item["vertices"] for item in snapshot.values()),
        sum(item["polygons"] for item in snapshot.values()),
    )


def main() -> int:
    args = parse_args()
    for path in (args.target, args.basecolor, args.roughness, args.normal):
        if not path.is_file() or path.stat().st_size <= 0:
            raise SystemExit(f"Required input missing: {path}")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    imported = import_glb(args.target)
    meshes = [obj for obj in imported if obj.type == "MESH"]
    armatures = [obj for obj in imported if obj.type == "ARMATURE"]
    if not meshes or not armatures:
        raise RuntimeError("Target must contain mesh objects and an armature")

    surface_meshes, helper_meshes = choose_surface_meshes(meshes)
    before = topology_snapshot(meshes)
    before_vertices, before_polygons = topology_totals(before)
    actions_before = animation_names()

    # Critical fix: helper/gizmo meshes never participate in hero bounds or UV projection.
    mn, mx = world_bounds(surface_meshes)
    uv_meta = apply_global_front_planar_uv(surface_meshes, mn, mx)
    mat, material_meta = make_material(args.basecolor, args.roughness, args.normal)
    assign_material(surface_meshes, mat)

    if topology_snapshot(meshes) != before:
        raise RuntimeError("Topology changed while applying local PBR candidate")

    preview_files, render_engine = render_previews(
        surface_meshes, helper_meshes, mn, mx, args.preview_dir
    )

    bpy.ops.object.select_all(action="DESELECT")
    for obj in imported:
        if bpy.data.objects.get(obj.name) is not None:
            obj.select_set(True)
    bpy.context.view_layer.objects.active = max(
        surface_meshes, key=lambda obj: len(obj.data.vertices)
    )

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

    after = topology_snapshot(meshes)
    after_vertices, after_polygons = topology_totals(after)
    if after != before:
        raise RuntimeError("Target topology changed before QC report")

    qc = {
        "sourceMotionHero": args.target.name,
        "productionReady": False,
        "purpose": "zero-cost corrected reference-projected PBR motion candidate",
        "freeOnly": True,
        "cloudProviderUsed": False,
        "paidFallbackAllowed": False,
        "geometryPreserved": True,
        "decimationApplied": False,
        "remeshApplied": False,
        "meshCount": len(meshes),
        "meshNames": [obj.name for obj in meshes],
        "surfaceMeshNames": [obj.name for obj in surface_meshes],
        "excludedHelperMeshNames": [obj.name for obj in helper_meshes],
        "helperExcludedFromUvBounds": True,
        "topologyBeforeAfter": {
            name: {
                "vertices": [before[name]["vertices"], after[name]["vertices"]],
                "polygons": [before[name]["polygons"], after[name]["polygons"]],
            }
            for name in before
        },
        "targetVerticesBeforeAfter": [before_vertices, after_vertices],
        "targetPolygonsBeforeAfter": [before_polygons, after_polygons],
        "armatureCount": len(armatures),
        "actionsBefore": actions_before,
        "actionsAfter": animation_names(),
        "uv": uv_meta,
        "material": material_meta,
        "previewFiles": preview_files,
        "renderEngine": render_engine,
        "knownGaps": [
            "single-view reference cannot author authoritative hidden side/back markings",
            "reference-derived normal map is micro-detail approximation",
            "strand/groom fur is not authored yet",
            "true eyelid, independent eyeball, muzzle/cheek and tongue deformation remain open",
            "physical Android frame pacing and deformation acceptance remain open",
        ],
        "productionGate": "CLOSED",
    }
    args.qc.parent.mkdir(parents=True, exist_ok=True)
    args.qc.write_text(json.dumps(qc, indent=2), encoding="utf-8")
    print(json.dumps(qc, indent=2))
    print("REFERENCE_PBR_APPLICATION_GATE=PASS")
    print("PRODUCTION_GATE=CLOSED")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as exc:
        print(
            f"REFERENCE_PBR_APPLICATION_GATE=FAIL: {type(exc).__name__}: {exc}",
            file=sys.stderr,
        )
        raise SystemExit(2)
