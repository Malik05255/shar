#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import quote

ACTOR_FILES = {
    "POLICE_DOG": "police_dog.glb",
    "OFFICE_SHELL": "office_shell.glb",
    "DESK": "desk.glb",
    "DOOR": "door.glb",
    "PHONE": "phone.glb",
    "FILE": "file.glb",
    "MONITOR": "monitor.glb",
    "KEYBOARD": "keyboard.glb",
    "CHAIR": "chair.glb",
    "PRINTER": "printer.glb",
    "COFFEE_CUP": "coffee_cup.glb",
    "STAFF_MALE_01": "staff_male_01.glb",
    "STAFF_MALE_02": "staff_male_02.glb",
    "STAFF_FEMALE_01": "staff_female_01.glb",
}
OPTIONAL = {"VISITOR_01": "visitor_01.glb"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Al-Shorti runtime3d/manifest.json from validated GLBs")
    parser.add_argument("--assets-dir", required=True, type=Path)
    parser.add_argument("--base-url", required=True, help="HTTPS directory containing the GLBs")
    parser.add_argument("--pack-version", required=True)
    parser.add_argument("--minimum-app-version", required=True)
    parser.add_argument("--output", type=Path, default=Path("runtime3d/manifest.json"))
    parser.add_argument("--disabled", action="store_true", help="Write the manifest with enabled=false")
    args = parser.parse_args()

    if not args.base_url.startswith("https://"):
        raise SystemExit("--base-url must use https://")
    base = args.base_url.rstrip("/")

    missing = [filename for filename in ACTOR_FILES.values() if not (args.assets_dir / filename).is_file()]
    if missing:
        raise SystemExit("Missing required GLBs: " + ", ".join(sorted(missing)))

    actors = []
    for actor_id, filename in {**ACTOR_FILES, **OPTIONAL}.items():
        path = args.assets_dir / filename
        if not path.is_file():
            continue
        actors.append(
            {
                "id": actor_id,
                "url": f"{base}/{quote(filename)}",
                "sha256": sha256(path),
                "bytes": path.stat().st_size,
            }
        )

    manifest = {
        "schema": 1,
        "enabled": not args.disabled,
        "packVersion": args.pack_version,
        "minimumAppVersion": args.minimum_app_version,
        "actors": actors,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"wrote {args.output}: enabled={manifest['enabled']} actors={len(actors)} "
        f"bytes={sum(item['bytes'] for item in actors)}"
    )


if __name__ == "__main__":
    main()
