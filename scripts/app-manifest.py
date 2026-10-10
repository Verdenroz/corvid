#!/usr/bin/env python3
"""Writes manifest.json for the release apps the image carries.

    app-manifest.py DIR VERSION_CODE

DIR holds corvid.apk and corvid-linux-x64.tar.gz. The manifest lands beside them.
"""
import hashlib
import json
import sys
from pathlib import Path


def entry(path):
    return {
        "path": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "size": path.stat().st_size,
    }


def main(directory, version_code):
    root = Path(directory)
    manifest = {
        "version": 1,
        "versionCode": version_code,
        "versionName": f"0.1.{version_code}",
        "android": entry(root / "corvid.apk"),
        "linux": entry(root / "corvid-linux-x64.tar.gz"),
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
