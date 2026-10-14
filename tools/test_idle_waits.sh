#!/usr/bin/env bash
# Optional Linux x86_64 integration test; does not run guest code or build APKs.
# Prerequisites: bootstrap_native.py, bootstrap_rexglue.py, and g++ with C++23.
# --compare-baseline additionally compiles the pinned unpatched POSIX source.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
compare_baseline=false
case "${1:-}" in
    '') ;;
    --compare-baseline) compare_baseline=true ;;
    --help) echo 'Usage: bash tools/test_idle_waits.sh [--compare-baseline]'; exit 0 ;;
    *) echo 'Usage: bash tools/test_idle_waits.sh [--compare-baseline]' >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
    echo 'Usage: bash tools/test_idle_waits.sh [--compare-baseline]' >&2
    exit 2
fi
if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != x86_64 ]; then
    echo 'This optional test requires a Linux x86_64 host.' >&2
    exit 2
fi
native_root="${MK_NATIVE_TOOLS:-/workspace/.tools/mk-native}"
host_root="${MK_REXGLUE_TOOLS:-/workspace/.tools/rexglue}/sdk-0.10.0/linux-amd64"
sdk_root="$native_root/rexglue-sdk"
test_cxx="${MK_IDLE_TEST_CXX:-g++}"
if ! command -v "$test_cxx" > /dev/null; then
    echo 'Install a C++23 host compiler or set MK_IDLE_TEST_CXX to its path.' >&2
    exit 2
fi
if [ ! -f "$sdk_root/src/core/threading_posix.cpp" ] || [ ! -f "$host_root/lib/librexruntime.so" ]; then
    echo 'Prepare the SDKs with python3 tools/bootstrap_native.py and python3 tools/bootstrap_rexglue.py.' >&2
    exit 2
fi
if [ "$(git -C "$sdk_root" rev-parse HEAD)" != 38b40e7424e5d3ce1afa38dca10e9a84b1aa2317 ]; then
    echo 'This test requires the pinned MK SDK revision; preserving the existing checkout.' >&2
    exit 2
fi
git -C "$sdk_root" apply --reverse --check "$repo_root/native/patches/android-idle-waits.patch"
validation_dir="$repo_root/out/idle-waits-validation"
mkdir -p "$validation_dir"
compile_flags=(-std=c++23 -O2 -g0 -UNDEBUG -ffunction-sections -fdata-sections
    -DSPDLOG_COMPILED_LIB -DSPDLOG_FMT_EXTERNAL
    "-I$sdk_root/include" "-I$sdk_root/thirdparty/fmt/include"
    "-I$sdk_root/thirdparty/spdlog/include" -pthread)
"$test_cxx" "${compile_flags[@]}" -c tests/native_idle_waits_test.cpp \
    -o "$validation_dir/idle-wait-api-test.o"
compile_and_run() {
    local variant="$1" source_file="$2"
    "$test_cxx" "${compile_flags[@]}" -c "$source_file" \
        -o "$validation_dir/threading-posix-$variant.o"
    "$test_cxx" -Wl,--gc-sections "$validation_dir/idle-wait-api-test.o" \
        "$validation_dir/threading-posix-$variant.o" "-L$host_root/lib" \
        -lrexruntime "-Wl,-rpath,$host_root/lib" -pthread \
        -o "$validation_dir/idle-wait-api-$variant"
    echo "POSIX public API test: $variant"
    timeout 30s "$validation_dir/idle-wait-api-$variant" | tee "$validation_dir/api-$variant.log"
}
compile_and_run fixed "$sdk_root/src/core/threading_posix.cpp"
if "$compare_baseline"; then
    git -C "$sdk_root" show HEAD:src/core/threading_posix.cpp \
        > "$validation_dir/threading-posix-baseline.cpp"
    compile_and_run baseline "$validation_dir/threading-posix-baseline.cpp"
fi
