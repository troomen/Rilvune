#!/usr/bin/env bash
# Package an installed distribution and jlink runtime as one Linux executable.
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { printf 'Linux releases must be built on Linux.\n' >&2; exit 1; }
readonly script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly distribution="$(realpath -- "$1")"
readonly runtime="$(realpath -- "$2")"
mkdir -p -- "$3"
readonly output_root="$(realpath -- "$3")"
readonly version="$4"
[[ "$version" =~ ^[a-zA-Z0-9._-]+$ ]] || { printf 'Invalid release version.\n' >&2; exit 1; }
readonly architecture="$(uname -m)"
readonly output="$output_root/rilvune-$version-linux-$architecture"
readonly staging="$(mktemp -d "$output_root/.package.XXXXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
mkdir -p "$staging/app"
cp -a -- "$distribution/lib" "$distribution/licenses" "$staging/app/"
cp -a -- "$runtime" "$staging/app/runtime"
# Keep Java's own legal notices and release metadata inside the runtime.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
    -C "$staging/app" -czf "$staging/payload.tar.gz" .
payload_sha256="$(sha256sum "$staging/payload.tar.gz")"
payload_sha256="${payload_sha256%% *}"
sed -e "s/@PAYLOAD_SHA256@/$payload_sha256/g" -e "s/@ARCHITECTURE@/$architecture/g" \
    "$script_root/release-launcher.sh" > "$staging/executable"
cat "$staging/payload.tar.gz" >> "$staging/executable"
chmod 755 "$staging/executable"
bash "$script_root/test-release.sh" "$staging/executable"
mv -f -- "$staging/executable" "$output"
(cd -- "$output_root" && sha256sum "${output##*/}" > "${output##*/}.sha256")
printf 'Standalone release: %s\n' "$output"
