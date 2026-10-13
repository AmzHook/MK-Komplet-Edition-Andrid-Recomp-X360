#!/usr/bin/env python3
"""Attach verified function metadata only to the exact supported MK binary."""
import argparse
import json
from pathlib import Path
import re
import tomllib
from inspect_xex import inspect_xex_file

SUPPORTED_SHA256 = "e51751328751287b2a6113c815b99f470d1ea0c88f5d9d9f9b1942a28b3e7f6d"


def configure(manifest, xex):
    metadata = inspect_xex_file(xex)
    if metadata["execution_info"]["title_id"] != "575207FD":
        raise ValueError("The executable is not identified as MK.")
    if metadata["sha256"] != SUPPORTED_SHA256:
        print("Another MK binary: no version-specific function boundaries applied.")
        return
    source = manifest.read_text()
    configuration = tomllib.loads(source)
    entrypoint = configuration["entrypoint"]
    if (manifest.parent / entrypoint["file_path"]).resolve() != xex.resolve():
        raise ValueError("Manifest input differs from the supplied XEX.")
    include = str((Path(__file__).resolve().parent.parent / "native/mk9_functions.toml"))
    includes = list(entrypoint.get("includes", []))
    if include in includes:
        print("Verified MK function metadata already attached.")
        return
    includes.append(include)
    section = re.search(r"^\[entrypoint\]\s*\n(.*?)(?=^\[|\Z)", source, re.MULTILINE | re.DOTALL)
    if section is None:
        raise ValueError("Cannot safely locate the entrypoint section.")
    body = section.group(1)
    line = "includes = " + json.dumps(includes)
    matches = list(re.finditer(r"^includes\s*=\s*\[[^\n]*\]\s*$", body, re.MULTILINE))
    if "includes" in entrypoint and len(matches) != 1:
        raise ValueError("Preserving complex manifest formatting: attach the verified include manually.")
    if matches:
        match = matches[0]
        body = body[:match.start()] + line + "\n" + body[match.end():]
    else:
        body = line + "\n" + body
    updated = source[:section.start(1)] + body + source[section.end(1):]
    parsed = tomllib.loads(updated)
    if parsed["entrypoint"]["includes"] != includes:
        raise ValueError("Manifest update failed verification.")
    manifest.write_text(updated)
    print("Attached verified MK function intervals for the supported SHA-256.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("xex", type=Path)
    args = parser.parse_args()
    try:
        configure(args.manifest, args.xex)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, "Cannot configure MK manifest: " + str(error) + "\n")


if __name__ == "__main__":
    main()
