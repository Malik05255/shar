#!/usr/bin/env python3
"""Extract BaseColor and Normal images from an embedded GLB without Blender.

This is intentionally strict: it follows glTF material -> texture -> image references,
accepts only embedded bufferView/data-URI images, preserves original bytes, and fails
closed when either required PBR image is unavailable.
"""
from __future__ import annotations

import argparse
import base64
import json
import struct
from pathlib import Path

GLB_MAGIC = 0x46546C67
JSON_CHUNK = 0x4E4F534A
BIN_CHUNK = 0x004E4942


def read_glb(path: Path) -> tuple[dict, bytes]:
    raw = path.read_bytes()
    if len(raw) < 20:
        raise SystemExit("GLB too small")
    magic, version, declared = struct.unpack_from("<III", raw, 0)
    if magic != GLB_MAGIC or version != 2 or declared != len(raw):
        raise SystemExit("Invalid GLB header")
    doc = None
    bin_chunk = b""
    off = 12
    while off + 8 <= len(raw):
        length, kind = struct.unpack_from("<II", raw, off)
        off += 8
        payload = raw[off:off + length]
        off += length
        if kind == JSON_CHUNK:
            doc = json.loads(payload.rstrip(b" \t\r\n\x00").decode("utf-8"))
        elif kind == BIN_CHUNK:
            bin_chunk = payload
    if doc is None:
        raise SystemExit("Missing GLB JSON chunk")
    return doc, bin_chunk


def material_index(doc: dict) -> int:
    # Prefer the material used by the largest POSITION accessor in the donor.
    accessors = doc.get("accessors") or []
    best = None
    for mesh in doc.get("meshes") or []:
        for prim in mesh.get("primitives") or []:
            pos = (prim.get("attributes") or {}).get("POSITION")
            mat = prim.get("material")
            if not isinstance(pos, int) or not isinstance(mat, int) or not (0 <= pos < len(accessors)):
                continue
            count = int(accessors[pos].get("count") or 0)
            if best is None or count > best[0]:
                best = (count, mat)
    if best is None:
        raise SystemExit("Donor has no material-bearing mesh primitive")
    return best[1]


def image_bytes(doc: dict, bin_chunk: bytes, image_index: int) -> tuple[bytes, str]:
    images = doc.get("images") or []
    if not (0 <= image_index < len(images)):
        raise SystemExit(f"Invalid image index {image_index}")
    image = images[image_index]
    mime = str(image.get("mimeType") or "")
    if "bufferView" in image:
        views = doc.get("bufferViews") or []
        vi = image["bufferView"]
        if not isinstance(vi, int) or not (0 <= vi < len(views)):
            raise SystemExit("Invalid image bufferView")
        view = views[vi]
        start = int(view.get("byteOffset") or 0)
        length = int(view.get("byteLength") or 0)
        payload = bin_chunk[start:start + length]
    else:
        uri = str(image.get("uri") or "")
        if not uri.startswith("data:") or ";base64," not in uri:
            raise SystemExit("External image URI is not accepted in strict mode")
        header, encoded = uri.split(",", 1)
        if not mime:
            mime = header[5:].split(";", 1)[0]
        payload = base64.b64decode(encoded)
    if not payload:
        raise SystemExit("Extracted image is empty")
    if mime == "image/png" or payload.startswith(b"\x89PNG\r\n\x1a\n"):
        ext = ".png"
    elif mime in {"image/jpeg", "image/jpg"} or payload.startswith(b"\xff\xd8\xff"):
        ext = ".jpg"
    else:
        raise SystemExit(f"Unsupported embedded image MIME/signature: {mime!r}")
    return payload, ext


def texture_source(doc: dict, texture_info: dict, label: str) -> int:
    ti = texture_info.get("index")
    textures = doc.get("textures") or []
    if not isinstance(ti, int) or not (0 <= ti < len(textures)):
        raise SystemExit(f"{label} texture index missing/invalid")
    source = textures[ti].get("source")
    if not isinstance(source, int):
        raise SystemExit(f"{label} texture has no image source")
    return source


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path, required=True)
    ap.add_argument("--output-dir", type=Path, required=True)
    ap.add_argument("--metadata", type=Path, required=True)
    args = ap.parse_args()

    doc, bin_chunk = read_glb(args.input)
    materials = doc.get("materials") or []
    mi = material_index(doc)
    if not (0 <= mi < len(materials)):
        raise SystemExit("Material index out of range")
    mat = materials[mi]
    pbr = mat.get("pbrMetallicRoughness") or {}
    base_info = pbr.get("baseColorTexture") or {}
    normal_info = mat.get("normalTexture") or {}
    base_source = texture_source(doc, base_info, "BaseColor")
    normal_source = texture_source(doc, normal_info, "Normal")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    base_payload, base_ext = image_bytes(doc, bin_chunk, base_source)
    normal_payload, normal_ext = image_bytes(doc, bin_chunk, normal_source)
    base_path = args.output_dir / ("basecolor" + base_ext)
    normal_path = args.output_dir / ("normal" + normal_ext)
    base_path.write_bytes(base_payload)
    normal_path.write_bytes(normal_payload)

    meta = {
        "input": args.input.name,
        "materialIndex": mi,
        "baseColorImageIndex": base_source,
        "normalImageIndex": normal_source,
        "baseColorFile": base_path.name,
        "normalFile": normal_path.name,
        "baseColorBytes": len(base_payload),
        "normalBytes": len(normal_payload),
        "strictEmbeddedOnly": True,
    }
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    args.metadata.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
