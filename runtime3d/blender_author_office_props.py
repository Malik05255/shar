#!/usr/bin/env python3
"""Author the non-character Runtime3D office actors as independent free GLB assets.

The assets intentionally share a local-origin convention. Runtime3DOfficeStage is responsible for
world placement, so none of these files bake a one-scene location into their geometry. Prop clips
use exact Runtime3DAssetCatalog names and are authored as independent NLA tracks.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import bpy
from mathutils import Vector

FPS = 30


def reset_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.unit_settings.system = 'METRIC'
    scene.unit_settings.scale_length = 1.0


def material(name: str, color: tuple[float, float, float, float], roughness: float, metallic: float = 0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Base Color'].default_value = color
    bsdf.inputs['Roughness'].default_value = roughness
    bsdf.inputs['Metallic'].default_value = metallic
    return mat


def add_beveled_cube(name: str, size, location, mat, bevel=0.025, parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel > 0:
        mod = obj.modifiers.new('EdgeSoftness', 'BEVEL')
        mod.width = bevel
        mod.segments = 3
    obj.data.materials.append(mat)
    if parent is not None:
        obj.parent = parent
    return obj


def add_cylinder(name: str, radius: float, depth: float, location, mat, vertices=48, parent=None, rotation=(0,0,0)):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.data.materials.append(mat)
    if parent is not None:
        obj.parent = parent
    return obj


def add_uv_sphere(name: str, scale, location, mat, parent=None):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=48, ring_count=24, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    if parent is not None:
        obj.parent = parent
    return obj


def make_root(name: str):
    root = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(root)
    root.rotation_mode = 'XYZ'
    return root


def action_track(obj, name: str, duration_frames: int, location_keys=None, rotation_keys=None, scale_keys=None):
    action = bpy.data.actions.new(name)
    action.use_fake_user = True
    if location_keys:
        for axis in range(3):
            curve = action.fcurves.new(data_path='location', index=axis)
            for frame, value in location_keys:
                curve.keyframe_points.insert(frame, value[axis], options={'FAST'})
    if rotation_keys:
        obj.rotation_mode = 'XYZ'
        for axis in range(3):
            curve = action.fcurves.new(data_path='rotation_euler', index=axis)
            for frame, value in rotation_keys:
                curve.keyframe_points.insert(frame, value[axis], options={'FAST'})
    if scale_keys:
        for axis in range(3):
            curve = action.fcurves.new(data_path='scale', index=axis)
            for frame, value in scale_keys:
                curve.keyframe_points.insert(frame, value[axis], options={'FAST'})
    obj.animation_data_create()
    track = obj.animation_data.nla_tracks.new()
    track.name = name
    track.strips.new(name, 1.0, action)
    return action


def idle_track(obj):
    return action_track(obj, 'Idle', 2, location_keys=[(1, tuple(obj.location)), (2, tuple(obj.location))])


def select_tree(root):
    bpy.ops.object.select_all(action='DESELECT')
    selected = []
    def walk(obj):
        obj.select_set(True)
        selected.append(obj)
        for child in obj.children:
            walk(child)
    walk(root)
    bpy.context.view_layer.objects.active = root
    return selected


def export(root, path: Path):
    select_tree(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(path),
        export_format='GLB',
        use_selection=True,
        export_yup=True,
        export_apply=False,
        export_animations=True,
        export_nla_strips=True,
        export_materials='EXPORT',
        export_normals=True,
        export_texcoords=True,
    )
    if not path.is_file() or path.stat().st_size < 2_000:
        raise RuntimeError(f'Asset export failed: {path}')


def build_office_shell(out: Path):
    reset_scene(); root=make_root('OfficeShell')
    wall=material('WarmWall',(0.34,0.36,0.38,1),0.88)
    floor=material('DarkStone',(0.075,0.085,0.095,1),0.62)
    trim=material('BlackMetal',(0.025,0.03,0.035,1),0.38,0.08)
    glass=material('WindowGlass',(0.12,0.18,0.22,1),0.18)
    add_beveled_cube('Floor',(8.4,0.12,7.2),(0,-0.06,1.4),floor,0.02,root)
    add_beveled_cube('BackWall',(8.4,3.2,0.14),(0,1.6,4.95),wall,0.01,root)
    add_beveled_cube('LeftWall',(0.14,3.2,7.2),(-4.13,1.6,1.4),wall,0.01,root)
    add_beveled_cube('RightWall',(0.14,3.2,7.2),(4.13,1.6,1.4),wall,0.01,root)
    # Architectural ribs and a broad rear window imply a real office without a front wall blocking camera.
    for x in (-3.2,-1.6,0,1.6,3.2):
        add_beveled_cube(f'Rib{x}',(0.055,2.8,0.10),(x,1.55,4.86),trim,0.008,root)
    add_beveled_cube('RearGlass',(4.2,1.45,0.035),(1.45,1.85,4.84),glass,0.006,root)
    export(root,out/'office_shell.glb')


def build_desk(out: Path):
    reset_scene(); root=make_root('Desk')
    wood=material('DeskWalnut',(0.12,0.075,0.045,1),0.47)
    metal=material('DeskMetal',(0.035,0.04,0.045,1),0.32,0.18)
    add_beveled_cube('Top',(1.95,0.08,0.92),(0,0.78,0),wood,0.035,root)
    for x in (-0.82,0.82):
        for z in (-0.32,0.32):
            add_beveled_cube(f'Leg{x}{z}',(0.07,0.74,0.07),(x,0.37,z),metal,0.018,root)
    add_beveled_cube('Drawer',(0.52,0.52,0.72),(-0.62,0.48,0.03),wood,0.028,root)
    export(root,out/'desk.glb')


def build_door(out: Path):
    reset_scene(); root=make_root('DoorHinge')
    wood=material('DoorWood',(0.14,0.08,0.045,1),0.5)
    metal=material('HandleMetal',(0.12,0.13,0.14,1),0.24,0.65)
    leaf=add_beveled_cube('DoorLeaf',(1.0,2.25,0.085),(0.5,1.125,0),wood,0.025,root)
    add_cylinder('Handle',0.035,0.18,(0.87,1.12,-0.12),metal,32,root,rotation=(math.pi/2,0,0))
    idle_track(root)
    action_track(root,'OpenDoor',30,rotation_keys=[(1,(0,0,0)),(30,(0,-math.radians(88),0))])
    action_track(root,'CloseDoor',30,rotation_keys=[(1,(0,-math.radians(88),0)),(30,(0,0,0))])
    export(root,out/'door.glb')


def build_phone(out: Path):
    reset_scene(); root=make_root('Phone')
    dark=material('PhoneBody',(0.035,0.04,0.045,1),0.36)
    key=material('PhoneKeys',(0.12,0.13,0.14,1),0.48)
    add_beveled_cube('Base',(0.34,0.08,0.24),(0,0.04,0),dark,0.025,root)
    add_beveled_cube('Handset',(0.36,0.055,0.075),(0,0.115,0.015),dark,0.022,root)
    for row in range(3):
        for col in range(3):
            add_beveled_cube(f'Key{row}{col}',(0.035,0.012,0.028),(-0.045+col*0.045,0.09,-0.055+row*0.036),key,0.004,root)
    idle_track(root)
    action_track(root,'Ring',18,rotation_keys=[(1,(0,0,-0.018)),(5,(0,0,0.018)),(9,(0,0,-0.018)),(13,(0,0,0.018)),(18,(0,0,0))])
    export(root,out/'phone.glb')


def build_file(out: Path):
    reset_scene(); root=make_root('File')
    folder=material('Folder',(0.18,0.28,0.38,1),0.72)
    paper=material('Paper',(0.86,0.84,0.78,1),0.92)
    add_beveled_cube('Cover',(0.31,0.018,0.41),(0,0.009,0),folder,0.008,root)
    for i in range(5):
        add_beveled_cube(f'Paper{i}',(0.285,0.004,0.385),(0,0.021+i*0.004,0),paper,0.002,root)
    idle_track(root)
    action_track(root,'MoveToHand',24,location_keys=[(1,(0,0,0)),(24,(0.18,0.38,0.16))],rotation_keys=[(1,(0,0,0)),(24,(0.08,-0.12,0.18))])
    action_track(root,'MoveToDesk',24,location_keys=[(1,(0.18,0.38,0.16)),(24,(0,0,0))],rotation_keys=[(1,(0.08,-0.12,0.18)),(24,(0,0,0))])
    export(root,out/'file.glb')


def build_monitor(out: Path):
    reset_scene(); root=make_root('Monitor')
    dark=material('MonitorFrame',(0.025,0.028,0.032,1),0.31)
    screen=material('MonitorScreen',(0.035,0.11,0.16,1),0.21,0.02)
    add_beveled_cube('Panel',(0.78,0.46,0.055),(0,0.38,0),dark,0.025,root)
    add_beveled_cube('Screen',(0.70,0.38,0.008),(0,0.38,-0.032),screen,0.012,root)
    add_beveled_cube('Stem',(0.06,0.27,0.06),(0,0.14,0.02),dark,0.015,root)
    add_beveled_cube('Stand',(0.34,0.035,0.21),(0,0.018,0.07),dark,0.018,root)
    export(root,out/'monitor.glb')


def build_keyboard(out: Path):
    reset_scene(); root=make_root('Keyboard')
    dark=material('KeyboardBody',(0.035,0.04,0.045,1),0.43)
    key=material('Keycaps',(0.085,0.09,0.095,1),0.55)
    add_beveled_cube('Board',(0.58,0.025,0.19),(0,0.0125,0),dark,0.014,root)
    for row in range(5):
        count=13 if row<4 else 8
        for col in range(count):
            x=(col-(count-1)/2)*0.038
            z=-0.064+row*0.032
            add_beveled_cube(f'K{row}_{col}',(0.031,0.011,0.024),(x,0.031,z),key,0.004,root)
    export(root,out/'keyboard.glb')


def build_chair(out: Path):
    reset_scene(); root=make_root('Chair')
    fabric=material('ChairFabric',(0.055,0.065,0.075,1),0.73)
    metal=material('ChairMetal',(0.06,0.065,0.07,1),0.31,0.3)
    add_beveled_cube('Seat',(0.58,0.11,0.56),(0,0.49,0),fabric,0.055,root)
    back=add_beveled_cube('Back',(0.58,0.78,0.10),(0,0.93,0.25),fabric,0.07,root)
    back.rotation_euler.x=math.radians(-7)
    add_cylinder('Post',0.045,0.38,(0,0.25,0),metal,32,root)
    for angle in range(0,360,72):
        a=math.radians(angle)
        x,z=0.25*math.cos(a),0.25*math.sin(a)
        arm=add_beveled_cube(f'Base{angle}',(0.32,0.035,0.045),(x/2,0.08,z/2),metal,0.012,root)
        arm.rotation_euler.y=-a
        add_cylinder(f'Wheel{angle}',0.035,0.04,(x,0.04,z),metal,24,root,rotation=(math.pi/2,0,0))
    idle_track(root)
    action_track(root,'Shift',20,location_keys=[(1,(0,0,0)),(10,(0.05,0,0.02)),(20,(0,0,0))])
    action_track(root,'Turn',30,rotation_keys=[(1,(0,0,0)),(15,(0,math.radians(12),0)),(30,(0,0,0))])
    export(root,out/'chair.glb')


def build_printer(out: Path):
    reset_scene(); root=make_root('Printer')
    body=material('PrinterBody',(0.68,0.69,0.68,1),0.52)
    dark=material('PrinterTrim',(0.045,0.05,0.055,1),0.38)
    paper_mat=material('PrinterPaper',(0.91,0.90,0.86,1),0.93)
    add_beveled_cube('Body',(0.62,0.42,0.52),(0,0.21,0),body,0.055,root)
    add_beveled_cube('Control',(0.42,0.05,0.12),(0,0.39,-0.22),dark,0.015,root)
    paper=add_beveled_cube('OutputPaper',(0.34,0.006,0.42),(0,0.23,-0.12),paper_mat,0.002,root)
    idle_track(root)
    action_track(paper,'Print',42,location_keys=[(1,tuple(paper.location)),(42,(paper.location.x,paper.location.y,paper.location.z-0.34))])
    export(root,out/'printer.glb')


def build_cup(out: Path):
    reset_scene(); root=make_root('CoffeeCup')
    ceramic=material('Ceramic',(0.82,0.80,0.73,1),0.33)
    coffee=material('Coffee',(0.055,0.025,0.012,1),0.24)
    add_cylinder('CupBody',0.055,0.105,(0,0.0525,0),ceramic,48,root)
    add_cylinder('CoffeeSurface',0.047,0.003,(0,0.104,0),coffee,48,root)
    bpy.ops.mesh.primitive_torus_add(major_radius=0.044,minor_radius=0.009,major_segments=32,minor_segments=12,location=(0.067,0.058,0),rotation=(math.pi/2,0,0))
    handle=bpy.context.object; handle.name='Handle'; handle.data.materials.append(ceramic); handle.parent=root
    idle_track(root)
    action_track(root,'MoveToHand',24,location_keys=[(1,(0,0,0)),(24,(-0.22,0.32,0.18))],rotation_keys=[(1,(0,0,0)),(24,(0.0,0.0,-0.08))])
    action_track(root,'MoveToDesk',24,location_keys=[(1,(-0.22,0.32,0.18)),(24,(0,0,0))],rotation_keys=[(1,(0.0,0.0,-0.08)),(24,(0,0,0))])
    export(root,out/'coffee_cup.glb')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args()
    args.output_dir.mkdir(parents=True,exist_ok=True)
    builders=[build_office_shell,build_desk,build_door,build_phone,build_file,build_monitor,build_keyboard,build_chair,build_printer,build_cup]
    for builder in builders:
        builder(args.output_dir)
    files=sorted(args.output_dir.glob('*.glb'))
    qc={'productionReady':False,'freeOnly':True,'assetCount':len(files),'assets':{p.name:p.stat().st_size for p in files},'productionGate':'CLOSED'}
    if len(files)!=10:
        raise RuntimeError(f'Expected 10 office GLBs, got {len(files)}')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(qc,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(qc,indent=2),flush=True)

if __name__=='__main__':
    main()
