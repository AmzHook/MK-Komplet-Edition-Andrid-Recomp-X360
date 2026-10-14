#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
mkdir -p out/native/host-tests
"${CXX:-c++}" -std=c++17 -pthread -Wall -Wextra -Werror -I native \
    tests/native_file_trace_test.cpp -o out/native/host-tests/file_trace_test
out/native/host-tests/file_trace_test
