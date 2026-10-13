#!/usr/bin/env python3
"""Check that the signed APK contains exactly the staged runtime and notices."""
import argparse
import hashlib
from pathlib import Path
import zipfile

REPO = Path(__file__).resolve().parent.parent


def digest(stream):
    result = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        result.update(chunk)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, nargs='?', default=REPO / 'out/apk/MK-Experimental-0.1.6.apk')
    args = parser.parse_args()
    staged = REPO / 'out/native'
    try:
        with zipfile.ZipFile(args.apk) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)) or any(Path(name).suffix.lower() in {'.xex', '.iso'} for name in names):
                raise ValueError('Duplicate APK entries or game data detected.')
            required = {'assets/runtime.properties': staged / 'runtime.properties'}
            required.update({'lib/arm64-v8a/' + path.name: path for path in (staged / 'jniLibs/arm64-v8a').glob('*.so')})
            required.update({'assets/thirdparty/' + path.relative_to(staged / 'notices').as_posix(): path for path in (staged / 'notices').rglob('*') if path.is_file()})
            native_names = {name for name in names if name.startswith(('lib/', 'assets/thirdparty/')) or name == 'assets/runtime.properties'}
            if native_names != set(required):
                raise ValueError('APK native libraries/metadata/notices differ from staged files.')
            for name, path in required.items():
                with archive.open(name) as packaged, path.open('rb') as original:
                    if digest(packaged) != digest(original):
                        raise ValueError('Packaged content differs from staged input: ' + name)
            dex = archive.read('classes.dex')
            for descriptor in [b'Lorg/amzhook/mkimporter/GameActivity;', b'Lorg/libsdl/app/SDLActivity;', b'Lorg/amzhook/mkimporter/MainActivity;', b'Lorg/amzhook/mkimporter/IsoImporter;', b'Lorg/amzhook/mkimporter/GameImporter;']:
                if descriptor not in dex:
                    raise ValueError('Required Android class is absent from DEX.')
        print(f'Verified APK payload: 4 ARM64 libraries, matching XEX metadata, SDL activity and {len(required) - 5} license/source notices. No XEX/ISO packaged.')
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
