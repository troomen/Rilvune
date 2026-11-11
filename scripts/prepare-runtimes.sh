#!/usr/bin/env bash
# Build-only: obtain every runtime once, verify pins, then stage the complete set.
set -euo pipefail
readonly script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_root/lib/checksum.sh"
mkdir -p -- "${1:?Runtime archive cache}" "$(dirname -- "${2:?Runtime output directory}")"
readonly cache="$(cd -- "$1" && pwd -P)"
readonly output="$(cd -- "$(dirname -- "$2")" && pwd -P)/${2##*/}"
readonly staging="$(mktemp -d "$(dirname -- "$output")/.runtimes.XXXXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
while read -r platform url checksum java_home; do
    [[ -n "$platform" && "$platform" != \#* ]] || continue
    [[ "$platform" =~ ^(linux|macos)-(x86_64|arm64)$ && "$checksum" =~ ^[a-f0-9]{64}$ && "$url" == https://* ]] \
        || { printf 'Invalid Java runtime manifest entry: %s\n' "$platform" >&2; exit 1; }
    archive="$cache/$checksum.tar.gz"
    if [[ ! -f "$archive" ]] || [[ "$(file_sha256 "$archive")" != "$checksum" ]]; then
        printf 'Downloading Java for %s…\n' "$platform"
        curl --fail --location --show-error --retry 3 --connect-timeout 20 --max-time 600 \
            --proto '=https' --proto-redir '=https' "$url" -o "$staging/download.tar.gz"
        [[ "$(file_sha256 "$staging/download.tar.gz")" == "$checksum" ]] \
            || { printf 'Java archive checksum mismatch for %s.\n' "$platform" >&2; exit 1; }
        mv -f -- "$staging/download.tar.gz" "$archive"
    fi
    printf 'Staging verified Java for %s…\n' "$platform"
    mkdir -p "$staging/unpack" "$staging/runtimes/$platform"
    tar -xzf "$archive" -C "$staging/unpack"
    [[ -x "$staging/unpack/$java_home/bin/java" && -f "$staging/unpack/$java_home/release" && -d "$staging/unpack/$java_home/legal" ]] \
        || { printf 'Incomplete Java runtime for %s.\n' "$platform" >&2; exit 1; }
    cp -R "$staging/unpack/$java_home/." "$staging/runtimes/$platform/"
    rm -rf -- "$staging/unpack"
done < "$script_root/java-runtimes.txt"
# Leave the previous complete set intact if any download or extraction fails.
rm -rf -- "$output"
mv -- "$staging/runtimes" "$output"
