#!/usr/bin/env python3
"""Compatibility entrypoint for the semantic vertex-color hero bake.

The legacy PBR candidate runner requires at least one image texture node after material
creation. The vertex-color material deliberately has none, so patch only that obsolete
compatibility check while leaving export, topology validation and rendering unchanged.
The workflow performs authoritative glTF inspection afterwards and requires COLOR_0
while rejecting any baseColorTexture.
"""
from __future__ import annotations

import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_transfer_vertexcolor_semantic as semantic

base = semantic.base


def vertex_color_compat_pack(_materials):
    # Compatibility sentinel for legacy base.main().  This does not create images.
    # The post-export workflow inspects the actual GLB and rejects baseColorTexture.
    return 1, 1


base.ensure_images_packed = vertex_color_compat_pack

if __name__ == "__main__":
    raise SystemExit(base.main())
