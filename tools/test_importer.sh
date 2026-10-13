#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tool_root="${MK_ANDROID_TOOLS:-/workspace/.tools/mk-android}"
cd "$repo_root"
mkdir -p out/tests
"$tool_root/jdk/bin/javac" --release 11 -d out/tests \
    android/src/org/amzhook/mkimporter/ZipImporter.java \
    android/src/org/amzhook/mkimporter/IsoImporter.java \
    android/src/org/amzhook/mkimporter/GameImporter.java \
    tests/ZipImporterTest.java tests/IsoImporterTest.java tests/GameImporterTest.java
"$tool_root/jdk/bin/java" -cp out/tests ZipImporterTest
"$tool_root/jdk/bin/java" -cp out/tests IsoImporterTest
"$tool_root/jdk/bin/java" -cp out/tests GameImporterTest
