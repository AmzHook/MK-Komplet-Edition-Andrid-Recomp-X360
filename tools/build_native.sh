#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ "$#" -ne 1 ]; then
    echo 'Usage: bash tools/build_native.sh /local/path/default.xex' >&2
    exit 2
fi
xex_path="$(realpath "$1")"
cd "$repo_root"
python3 tools/inspect_xex.py "$xex_path" --expected-title-id 575207FD > /dev/null
input_hash="$(sha256sum "$xex_path")"
project_root="$repo_root/out/native/mk-komplete"
if [ ! -f "$project_root/.xex-input" ] || [ "$(cat "$project_root/.xex-input")" != "$input_hash" ]; then
    echo 'The supplied XEX does not match this generated project. Run prepare_mk_codegen.sh first.' >&2
    exit 2
fi
test -f "$project_root/generated/default/sources.cmake" || { echo 'MK code generation has not completed.' >&2; exit 1; }
python3 tools/bootstrap_native.py
bash tools/build_runtime.sh
native_root="${MK_NATIVE_TOOLS:-/workspace/.tools/mk-native}"
cmake_bin="$native_root/sdk/cmake/3.31.6/bin/cmake"
ninja_bin="$native_root/sdk/cmake/3.31.6/bin/ninja"
ndk_root="$native_root/sdk/ndk/28.2.13676358"
sdk_root="$native_root/rexglue-sdk"
build_root="$repo_root/out/native/build-arm64"
"$cmake_bin" -S native -B "$build_root" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
    -DSDK_ROOT="$sdk_root" -DMK_GENERATED_DIR="$project_root/generated/default" \
    -DMK_SDK_BUILD_DIR="$native_root/build-android-arm64" -DMK_REUSE_SDK_RUNTIME=ON
"$cmake_bin" --build "$build_root" --target mk_komplete --parallel "${MK_BUILD_JOBS:-3}"
mkdir -p out/native/jniLibs/arm64-v8a
for library in libmk_komplete.so librexruntime.so librexgpu-xenos.so; do
    test -f "$build_root/lib/$library"
    cp "$build_root/lib/$library" out/native/jniLibs/arm64-v8a/
done
cp "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" out/native/jniLibs/arm64-v8a/
"$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded out/native/jniLibs/arm64-v8a/*.so
export MK_NATIVE_SOURCE_ROOT="$sdk_root" MK_NATIVE_XEX_PATH="$xex_path" MK_NATIVE_NDK_ROOT="$ndk_root"
python3 - <<'PY'
import hashlib, os, shutil
from pathlib import Path
xex = Path(os.environ['MK_NATIVE_XEX_PATH'])
digest = hashlib.sha256(xex.read_bytes()).hexdigest()
Path('out/native/runtime.properties').write_text('xex_sha256=' + digest + '\ntitle_id=575207FD\nstatus=experimental-unvalidated\n')
source = Path(os.environ['MK_NATIVE_SOURCE_ROOT'])
notices = Path('out/native/notices'); notices.mkdir(exist_ok=True)
for item in source.rglob('*'):
    if not item.is_file() or '.git' in item.parts or 'out' in item.parts:
        continue
    if item.name.lower().startswith(('license', 'copying', 'notice')):
        relative = item.relative_to(source)
        target = notices / relative; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
(notices / 'SOURCES.txt').write_text(
    'ReXGlue Android runtime source: https://github.com/PauloUbirajara/asura-wrath-rexglue-sdk\n'
    'Pinned commit: 38b40e7424e5d3ce1afa38dca10e9a84b1aa2317\n'
    'Submodule sources and commit pins: .gitmodules and gitlinks in that SDK.\n'
    'Local SDK changes: native/patches/android-fiber.patch, indirect-call-diagnostics.patch, floating-point-stub-logging.patch.\n'
    'Original Android wrapper and build scripts: AmzHook/MK-Komplet-Edition-Andrid-Recomp-X360.\n'
    'No XEX, ISO or game assets are distributed; the APK includes title-specific compiled code.\n'
    'Exact corresponding public runtime/dependency sources: native/prebuilt/runtime-sources.tar.xz.\n'
    'Rebuild runtime without game files: python3 tools/bootstrap_native.py; bash tools/build_runtime.sh.\n'
    'Replace compatible runtime libraries in out/native/jniLibs/arm64-v8a, then build_apk.sh --native.\n')
for notice in Path(os.environ['MK_NATIVE_NDK_ROOT']).glob('NOTICE*'):
    if notice.is_file(): shutil.copyfile(notice, notices / ('NDK-' + notice.name))
PY
echo 'Native libraries prepared. Build the experimental APK with bash tools/build_apk.sh --native.'
