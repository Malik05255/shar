#!/usr/bin/env python3
"""Run target-owned hero PBR with material indices assigned after slots exist.

The original authoring pass correctly classified every polygon before material slots were
created. Blender clamps material_index to zero when no slots exist, so the GLB exported one
material even though seven semantic regions had been computed. This wrapper preserves the
existing UV/texturing algorithm and only reapplies those semantic indices after all seven
material slots have been authored.
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path
import sys

# Blender's --python invocation does not reliably prepend the script directory to sys.path.
# Make sibling runtime3d modules deterministic in GitHub Actions and local headless runs.
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import blender_author_target_pbr as authored


def copy_materials_after_slots(target, donor):
    count = authored.copy_target_materials(target, donor)
    groups = authored.dominant_group_names(target)
    mn, mx = authored.base.local_bounds(target)
    counts = Counter()

    vertex_class = []
    for vertex in target.data.vertices:
        normalized = authored.base.normalize_point(vertex.co, mn, mx)
        vertex_class.append(
            authored.semantic_class(groups[vertex.index], float(normalized.z))
        )

    for polygon in target.data.polygons:
        votes = Counter(vertex_class[int(index)] for index in polygon.vertices)
        klass = votes.most_common(1)[0][0]
        polygon.material_index = authored.MATERIAL_INDEX[klass]
        counts[klass] += 1

    missing = [name for name in authored.MATERIAL_ORDER if counts[name] <= 0]
    if missing:
        raise RuntimeError(f"Post-slot semantic material assignment missing {missing}: {dict(counts)}")
    if max(int(p.material_index) for p in target.data.polygons) < len(authored.MATERIAL_ORDER) - 1:
        raise RuntimeError("Blender still clamped semantic material indices after slot creation")

    print("TARGET_PBR_POST_SLOT_MATERIALS=" + str(dict(counts)), flush=True)
    return count


authored.base.transfer_uvs = authored.transfer_target_uvs
authored.base.copy_materials = copy_materials_after_slots

if __name__ == "__main__":
    raise SystemExit(authored.base.main())
