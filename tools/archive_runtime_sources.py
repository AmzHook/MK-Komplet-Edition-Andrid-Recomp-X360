#!/usr/bin/env python3
"""Archive exact public SDK dependency sources and the local Android patches.

Uses only Git-tracked SDK/submodule files and the explicit new fiber assembly;
never includes tool caches, build outputs, generated MK code or game files.
"""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile

from bootstrap_native import PATCHES
from bootstrap_adrenotools import SOURCES as ADRENO_SOURCES

PIN = '38b40e7424e5d3ce1afa38dca10e9a84b1aa2317'
REPO = Path(__file__).resolve().parent.parent


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('MK_NATIVE_TOOLS', '/workspace/.tools/mk-native')) / 'rexglue-sdk')
    parser.add_argument('--output', type=Path, default=REPO / 'native/prebuilt/runtime-sources.tar.xz')
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    if git(sdk, 'rev-parse', 'HEAD').decode().strip() != PIN:
        parser.error('SDK checkout does not match the published source pin.')
    roots = [sdk]
    pins = {'SDK': PIN}
    for line in git(sdk, 'submodule', 'status', '--recursive').decode().splitlines():
        if line.startswith('-'):
            continue  # Optional CLI-only dependencies are not built into runtime.
        fields = line[1:].split()
        if line[0] != ' ':
            parser.error('Submodule differs from its pinned revision: ' + fields[1])
        root = (sdk / fields[1]).resolve()
        if not root.is_relative_to(sdk):
            parser.error('Submodule escapes the source tree.')
        roots.append(root)
        pins[fields[1]] = fields[0]
    files = {}
    for root in roots:
        for relative in git(root, 'ls-files', '-z').decode().split('\0'):
            if not relative:
                continue
            path = root / relative
            if path.is_file() or path.is_symlink():
                name = path.relative_to(sdk).as_posix()
                # These bundled Windows CLI tools are not inputs to the
                # Android runtime build. Corresponding runtime sources and
                # their pinned submodules remain in the archive.
                if name.startswith('tools/binutils/'):
                    continue
                if path.suffix.lower() in {'.exe', '.dll', '.lib', '.so', '.o', '.a'}:
                    continue
                if path.suffix.lower() in {'.xex', '.iso'} or any(p in {'.git', 'out', 'generated'} for p in Path(name).parts):
                    parser.error('Unexpected build/game file in tracked SDK source: ' + name)
                files[name] = path
    adreno = sdk.parent / 'adrenotools'
    for repo, revision, checksum, relative in ADRENO_SOURCES:
        root = sdk.parent / relative
        if not (root / '.source-sha256').is_file() or (root / '.source-sha256').read_text().strip() != checksum:
            parser.error('Verified AdrenoTools sources are missing: ' + repo)
        pins[repo] = {'commit': revision, 'archive_sha256': checksum}
    for path in adreno.rglob('*'):
        if path.is_file() and path.name != '.source-sha256':
            if path.suffix.lower() in {'.so', '.a', '.o', '.xex', '.iso', '.exe', '.dll'}:
                parser.error('Unexpected binary in AdrenoTools sources: ' + str(path))
            files['thirdparty/adrenotools/' + path.relative_to(adreno).as_posix()] = path
    for name in ['src/core/fiber_android_aarch64.S', 'include/rex/kernel/xboxkrnl/io.h']:
        added = sdk / name
        if not added.is_file():
            parser.error('Required patched runtime source is missing: ' + name)
        files[name] = added
    for patch in PATCHES:
        if not patch.is_file():
            parser.error('Required runtime source patch is missing: ' + patch.name)
        if subprocess.run(['git', '-C', str(sdk), 'apply', '--reverse', '--check', str(patch)], capture_output=True).returncode:
            parser.error('Required runtime patch is not applied to these sources: ' + patch.name)
        files['local-patches/' + patch.name] = patch
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.partial')
    with tarfile.open(temporary, 'w:xz', preset=3) as archive:
        for name, source in sorted(files.items()):
            item = archive.gettarinfo(str(source), arcname='runtime-sdk/' + name)
            item.uid = item.gid = item.mtime = 0
            item.uname = item.gname = ''
            if item.issym() and not (source.parent / item.linkname).resolve().is_relative_to(sdk):
                parser.error('Source symlink escapes SDK.')
            if item.isfile():
                with source.open('rb') as stream:
                    archive.addfile(item, stream)
            else:
                archive.addfile(item)
        data = (json.dumps(pins, indent=2, sort_keys=True) + '\n').encode()
        item = tarfile.TarInfo('runtime-sdk/SOURCE-PINS.json')
        item.size = len(data); item.mode = 0o644
        archive.addfile(item, io.BytesIO(data))
    temporary.replace(args.output)
    print(f'Public runtime sources: {args.output} ({args.output.stat().st_size:,} bytes, {len(files)} files)')


if __name__ == '__main__':
    main()
