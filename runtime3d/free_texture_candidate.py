#!/usr/bin/env python3
"""Probe legitimate free texture/reference providers without paid fallback.

The output is a TEXTURE CANDIDATE ONLY. Provider meshes may use different topology or perform
internal remeshing, so no result from this script may replace the accepted high-detail hero mesh
without visual/topology comparison. Texture candidates are used as material references or baking
sources while the accepted hero geometry stays protected.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).parent))
from inspect_glb import read_glb_json

PROVIDERS = [
    {"id":"hf-stable-fast-3d-zero-textured","space":"stabilityai/stable-fast-3d","priority":10,"adapter":"stable_fast_3d","qualityRole":"texture-reference-only"},
    {"id":"hf-hunyuan3d-2.1-zero-textured","space":"tencent/Hunyuan3D-2.1","priority":20,"adapter":"hunyuan_generation_all","qualityRole":"pbr-candidate-only"},
    {"id":"hf-hunyuan3d-2.0-zero-textured","space":"tencent/Hunyuan3D-2","priority":30,"adapter":"hunyuan_generation_all","qualityRole":"texture-candidate-only"},
]

def now(): return datetime.now(timezone.utc).isoformat()
def sanitize(name: str) -> str:
    value = re.sub(r"\W", "_", name.strip()); return "_" + value if value[:1].isdigit() else value

def client(space: str):
    from gradio_client import Client
    token=os.environ.get("HF_TOKEN","").strip() or None; kw={"verbose":False}
    if token: kw["hf_token"]=token
    return Client(space,**kw)

def endpoint_matching(c:Any, needle:str):
    named=(c.view_api(print_info=False,return_format="dict").get("named_endpoints") or {})
    name=next((n for n in named if needle.lower() in n.lower()),None)
    if not name: raise RuntimeError(f"No endpoint containing {needle!r}; endpoints={list(named)}")
    return name,named[name]

def build_hunyuan_kwargs(info,reference):
    from gradio_client import handle_file
    out={}
    for p in info.get("parameters") or []:
        raw=str(p.get("parameter_name") or p.get("label") or "").strip()
        if not raw: continue
        name=sanitize(raw); low=name.lower()
        if low=="image" or (low.endswith("_image") and not low.startswith("mv_")): out[name]=handle_file(str(reference))
        elif low=="caption": out[name]=None
        elif low.startswith("mv_image_"): out[name]=None
        elif low in {"steps","num_steps"}: out[name]=30
        elif low in {"guidance_scale","cfg_scale"}: out[name]=7.5
        elif low=="seed": out[name]=1234
        elif low=="octree_resolution": out[name]=384
        elif low in {"check_box_rembg","remove_background"}: out[name]=True
        elif low=="num_chunks": out[name]=200000
        elif low=="randomize_seed": out[name]=False
        elif p.get("parameter_has_default"): continue
        else: out[name]=None
    if not any(k.lower()=="image" for k in out): raise RuntimeError(f"generation_all exposes no image input: {list(out)}")
    return out

def make_soft_alpha_reference(source:Path,output:Path):
    from PIL import Image
    image=Image.open(source).convert("RGB"); w,h=image.size; px=image.load(); border=[]
    for x in range(0,w,max(1,w//80)): border += [px[x,0],px[x,h-1]]
    for y in range(0,h,max(1,h//120)): border += [px[0,y],px[w-1,y]]
    bg=tuple(sorted(v[i] for v in border)[len(border)//2] for i in range(3))
    rgba=Image.new("RGBA",image.size); src=image.load(); dst=rgba.load(); transparent=opaque=0
    for y in range(h):
        for x in range(w):
            r,g,b=src[x,y]; d=math.sqrt((r-bg[0])**2+(g-bg[1])**2+(b-bg[2])**2)
            alpha=int(max(0.0,min(1.0,(d-12.0)/38.0))*255.0)
            transparent += alpha<8; opaque += alpha>247; dst[x,y]=(r,g,b,alpha)
    rgba.save(output,format="PNG")
    if output.stat().st_size<10000: raise RuntimeError("Soft-alpha preprocessing produced an unexpectedly small image")
    return {"backgroundEstimate":list(bg),"transparentPixels":transparent,"opaquePixels":opaque,"totalPixels":w*h,"outputBytes":output.stat().st_size}

def run_stable_fast_3d(c,reference:Path,scratch:Path):
    from gradio_client import handle_file
    masked=scratch/"sf3d-reference-rgba.png"; matte=make_soft_alpha_reference(reference,masked)
    endpoint,info=endpoint_matching(c,"run_button"); kwargs={}; exposed=[]
    for p in info.get("parameters") or []:
        raw=str(p.get("parameter_name") or p.get("label") or "").strip()
        if not raw: continue
        name=sanitize(raw); low=name.lower(); exposed.append(name)
        if low in {"input_image","image"}: kwargs[name]=handle_file(str(masked))
        elif "foreground_ratio" in low: kwargs[name]=0.90
        elif "remesh" in low: kwargs[name]="None"
        elif "vertex" in low and "count" in low: kwargs[name]=-1
        elif "texture" in low and "size" in low: kwargs[name]=2048
        elif p.get("parameter_has_default"): continue
        else: kwargs[name]=None
    if not any(k.lower() in {"input_image","image"} for k in kwargs):
        raise RuntimeError(f"SF3D run endpoint exposes no image parameter; parameters={exposed}")
    result=c.predict(api_name=endpoint,**kwargs)
    return result,{"endpoint":endpoint,"parameters":exposed,"matte":matte,"textureSizeRequested":2048,"remesh":"None","vertexTarget":-1}

def run_hunyuan(c,reference):
    endpoint,info=endpoint_matching(c,"generation_all"); kw=build_hunyuan_kwargs(info,reference)
    return c.predict(api_name=endpoint,**kw),{"endpoint":endpoint,"parameters":list(kw),"textureClass":"PBR-if-provider-succeeds"}

def collect_glbs(value,found):
    if value is None:return
    if isinstance(value,(str,Path)):
        p=Path(value)
        if p.suffix.lower()==".glb" and p.is_file():found.append(p)
    elif isinstance(value,dict):
        for v in value.values():collect_glbs(v,found)
    elif isinstance(value,(list,tuple)):
        for v in value:collect_glbs(v,found)
def score_glb(path):
    doc=read_glb_json(path); meshes=doc.get("meshes") or []; accessors=doc.get("accessors") or []; vertices=0
    for mesh in meshes:
        for prim in mesh.get("primitives") or []:
            pos=(prim.get("attributes") or {}).get("POSITION")
            if isinstance(pos,int) and 0<=pos<len(accessors):vertices+=int(accessors[pos].get("count") or 0)
    return {"bytes":path.stat().st_size,"meshes":len(meshes),"vertices":vertices,"materials":len(doc.get("materials") or []),"textures":len(doc.get("textures") or []),"images":len(doc.get("images") or [])}
def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--reference-file",type=Path,required=True); ap.add_argument("--output-dir",type=Path,required=True); args=ap.parse_args()
    if not args.reference_file.is_file() or args.reference_file.stat().st_size<10000: raise RuntimeError("Exact hero reference file missing/too small")
    args.output_dir.mkdir(parents=True,exist_ok=True)
    report={"startedAt":now(),"freeOnly":True,"paidFallbackAllowed":False,"automaticHeroReplacementAllowed":False,"productionReady":False,"productionGate":"CLOSED","referenceBytes":args.reference_file.stat().st_size,"attempts":[],"winner":None,"warnings":["Provider meshes can use different topology/remeshing from the accepted hero.","A textured candidate is a material reference/baking source until visual/topology comparison passes.","The accepted high-detail rig/motion hero is never automatically replaced by this pool."]}
    for provider in sorted(PROVIDERS,key=lambda x:int(x["priority"])):
        attempt={"provider":provider["id"],"space":provider["space"],"qualityRole":provider["qualityRole"],"startedAt":now()}; report["attempts"].append(attempt)
        try:
            c=client(provider["space"]); started=time.monotonic()
            if provider["adapter"]=="stable_fast_3d": result,meta=run_stable_fast_3d(c,args.reference_file,args.output_dir)
            else: result,meta=run_hunyuan(c,args.reference_file)
            glbs=[]; collect_glbs(result,glbs); unique=[]; seen=set()
            for p in glbs:
                k=str(p.resolve())
                if k not in seen:seen.add(k);unique.append(p)
            scored=[(p,score_glb(p)) for p in unique]; attempt["adapter"]=meta; attempt["returned"]=[{"file":p.name,**s} for p,s in scored]
            candidates=[(p,s) for p,s in scored if s["materials"]>0 and (s["textures"]>0 or s["images"]>0)]
            if not candidates: raise RuntimeError(f"Provider returned no GLB with material+texture payload; returned={attempt['returned']}")
            source,stats=max(candidates,key=lambda item:(item[1]["textures"]+item[1]["images"],item[1]["vertices"],item[1]["bytes"]))
            target=args.output_dir/f"police_dog.{provider['id']}.texture_candidate.glb"; shutil.copy2(source,target)
            attempt.update({"status":"success","elapsedSeconds":round(time.monotonic()-started,2),"winnerStats":stats,"finishedAt":now()}); report.update({"winner":provider["id"],"winnerFile":target.name,"winnerStats":stats,"winnerQualityRole":provider["qualityRole"]});break
        except Exception as exc:
            attempt.update({"status":"failed-free-provider","reason":str(exc)[:1800],"finishedAt":now()}); print(f"::warning::{provider['id']} failed: {attempt['reason']}",flush=True)
    report["finishedAt"]=now(); (args.output_dir/"texture-provider-report.json").write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    if not report["winner"]: print("::error::All automated free texture providers unavailable/exhausted; paid fallback disabled."); return 2
    print(f"FREE_TEXTURE_WINNER={report['winner']}"); print(f"FREE_TEXTURE_CANDIDATE={report['winnerFile']}"); print(f"FREE_TEXTURE_ROLE={report['winnerQualityRole']}"); print("AUTO_REPLACE_HERO=false"); print("PRODUCTION_GATE=CLOSED"); return 0
if __name__=="__main__": raise SystemExit(main())
