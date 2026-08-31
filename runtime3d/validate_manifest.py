#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path

MANIFEST = Path(__file__).with_name("manifest.json")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")

REQUIRED = {
    "POLICE_DOG",
    "OFFICE_SHELL",
    "DESK",
    "DOOR",
    "PHONE",
    "FILE",
    "MONITOR",
    "KEYBOARD",
    "CHAIR",
    "PRINTER",
    "COFFEE_CUP",
    "STAFF_MALE_01",
    "STAFF_MALE_02",
    "STAFF_FEMALE_01",
}
OPTIONAL = {"VISITOR_01"}
KNOWN = REQUIRED | OPTIONAL


def fail(message: str) -> None:
    print(f"runtime3d manifest error: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if data.get("schema") != 1:
        fail("schema must be 1")
    if not isinstance(data.get("enabled"), bool):
        fail("enabled must be boolean")
    if not str(data.get("packVersion", "")).strip():
        fail("packVersion is required")

    minimum = str(data.get("minimumAppVersion", "")).strip()
    if not SEMVER.fullmatch(minimum):
        fail("minimumAppVersion must be semantic x.y.z")

    actors = data.get("actors")
    if not isinstance(actors, list):
        fail("actors must be an array")

    ids = []
    total_bytes = 0
    for index, actor in enumerate(actors):
        if not isinstance(actor, dict):
            fail(f"actors[{index}] must be an object")
        actor_id = str(actor.get("id", "")).strip()
        if actor_id not in KNOWN:
            fail(f"actors[{index}].id is unknown: {actor_id!r}")
        if actor_id in ids:
            fail(f"duplicate actor id: {actor_id}")
        ids.append(actor_id)

        url = str(actor.get("url", "")).strip()
        digest = str(actor.get("sha256", "")).strip().lower()
        size = actor.get("bytes")
        if not url.startswith("https://"):
            fail(f"{actor_id}: url must use https")
        if not SHA256.fullmatch(digest):
            fail(f"{actor_id}: sha256 must contain 64 lowercase hex characters")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            fail(f"{actor_id}: bytes must be a positive integer")
        total_bytes += size

    if data["enabled"]:
        missing = sorted(REQUIRED - set(ids))
        if missing:
            fail("enabled pack is missing required actors: " + ", ".join(missing))
        if total_bytes <= 0:
            fail("enabled pack has no payload")
        if total_bytes > 2 * 1024 * 1024 * 1024:
            fail("declared pack exceeds the 2 GiB runtime safety limit")
    elif actors:
        print("warning: manifest disabled but contains staged actor entries", file=sys.stderr)

    print(
        f"runtime3d manifest OK: enabled={data['enabled']} "
        f"pack={data['packVersion']} actors={len(ids)} bytes={total_bytes}"
    )


if __name__ == "__main__":
    main()
