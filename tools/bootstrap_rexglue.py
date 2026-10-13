#!/usr/bin/env python3
"""Install the official Linux x64 ReXGlue SDK with its published release digest."""
import hashlib
import os
from pathlib import Path
import urllib.request
import zipfile

VERSION = "0.10.0"
FILENAME = "rexglue-sdk-0.10.0-linux-amd64.zip"
SHA256 = "0388edb3f8fb1444f1b0ad5d440cb02d9690ac20a76b04fd6b7dcc19f59328a6"
URL = "https://github.com/rexglue/rexglue-sdk/releases/download/v0.10.0/" + FILENAME
# Digest provenance: https://github.com/rexglue/rexglue-sdk/releases/expanded_assets/v0.10.0
ROOT = Path(os.environ.get("MK_REXGLUE_TOOLS", "/workspace/.tools/rexglue"))


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    archive = ROOT / FILENAME
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        temporary = archive.with_suffix(".download")
        digest = hashlib.sha256()
        with urllib.request.urlopen(URL, timeout=60) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != SHA256:
            temporary.unlink()
            raise RuntimeError("Official ReXGlue release checksum mismatch")
        temporary.replace(archive)
    destination = ROOT / ("sdk-" + VERSION)
    binary = destination / "linux-amd64/bin/rexglue"
    if not binary.is_file():
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as content:
            content.extractall(destination)
            for entry in content.infolist():
                mode = entry.external_attr >> 16 & 0o777
                if mode:
                    (destination / entry.filename).chmod(mode)
    print("Verified ReXGlue SDK:", binary)


if __name__ == "__main__":
    main()
