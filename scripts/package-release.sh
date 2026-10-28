#!/usr/bin/env bash
# Shared release staging and validation; platform implementations only assemble artifacts.
set -euo pipefail
readonly script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_root/release-platform.sh"
source "$script_root/lib/checksum.sh"
select_release_platform
# Reject accidental cross-packaging (for example an ARM JVM with an Intel artifact name).
[[ "$release_target" == "${6:?Expected the Gradle JVM release target}" ]] \
    || { printf 'Release target %s does not match the build host %s. Build on a matching host/JDK.\n' "$6" "$release_target" >&2; exit 1; }
source "$script_root/release/$release_packager.sh"
distribution="$(cd -- "$1" && pwd -P)"
runtime="$(cd -- "$2" && pwd -P)"
jdk="$(cd -- "$5" && pwd -P)"
readonly distribution runtime jdk
mkdir -p -- "$3"
output_root="$(cd -- "$3" && pwd -P)"
readonly output_root
readonly version="$4"
[[ "$version" =~ ^[a-zA-Z0-9._-]+$ ]] || { printf 'Invalid release version.\n' >&2; exit 1; }
readonly output="$output_root/rilvune-$version-$release_suffix"
staging="$(mktemp -d "$(dirname -- "$output_root")/.package.XXXXXXXX")"
readonly staging
trap 'rm -rf -- "$staging"' EXIT
readonly artifact="$staging/${output##*/}"
# Platform contract: write $artifact using $distribution, $runtime, $jdk, $version,
# and private scratch space under $staging. Shared code validates and publishes it.
package_application
bash "$script_root/test-release.sh" "$artifact"
checksum="$(file_sha256 "$artifact")"
printf '%s  %s\n' "$checksum" "${output##*/}" > "$artifact.sha256"
# Replace only this target, after all checks; retain other platforms and versions.
mv -f -- "$artifact" "$output"
mv -f -- "$artifact.sha256" "$output.sha256"
printf 'Standalone release: %s\n' "$output"
