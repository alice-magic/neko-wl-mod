#!/usr/bin/env python3
"""Upload one built jar to Modrinth as a version.

Run once per jar. Each loader gets its own Modrinth version so someone filtering
by Fabric or NeoForge only sees the jar that fits their server.

Reads MODRINTH_TOKEN from the environment; the token is never logged or echoed.
Exits 0 when the version already exists, so re-running a release is harmless.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.modrinth.com/v2"
PROJECT = "neko-launcher-whitelist"

# Architectury only ships a Forge target on 1.20.1; everything later is NeoForge.
# The Fabric jar also runs on Quilt, so declare both rather than making Quilt
# users guess.
LOADER_ALIASES = {
    "fabric": ["fabric", "quilt"],
    "forge": ["forge"],
    "neoforge": ["neoforge"],
}


def request(method, url, token, data=None, headers=None):
    req = urllib.request.Request(url, method=method, data=data)
    req.add_header("Authorization", token)
    req.add_header("User-Agent", "alice-magic/neko-wl-mod publisher")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    with urllib.request.urlopen(req) as response:
        body = response.read()
        return response.status, json.loads(body) if body else None


def version_exists(token, version_number):
    """True when this exact version_number is already published."""
    try:
        _, versions = request("GET", f"{API}/project/{PROJECT}/version", token)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise
    return any(v.get("version_number") == version_number for v in versions or [])


def multipart(fields, filename, payload):
    """Builds a multipart/form-data body without pulling in a dependency."""
    boundary = "----nekoWhitelistBoundary7f3c8a1e"
    parts = []

    for name, value in fields.items():
        parts.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
            f"{value}\r\n".encode()
        )

    parts.append(
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: application/java-archive\r\n\r\n".encode()
    )
    parts.append(payload)
    parts.append(f"\r\n--{boundary}--\r\n".encode())

    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True)
    parser.add_argument("--loader", required=True)
    parser.add_argument("--mc", required=True, help="Minecraft version, e.g. 1.21.1")
    parser.add_argument("--tag", required=True, help="Release tag, e.g. v1.0.0")
    args = parser.parse_args()

    token = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not token:
        print("MODRINTH_TOKEN is not set; skipping the Modrinth upload.")
        return 0

    loaders = LOADER_ALIASES.get(args.loader)
    if not loaders:
        print(f"Unknown loader '{args.loader}' in {args.file}; skipping.")
        return 0

    # Unique per loader and Minecraft version, since one release publishes eight jars.
    version_number = f"{args.tag.lstrip('v')}+mc{args.mc}-{args.loader}"

    if version_exists(token, version_number):
        print(f"{version_number} already on Modrinth; nothing to do.")
        return 0

    metadata = {
        "name": f"{args.tag} for {args.mc} ({args.loader})",
        "version_number": version_number,
        "game_versions": [args.mc],
        "version_type": "release",
        "loaders": loaders,
        "featured": False,
        "project_id": PROJECT,
        "dependencies": [
            # Architectury API is required at runtime on every loader.
            {"project_id": "lhGA9TYQ", "dependency_type": "required"},
        ],
        "file_parts": ["file"],
        "primary_file": "file",
    }

    # Fabric additionally needs Fabric API.
    if args.loader == "fabric":
        metadata["dependencies"].append(
            {"project_id": "P7dR8mSH", "dependency_type": "required"}
        )

    with open(args.file, "rb") as handle:
        payload = handle.read()

    body, content_type = multipart(
        {"data": json.dumps(metadata)}, os.path.basename(args.file), payload
    )

    try:
        status, _ = request(
            "POST", f"{API}/version", token, body, {"Content-Type": content_type}
        )
    except urllib.error.HTTPError as error:
        # Never let a token reach the log, even inside an error body.
        detail = error.read().decode("utf-8", "replace").replace(token, "***")
        print(f"Modrinth rejected {version_number}: HTTP {error.code} {detail}")
        return 1

    print(f"Published {version_number} ({status}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
