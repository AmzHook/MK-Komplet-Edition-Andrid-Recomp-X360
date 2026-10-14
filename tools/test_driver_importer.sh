#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tool_root="${MK_ANDROID_TOOLS:-/workspace/.tools/mk-android}"
cd "$repo_root"
mkdir -p out/tests/drivers
"$tool_root/jdk/bin/javac" --release 11 -Xlint:all -d out/tests/drivers \
    android/src/org/amzhook/mkimporter/DriverManager.java \
    android/test/org/amzhook/mkimporter/DriverManagerTest.java
"$tool_root/jdk/bin/java" -cp out/tests/drivers org.amzhook.mkimporter.DriverManagerTest
