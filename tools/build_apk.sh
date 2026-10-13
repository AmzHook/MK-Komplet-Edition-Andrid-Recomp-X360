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
test -f "$android_jar" || { echo 'Run python3 tools/bootstrap_android.py first.' >&2; exit 1; }
mkdir -p out/classes out/dex out/apk
"$jdk_bin/javac" --release 11 -classpath "$android_jar" -d out/classes android/src/org/amzhook/mkimporter/*.java
"$jdk_bin/jar" --create --file out/classes.jar -C out/classes .
"$build_tools/d8" --min-api 26 --lib "$android_jar" --output out/dex out/classes.jar
"$build_tools/aapt2" link -I "$android_jar" --manifest android/AndroidManifest.xml --version-code 1 --version-name 0.1.0 -o out/apk/unsigned.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('out/apk/unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write('out/dex/classes.dex', 'classes.dex')
PY
"$build_tools/zipalign" -f -p 4 out/apk/unsigned.apk out/apk/aligned.apk
mkdir -p "$tool_root/signing"
if [ ! -f "$tool_root/signing/local-development.jks" ]; then
    "$jdk_bin/keytool" -genkeypair -keystore "$tool_root/signing/local-development.jks" -alias mk-development -keyalg RSA -keysize 3072 -validity 3650 -storepass android -keypass android -dname 'CN=MK Importador Local Development' -noprompt
fi
"$build_tools/apksigner" sign --ks "$tool_root/signing/local-development.jks" --ks-key-alias mk-development --ks-pass pass:android --key-pass pass:android --out out/apk/MK-Importador-0.1.0.apk out/apk/aligned.apk
"$build_tools/apksigner" verify --verbose out/apk/MK-Importador-0.1.0.apk
"$build_tools/aapt2" dump badging out/apk/MK-Importador-0.1.0.apk
(cd out/apk && sha256sum MK-Importador-0.1.0.apk > MK-Importador-0.1.0.apk.sha256)
printf 'APK: %s\n' "$repo_root/out/apk/MK-Importador-0.1.0.apk"
