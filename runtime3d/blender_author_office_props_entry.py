#!/usr/bin/env python3
"""Blender entrypoint that forwards only arguments after `--` to office prop authoring."""
from __future__ import annotations

from pathlib import Path
import runpy
import sys

script = Path(__file__).resolve().with_name("blender_author_office_props.py")
raw = list(sys.argv)
forwarded = raw[raw.index("--") + 1:] if "--" in raw else []
sys.argv = [str(script), *forwarded]
runpy.run_path(str(script), run_name="__main__")
