#!/usr/bin/env bash
# Packaging contracts using fake runtimes/tools; real runtime smoke tests run during release.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-packaging.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
source "$source_root/scripts/release-platform.sh"
source "$source_root/scripts/lib/checksum.sh"

for pair in 'Linux amd64 linux-x86_64' 'Linux aarch64 linux-arm64' 'Darwin x86_64 macos-x86_64' \
        'macOS x64 macos-x86_64' 'Mac_OS_X AMD64 macos-x86_64'; do
    read -r os arch expected <<< "$pair"
    select_release_platform "${os//_/ }" "$arch"
    [[ "$release_target" == "$expected" ]]
done
select_release_platform Linux arm64
[[ "$release_suffix" == linux-aarch64 ]]
for pair in 'Darwin arm64' 'Windows amd64' 'Linux riscv64'; do
    read -r os arch <<< "$pair"
    if select_release_platform "$os" "$arch" > "$fixture/error" 2>&1; then
        printf 'Unsupported release platform was accepted: %s\n' "$pair" >&2; exit 1
    fi
done

mkdir -p "$fixture/distribution/lib" "$fixture/distribution/licenses" "$fixture/runtime/bin" \
    "$fixture/jdk/bin" "$fixture/bin" "$fixture/output"
printf 'application\n' > "$fixture/distribution/lib/rilvune-2026.4.66.jar"
printf 'license\n' > "$fixture/distribution/licenses/LICENSE"
printf 'other platform\n' > "$fixture/output/rilvune-2026.4.66-abc1234-windows-x86_64.exe"
printf 'old version\n' > "$fixture/output/rilvune-2026.4.65-abc1234-linux-x86_64"
cat > "$fixture/runtime/bin/java" <<'EOF'
#!/bin/bash
set -eu
[[ "${FAIL_RUNTIME:-false}" != true ]] || exit 42
printf 'Rilvune [--workspace <directory>]\n'
EOF
chmod +x "$fixture/runtime/bin/java"
export REAL_UNAME="$(command -v uname)" PACKAGING_LOG="$fixture/commands"
cat > "$fixture/bin/uname" <<'EOF'
#!/bin/bash
case "$1" in
    -s) printf '%s\n' "$PACKAGING_OS" ;;
    -m) printf '%s\n' "$PACKAGING_ARCH" ;;
    *) exec "$REAL_UNAME" "$@" ;;
esac
EOF
cat > "$fixture/jdk/bin/jpackage" <<'EOF'
#!/bin/bash
set -eu
printf 'jpackage\n' >> "$PACKAGING_LOG"
printf '<%s>\n' "$@" >> "$PACKAGING_LOG"
while (( $# )); do
    case "$1" in
        --dest) destination="$2" ;;
        --input) input="$2" ;;
        --runtime-image) runtime="$2" ;;
        --app-version) [[ "$2" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ;;
    esac
    shift 2
done
app="$destination/Rilvune.app/Contents"
mkdir -p "$app/MacOS" "$app/app"
cp -R "$input/." "$app/app/"
cp -R "$runtime" "$app/runtime"
cat > "$app/MacOS/Rilvune" <<'LAUNCHER'
#!/bin/bash
set -eu
directory="$(cd "$(dirname "$0")/.." && pwd -P)"
exec "$directory/runtime/bin/java" "$@"
LAUNCHER
chmod +x "$app/MacOS/Rilvune"
EOF
cat > "$fixture/bin/ditto" <<'EOF'
#!/bin/bash
# Only verify ditto dispatch here; the macOS runner tests real ZIPs and native launchers.
set -eu
printf 'ditto\n' >> "$PACKAGING_LOG"
printf '<%s>\n' "$@" >> "$PACKAGING_LOG"
case "$1" in
    -c)
        [[ "$2 $3 $4" == '-k --sequesterRsrc --keepParent' ]]
        tar -czf "$6" -C "$(dirname "$5")" "$(basename "$5")"
        ;;
    -x)
        [[ "$2" == -k ]]
        mkdir -p "$4"
        tar -xzf "$3" -C "$4"
        ;;
    *) exit 1 ;;
esac
EOF
chmod +x "$fixture/bin/"* "$fixture/jdk/bin/jpackage"
export PATH="$fixture/bin:$PATH" PACKAGING_ARCH=x86_64
package() {
    bash "$source_root/scripts/package-release.sh" "$fixture/distribution" "$fixture/runtime" \
        "$fixture/output" "${2:-2026.4.66-abc1234}" "$fixture/jdk" "$1"
}
verify_artifact() {
    local name="$1" recorded filename
    [[ -f "$fixture/output/$name" && -f "$fixture/output/$name.sha256" ]]
    read -r recorded filename < "$fixture/output/$name.sha256"
    [[ "$recorded" == "$(file_sha256 "$fixture/output/$name")" && "$filename" == "$name" ]]
}

# Linux self-extraction requires GNU tar; exercise it only on Linux, not with BSD tools.
if [[ "$("$REAL_UNAME" -s)" == Linux ]]; then
    export PACKAGING_OS=Linux
    package linux-x86_64
    verify_artifact rilvune-2026.4.66-abc1234-linux-x86_64
fi
export PACKAGING_OS=Darwin
package macos-x86_64
readonly macos_artifact=rilvune-2026.4.66-abc1234-macos-x86_64.zip
verify_artifact "$macos_artifact"
grep -F '<dev.rilvune.app>' "$PACKAGING_LOG" >/dev/null
grep -F '<rilvune-2026.4.66.jar>' "$PACKAGING_LOG" >/dev/null
grep -F '<-Dfile.encoding=UTF-8>' "$PACKAGING_LOG" >/dev/null
package macos-x86_64 2026.4.66.1-abc1234
verify_artifact rilvune-2026.4.66.1-abc1234-macos-x86_64.zip
before="$(file_sha256 "$fixture/output/$macos_artifact")"
if bash "$source_root/scripts/package-release.sh" "$fixture/distribution" "$fixture/runtime" \
        "$fixture/output" 2026.4.66-abc1234 "$fixture/missing-jdk" macos-x86_64 > "$fixture/error" 2>&1; then
    printf 'Packaging unexpectedly accepted a missing JDK.\n' >&2; exit 1
fi
if FAIL_RUNTIME=true package macos-x86_64 > "$fixture/error" 2>&1; then
    printf 'Failed smoke test unexpectedly published an artifact.\n' >&2; exit 1
fi
[[ "$before" == "$(file_sha256 "$fixture/output/$macos_artifact")" ]]
verify_artifact "$macos_artifact"
if package linux-x86_64 > "$fixture/error" 2>&1; then
    printf 'Cross-packaging unexpectedly succeeded.\n' >&2; exit 1
fi
grep -F 'does not match the build host' "$fixture/error" >/dev/null
[[ "$(cat "$fixture/output/rilvune-2026.4.66-abc1234-windows-x86_64.exe")" == 'other platform' ]]
[[ "$(cat "$fixture/output/rilvune-2026.4.65-abc1234-linux-x86_64")" == 'old version' ]]
[[ -z "$(find "$fixture" -maxdepth 1 -name '.package.*' -print)" ]]

# The checksum helper must work with only the macOS tool in PATH.
mkdir -p "$fixture/checksum-bin"
cat > "$fixture/checksum-bin/shasum" <<'EOF'
#!/bin/bash
[[ "$1 $2" == '-a 256' && -f "$3" ]] || exit 1
printf '%s  %s\n' "$EXPECTED_CHECKSUM" "$3"
EOF
chmod +x "$fixture/checksum-bin/shasum"
export EXPECTED_CHECKSUM="$before"
[[ "$(PATH="$fixture/checksum-bin" file_sha256 "$fixture/output/$macos_artifact")" == "$before" ]]
printf 'Packaging regression tests passed.\n'
