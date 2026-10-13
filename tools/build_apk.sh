#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tool_root="${MK_ANDROID_TOOLS:-/workspace/.tools/mk-android}"
jdk_bin="$tool_root/jdk/bin"
build_tools="$tool_root/sdk/build-tools/35.0.0"
android_jar="$tool_root/sdk/platforms/android-35/android.jar"
export JAVA_HOME="$tool_root/jdk"
export PATH="$jdk_bin:$PATH"
cd "$repo_root"
variant="${1:-importer}"
if [ "$variant" != importer ] && [ "$variant" != --native ]; then
    echo 'Usage: bash tools/build_apk.sh [--native]' >&2
    exit 2
fi
test -f "$android_jar" || { echo 'Run python3 tools/bootstrap_android.py first.' >&2; exit 1; }
build_dir=out/importer
manifest=android/AndroidManifest.xml
min_api=26
apk_name=MK-Importador-0.1.0.apk
version_code=1
version_name=0.1.0
java_sources=(android/src/org/amzhook/mkimporter/MainActivity.java android/src/org/amzhook/mkimporter/ZipImporter.java android/src/org/amzhook/mkimporter/IsoImporter.java android/src/org/amzhook/mkimporter/GameImporter.java)
if [ "$variant" = --native ]; then
    build_dir=out/experimental
    manifest=android/AndroidManifest-native.xml
    min_api=28
    apk_name=MK-Experimental-0.1.6.apk
    version_code=7
    version_name=0.1.6
    sdk_root="${MK_NATIVE_TOOLS:-/workspace/.tools/mk-native}/rexglue-sdk"
    sdl_java_root="${MK_SDL_JAVA_ROOT:-$repo_root/vendor/sdl3/android/org/libsdl/app}"
    if [ ! -d "$sdl_java_root" ]; then
        sdl_java_root="$sdk_root/thirdparty/sdl3/android-project/app/src/main/java/org/libsdl/app"
    fi
    test -f "$sdl_java_root/SDLActivity.java" || { echo 'Matching SDL Java sources are missing.' >&2; exit 1; }
    test -f out/native/runtime.properties || { echo 'Native runtime metadata is missing; build native libraries first.' >&2; exit 1; }
    for library in libmk_komplete.so librexruntime.so librexgpu-xenos.so libc++_shared.so; do
        test -f "out/native/jniLibs/arm64-v8a/$library" || { echo "Native library is missing: $library" >&2; exit 1; }
    done
    java_sources+=(android/src/org/amzhook/mkimporter/GameActivity.java)
    for source in "$sdl_java_root"/*.java; do
        java_sources+=("$source")
    done
fi
rm -rf "$build_dir/classes" "$build_dir/dex"
mkdir -p "$build_dir/classes" "$build_dir/dex" out/apk
"$jdk_bin/javac" --release 11 -classpath "$android_jar" -d "$build_dir/classes" "${java_sources[@]}"
"$jdk_bin/jar" --create --file "$build_dir/classes.jar" -C "$build_dir/classes" .
"$build_tools/d8" --min-api "$min_api" --lib "$android_jar" --output "$build_dir/dex" "$build_dir/classes.jar"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$manifest" --version-code "$version_code" --version-name "$version_name" -o "$build_dir/unsigned.apk"
export MK_APK_BUILD_DIR="$build_dir" MK_APK_VARIANT="$variant"
python3 - <<'PY'
import os, zipfile
from pathlib import Path
build = Path(os.environ['MK_APK_BUILD_DIR'])
with zipfile.ZipFile(build/'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(build/'dex/classes.dex', 'classes.dex')
    if os.environ['MK_APK_VARIANT'] == '--native':
        apk.write('out/native/runtime.properties', 'assets/runtime.properties')
        for library in Path('out/native/jniLibs/arm64-v8a').glob('*.so'):
            apk.write(library, 'lib/arm64-v8a/' + library.name)
        notices = Path('out/native/notices')
        if not notices.is_dir(): raise SystemExit('Third-party license notices are missing.')
        for notice in notices.rglob('*'):
            if notice.is_file(): apk.write(notice, 'assets/thirdparty/' + str(notice.relative_to(notices)))
PY
"$build_tools/zipalign" -f -p 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
mkdir -p "$tool_root/signing"
if [ ! -f "$tool_root/signing/local-development.jks" ]; then
    "$jdk_bin/keytool" -genkeypair -keystore "$tool_root/signing/local-development.jks" -alias mk-development -keyalg RSA -keysize 3072 -validity 3650 -storepass android -keypass android -dname 'CN=MK Importador Local Development' -noprompt
fi
"$build_tools/apksigner" sign --ks "$tool_root/signing/local-development.jks" --ks-key-alias mk-development --ks-pass pass:android --key-pass pass:android --out "out/apk/$apk_name" "$build_dir/aligned.apk"
"$build_tools/apksigner" verify --verbose "out/apk/$apk_name"
"$build_tools/aapt2" dump badging "out/apk/$apk_name"
(cd out/apk && sha256sum "$apk_name" > "$apk_name.sha256")
printf 'APK: %s\n' "$repo_root/out/apk/$apk_name"
