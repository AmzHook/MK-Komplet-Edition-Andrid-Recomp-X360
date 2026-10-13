#!/usr/bin/env bash
# This generates a native project; it does not compile a playable Android APK.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ "$#" -ne 1 ]; then
    echo 'Usage: bash tools/prepare_mk_codegen.sh /local/path/default.xex' >&2
    exit 2
fi
xex_path="$(realpath "$1")"
cd "$repo_root"
# Validate the title BEFORE creating a project or running code generation.
python3 tools/inspect_xex.py "$xex_path" --expected-title-id 575207FD
python3 tools/bootstrap_rexglue.py
tool_root="${MK_REXGLUE_TOOLS:-/workspace/.tools/rexglue}"
rexglue_bin="$tool_root/sdk-0.10.0/linux-amd64/bin/rexglue"
project_root="$repo_root/out/native/mk-komplete"
input_hash="$(sha256sum "$xex_path")"
if [ ! -e "$project_root/mk_komplete_manifest.toml" ]; then
    mkdir -p "$project_root"
    "$rexglue_bin" init --project-name mk_komplete --xex-path "$xex_path" --project-root "$project_root"
    printf '%s\n' "$input_hash" > "$project_root/.xex-input"
else
    if [ ! -f "$project_root/.xex-input" ] || [ "$(cat "$project_root/.xex-input")" != "$input_hash" ]; then
        echo 'Generated project uses another input. Preserve it and choose a separate project for another XEX.' >&2
        exit 2
    fi
fi
python3 tools/configure_mk_manifest.py "$project_root/mk_komplete_manifest.toml" "$xex_path"
"$rexglue_bin" --log-file "$project_root/codegen.log" codegen "$project_root/mk_komplete_manifest.toml"
printf 'Native code generation output: %s/generated/default\n' "$project_root"
echo 'Android runtime integration, native compilation and device validation are still required.'
