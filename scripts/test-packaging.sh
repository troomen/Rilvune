#!/usr/bin/env bash
# Verify the universal artifact and launcher on all OS/CPU routes with isolated runtimes.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-packaging.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
source "$source_root/scripts/lib/checksum.sh"
mkdir -p "$fixture/distribution/lib" "$fixture/distribution/licenses" "$fixture/distribution/branding" \
    "$fixture/bin" "$fixture/output" "$fixture/unpack"
printf 'application\n' > "$fixture/distribution/lib/rilvune-2026.4.66.jar"
printf 'license\n' > "$fixture/distribution/licenses/HACK-LICENSE.md"
printf 'icon\n' > "$fixture/distribution/branding/Rilvune.icns"
printf 'old release\n' > "$fixture/output/previous.tar.gz"
export REAL_UNAME="$(command -v uname)" LAUNCH_LOG="$fixture/launch"
cat > "$fixture/bin/uname" <<'UNAME'
#!/bin/bash
case "$1" in
    -s) printf '%s\n' "${PACKAGING_OS:-Linux}" ;;
    -m) printf '%s\n' "${PACKAGING_ARCH:-x86_64}" ;;
    *) exec "$REAL_UNAME" "$@" ;;
esac
UNAME
chmod +x "$fixture/bin/uname"
for platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
    mkdir -p "$fixture/runtimes/$platform/bin" "$fixture/runtimes/$platform/legal"
    printf 'JAVA_VERSION="25.0.5"\n' > "$fixture/runtimes/$platform/release"
    cat > "$fixture/runtimes/$platform/bin/java" <<'JAVA'
#!/bin/bash
set -eu
[[ "${FAIL_RUNTIME:-false}" != true ]] || exit 42
printf '%s\n' "$0" > "$LAUNCH_LOG"
printf '<%s>\n' "$@" >> "$LAUNCH_LOG"
printf 'Rilvune [--workspace <directory>]\n'
JAVA
    chmod +x "$fixture/runtimes/$platform/bin/java"
done
export PATH="$fixture/bin:$PATH" PACKAGING_OS=Linux PACKAGING_ARCH=x86_64
package() {
    bash "$source_root/scripts/package-release.sh" "$fixture/distribution" "$fixture/runtimes" \
        "$fixture/output" "${1:-2026.4.66-abc1234}"
}
package
readonly name=rilvune-2026.4.66-abc1234.tar.gz
read -r recorded filename < "$fixture/output/$name.sha256"
[[ "$recorded" == "$(file_sha256 "$fixture/output/$name")" && "$filename" == "$name" ]]
tar -xzf "$fixture/output/$name" -C "$fixture/unpack"
readonly app="$fixture/unpack/Rilvune"
for pair in 'Linux amd64 linux-x86_64' 'Linux aarch64 linux-arm64' 'Darwin x86_64 macos-x86_64' 'Darwin arm64 macos-arm64'; do
    read -r PACKAGING_OS PACKAGING_ARCH expected <<< "$pair"
    export PACKAGING_OS PACKAGING_ARCH
    "$app/rilvune" --workspace 'project with spaces' --profile 'profile with spaces' > /dev/null
    grep -F "/runtime/$expected/bin/java" "$LAUNCH_LOG" > /dev/null
    grep -F '<project with spaces>' "$LAUNCH_LOG" > /dev/null
    grep -F '<profile with spaces>' "$LAUNCH_LOG" > /dev/null
    if [[ "$PACKAGING_OS" == Darwin ]]; then grep -F '<-Xdock:name=Rilvune>' "$LAUNCH_LOG" > /dev/null; fi
    # Missing native runtime must fail, never accidentally execute another architecture.
    mv "$app/Rilvune.app/Contents/runtime/$expected/bin/java" "$fixture/moved-java"
    if "$app/rilvune" --help > "$fixture/error" 2>&1; then exit 1; fi
    grep -F 'bundled Java' "$fixture/error" > /dev/null
    mv "$fixture/moved-java" "$app/Rilvune.app/Contents/runtime/$expected/bin/java"
done
# An unrecognized platform can still use a supplied Java; the application owns feature support.
export PACKAGING_OS=FreeBSD PACKAGING_ARCH=riscv64
JAVA_HOME="$fixture/runtimes/linux-x86_64" "$app/rilvune" --help > /dev/null
# Packaging always produces the same complete artifact, even when built on a Mac.
export PACKAGING_OS=Darwin PACKAGING_ARCH=arm64
package
before="$(file_sha256 "$fixture/output/$name")"
package 2026.4.66.1-abc1234
tar -xzOf "$fixture/output/rilvune-2026.4.66.1-abc1234.tar.gz" Rilvune/Rilvune.app/Contents/Info.plist > "$fixture/plist"
grep -F '<key>CFBundleShortVersionString</key><string>2026.4.66</string>' "$fixture/plist" >/dev/null
grep -F '<key>CFBundleVersion</key><string>2026.4.66</string>' "$fixture/plist" >/dev/null
grep -F '<key>RilvuneReleaseVersion</key><string>2026.4.66.1-abc1234</string>' "$fixture/plist" >/dev/null
if FAIL_RUNTIME=true package > "$fixture/error" 2>&1; then exit 1; fi
[[ "$before" == "$(file_sha256 "$fixture/output/$name")" ]]
rm "$fixture/runtimes/linux-arm64/bin/java"
if package > "$fixture/error" 2>&1; then exit 1; fi
grep -F 'Missing Java runtime: linux-arm64' "$fixture/error" > /dev/null
[[ "$before" == "$(file_sha256 "$fixture/output/$name")" ]]
[[ "$(cat "$fixture/output/previous.tar.gz")" == 'old release' ]]
[[ -z "$(find "$fixture" -maxdepth 1 -name '.package.*' -print)" ]]
# The checksum helper also works on macOS, where only shasum is supplied.
mkdir -p "$fixture/checksum-bin"
cat > "$fixture/checksum-bin/shasum" <<'SHASUM'
#!/bin/bash
[[ "$1 $2" == '-a 256' && -f "$3" ]] || exit 1
printf '%s  %s\n' "$EXPECTED_CHECKSUM" "$3"
SHASUM
chmod +x "$fixture/checksum-bin/shasum"
export EXPECTED_CHECKSUM="$before"
[[ "$(PATH="$fixture/checksum-bin" file_sha256 "$fixture/output/$name")" == "$before" ]]
printf 'Universal packaging regression tests passed.\n'
