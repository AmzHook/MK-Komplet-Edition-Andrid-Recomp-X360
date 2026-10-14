#!/usr/bin/env python3
"""Build pinned, checksum-verified AdrenoTools and its namespace helper."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(os.environ.get('MK_NATIVE_TOOLS', '/workspace/.tools/mk-native'))
SOURCES = (
    ('libadrenotools', '8fae8ce254dfc1344527e05301e43f37dea2df80', 'ceffce971676d4cfdf348a082df06fc92a1dca6d95bea892a480d63f200961cb', 'adrenotools'),
    ('liblinkernsbypass', 'aa3975893d83ef1bc84c321ec60c65fbf1287887', 'da1128c8aa771c4d24766b53a47822d0717baa5c536ca8491220402942b80638', 'adrenotools/lib/linkernsbypass'),
)

def prepare_sources():
    downloads = ROOT / 'adreno-downloads'; downloads.mkdir(parents=True, exist_ok=True)
    for repo, revision, checksum, relative in SOURCES:
        archive = downloads / (repo + '.tar.gz')
        if not archive.is_file() or hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
            url = f'https://codeload.github.com/bylaws/{repo}/tar.gz/{revision}'
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(2 * 1024 * 1024)
            if hashlib.sha256(data).hexdigest() != checksum:
                raise RuntimeError('AdrenoTools source checksum mismatch: ' + repo)
            archive.write_bytes(data)
        destination = ROOT / relative
        marker = destination / '.source-sha256'
        if marker.is_file() and marker.read_text().strip() == checksum:
            continue
        if destination.exists() and any(destination.iterdir()):
            raise RuntimeError('Preserving an unverified or modified source directory: ' + str(destination))
        scratch = downloads / (repo + '.extract')
        if scratch.exists(): shutil.rmtree(scratch)
        scratch.mkdir()
        with tarfile.open(archive, 'r:gz') as package:
            members = package.getmembers()
            prefix = repo + '-' + revision + '/'
            for member in members:
                if member.isdir() and member.name == prefix[:-1]: continue
                if not member.name.startswith(prefix) or not (member.isdir() or member.isfile()):
                    raise RuntimeError('Unsafe AdrenoTools source archive member')
                relative_path = Path(member.name[len(prefix):])
                if relative_path.is_absolute() or '..' in relative_path.parts:
                    raise RuntimeError('Unsafe AdrenoTools source path')
                output = scratch / relative_path
                if member.isdir(): output.mkdir(parents=True, exist_ok=True)
                else:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with package.extractfile(member) as source, output.open('wb') as target:
                        shutil.copyfileobj(source, target)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists(): destination.rmdir()
        scratch.rename(destination)
        marker.write_text(checksum + '\n')
    return ROOT / 'adrenotools'

def main():
    source = prepare_sources()
    sdk = ROOT / 'sdk'
    cmake = sdk / 'cmake/3.31.6/bin/cmake'
    ninja = sdk / 'cmake/3.31.6/bin/ninja'
    build = ROOT / 'build-adrenotools-arm64'
    output = build / 'lib'
    subprocess.run([str(cmake), '-S', str(source), '-B', str(build), '-G', 'Ninja',
        '-DCMAKE_MAKE_PROGRAM=' + str(ninja),
        '-DCMAKE_TOOLCHAIN_FILE=' + str(sdk / 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-28', '-DANDROID_STL=c++_shared',
        '-DBUILD_SHARED_LIBS=ON', '-DCMAKE_BUILD_TYPE=Release',
        '-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=' + str(output),
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'], check=True)
    subprocess.run([str(cmake), '--build', str(build), '--parallel', os.environ.get('MK_BUILD_JOBS', '3')], check=True)
    print('Verified AdrenoTools source and Android libraries:', output)

if __name__ == '__main__': main()
