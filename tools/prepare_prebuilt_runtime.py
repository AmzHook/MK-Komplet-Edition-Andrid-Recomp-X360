#!/usr/bin/env python3
"""Verify precompiled ARM64 runtime inputs and stage them for APK packaging."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import struct
import subprocess

REPO = Path(__file__).resolve().parent.parent
LIBRARIES = {'libmk_komplete.so', 'librexruntime.so', 'librexgpu-xenos.so', 'libc++_shared.so'}
SYSTEM_LIBRARIES = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libOpenSLES.so', 'libvulkan.so', 'libEGL.so', 'libGLESv2.so', 'libz.so'}
XEX_SHA256 = 'e51751328751287b2a6113c815b99f470d1ea0c88f5d9d9f9b1942a28b3e7f6d'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def verify(source):
    expected = {}
    for line in (source / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  (.+)', line)
        if not match:
            raise ValueError('Invalid checksum manifest line.')
        checksum, name = match.groups()
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or name in expected or name == 'SHA256SUMS':
            raise ValueError('Unsafe/duplicate checksum manifest path.')
        expected[name] = checksum
    actual = {path.relative_to(source).as_posix() for path in source.rglob('*') if path.is_file() and path.name != 'SHA256SUMS'}
    if actual != set(expected):
        raise ValueError('Prebuilt files differ from the complete checksum manifest.')
    for name, checksum in expected.items():
        path = source / name
        if path.is_symlink() or not path.resolve().is_relative_to(source.resolve()) or digest(path) != checksum:
            raise ValueError('Prebuilt file checksum/path failure: ' + name)
    properties = dict(line.split('=', 1) for line in (source / 'runtime.properties').read_text().splitlines() if '=' in line)
    if properties != {'xex_sha256': XEX_SHA256, 'title_id': '575207FD', 'status': 'experimental-unvalidated'}:
        raise ValueError('Incorrect runtime/XEX version metadata.')
    required = ['notices/LICENSE', 'notices/SOURCES.txt', 'notices/thirdparty/FFmpeg/COPYING.LGPLv2.1', 'notices/thirdparty/libmspack/libmspack/COPYING.LIB', 'runtime-sources.tar.xz']
    for name in required:
        if name not in expected:
            raise ValueError('Required source/license distribution file is missing: ' + name)
    directory = source / 'jniLibs/arm64-v8a'
    if {item.name for item in directory.iterdir()} != LIBRARIES:
        raise ValueError('Exactly four native libraries are required.')
    for name in sorted(LIBRARIES):
        path = directory / name
        with path.open('rb') as stream:
            header = stream.read(64)
        if header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', header, 16) != (3, 183):
            raise ValueError('Expected an AArch64 ELF64 shared library: ' + name)
        details = subprocess.check_output(['readelf', '-l', '-d', '--dyn-syms', '--wide', str(path)], text=True)
        loads = [line.split() for line in details.splitlines() if line.strip().startswith('LOAD ')]
        if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
            raise ValueError('Native LOAD segments must support 16 KiB pages: ' + name)
        needed = set(re.findall(r'\(NEEDED\).*\[(.*?)\]', details))
        if needed - LIBRARIES - SYSTEM_LIBRARIES:
            raise ValueError('Unpackaged native dependency in ' + name + ': ' + ', '.join(sorted(needed - LIBRARIES - SYSTEM_LIBRARIES)))
        if name == 'libmk_komplete.so' and not re.search(r'\bSDL_main\b', details):
            raise ValueError('Native game library does not export SDL_main.')
        if name == 'libmk_komplete.so' and not all(re.search(r'\b' + symbol + r'\b', details) for symbol in ['sub_829AEB48', 'sub_827EF090']):
            raise ValueError('Native game library lacks the observed 0.1.2 virtual-call corrections.')
        if name == 'librexruntime.so' and not all(symbol in details for symbol in ['JNI_OnLoad', 'Java_org_libsdl_app_SDLActivity_nativeRunMain']):
            raise ValueError('Runtime lacks SDL Java/JNI entry points.')
        if name == 'librexruntime.so' and b'guestThreadID=0x{:08X}' not in path.read_bytes():
            raise ValueError('Runtime lacks the 0.1.1 indirect-call diagnostics.')
        if name == 'librexruntime.so' and b'UNIMPLEMENTED calls=' not in path.read_bytes():
            raise ValueError('Runtime lacks the 0.1.2 bounded floating-point stub diagnostics.')
        print(f'Verified {name}: ARM64, 16 KiB LOAD alignment, packaged dependencies')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=REPO / 'native/prebuilt')
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    try:
        verify(args.source)
        if not args.verify_only:
            target = REPO / 'out/native'
            target.mkdir(parents=True, exist_ok=True)
            for directory in ['jniLibs', 'notices']:
                shutil.copytree(args.source / directory, target / directory, dirs_exist_ok=True)
            shutil.copyfile(args.source / 'runtime.properties', target / 'runtime.properties')
        print('Experimental precompiled runtime verified. Gameplay remains unvalidated.')
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
