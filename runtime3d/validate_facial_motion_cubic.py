#!/usr/bin/env python3
"""Run the strict facial validator with correct glTF CUBICSPLINE sample handling.

For CUBICSPLINE samplers glTF stores each key as [in-tangent, value, out-tangent].
The base validator intentionally stays format-focused; this adapter makes its pose/span
queries operate on the actual key values instead of tangent rows.
"""
from __future__ import annotations

import sys
from typing import Any

import validate_facial_motion as base


def _effective_values(
    doc: dict[str, Any],
    binary: bytes,
    animation: dict[str, Any],
    channel: dict[str, Any],
) -> list[tuple[float, ...]]:
    sampler = animation["samplers"][channel["sampler"]]
    values = base.read_accessor(doc, binary, sampler["output"])
    interpolation = str(sampler.get("interpolation") or "LINEAR").upper()
    if interpolation == "CUBICSPLINE":
        if len(values) % 3 != 0:
            raise RuntimeError(
                f"Invalid CUBICSPLINE output count for animation {animation.get('name')}: {len(values)}"
            )
        return values[1::3]
    return values


def first_channel_value(
    doc: dict[str, Any],
    binary: bytes,
    animation: dict[str, Any],
    node_name: str,
    path: str,
) -> tuple[float, ...] | None:
    nodes = doc.get("nodes") or []
    for channel in animation.get("channels") or []:
        target = channel.get("target") or {}
        node_index = target.get("node")
        name = nodes[node_index].get("name") if isinstance(node_index, int) and node_index < len(nodes) else None
        if name == node_name and target.get("path") == path:
            values = _effective_values(doc, binary, animation, channel)
            return values[0] if values else None
    return None


def channel_span(
    doc: dict[str, Any],
    binary: bytes,
    animation: dict[str, Any],
    node_name: str,
    path: str,
) -> float:
    nodes = doc.get("nodes") or []
    for channel in animation.get("channels") or []:
        target = channel.get("target") or {}
        node_index = target.get("node")
        name = nodes[node_index].get("name") if isinstance(node_index, int) and node_index < len(nodes) else None
        if name == node_name and target.get("path") == path:
            return base.max_component_span(_effective_values(doc, binary, animation, channel))
    return 0.0


base.first_channel_value = first_channel_value
base.channel_span = channel_span

if __name__ == "__main__":
    base.main()
