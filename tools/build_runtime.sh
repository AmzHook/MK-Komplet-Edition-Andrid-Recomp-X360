#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
native_root="${MK_NATIVE_TOOLS:-/workspace/.tools/mk-native}"
ndk_root="$native_root/sdk/ndk/28.2.13676358"
cmake_bin="$native_root/sdk/cmake/3.31.6/bin/cmake"
ninja_bin="$native_root/sdk/cmake/3.31.6/bin/ninja"
sdk_root="$native_root/rexglue-sdk"
test -x "$cmake_bin" || { echo 'Run python3 tools/bootstrap_native.py first.' >&2; exit 1; }
"$cmake_bin" -S "$sdk_root" -B "$native_root/build-android-arm64" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DREXGLUE_ENABLE_TRACY=OFF -DREXGLUE_BUILD_TESTS=OFF \
    -DREXGLUE_USE_VULKAN=ON -DREXGLUE_USE_D3D12=OFF
"$cmake_bin" --build "$native_root/build-android-arm64" --target rexruntime rexgpu-xenos --parallel "${MK_BUILD_JOBS:-3}"
