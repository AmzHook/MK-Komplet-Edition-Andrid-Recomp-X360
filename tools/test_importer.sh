#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tool_root="${MK_ANDROID_TOOLS:-/workspace/.tools/mk-android}"
cd "$repo_root"
mkdir -p out/tests
"$tool_root/jdk/bin/javac" --release 11 -d out/tests android/src/org/amzhook/mkimporter/ZipImporter.java tests/ZipImporterTest.java
"$tool_root/jdk/bin/java" -cp out/tests ZipImporterTest
