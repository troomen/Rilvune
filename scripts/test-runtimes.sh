#!/usr/bin/env bash
# Exercise downloads, cache integrity, runtime layout and failure retention without a network.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-runtimes.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
source "$source_root/scripts/lib/checksum.sh"
mkdir -p "$fixture/scripts/lib" "$fixture/source/jre/bin" "$fixture/source/jre/legal" "$fixture/bin"
cp "$source_root/scripts/prepare-runtimes.sh" "$fixture/scripts/"
cp "$source_root/scripts/lib/checksum.sh" "$fixture/scripts/lib/"
printf '#!/bin/bash\nexit 0\n' > "$fixture/source/jre/bin/java"
chmod +x "$fixture/source/jre/bin/java"
printf 'JAVA_VERSION="25.0.5"\n' > "$fixture/source/jre/release"
tar -czf "$fixture/source.tar.gz" -C "$fixture/source" jre
readonly archive_checksum="$(file_sha256 "$fixture/source.tar.gz")"
printf 'linux-x86_64 https://fixture.invalid/java.tar.gz %s jre\n' "$archive_checksum" > "$fixture/scripts/java-runtimes.txt"
export RUNTIME_FIXTURE="$fixture" PATH="$fixture/bin:$PATH"
cat > "$fixture/bin/curl" <<'CURL'
#!/bin/bash
set -eu
printf 'download\n' >> "$RUNTIME_FIXTURE/downloads"
[[ "${FAIL_DOWNLOAD:-false}" != true ]] || exit 22
while (( $# )); do
    if [[ "$1" == -o ]]; then cp "$RUNTIME_FIXTURE/source.tar.gz" "$2"; exit; fi
    shift
done
exit 1
CURL
chmod +x "$fixture/bin/curl"
prepare() { bash "$fixture/scripts/prepare-runtimes.sh" "$fixture/cache" "$fixture/output"; }
prepare
[[ -x "$fixture/output/linux-x86_64/bin/java" ]]
prepare
[[ "$(wc -l < "$fixture/downloads")" == 1 ]]
# A modified cache entry is replaced from the pinned source before extraction.
printf 'damaged\n' >> "$fixture/cache/$archive_checksum.tar.gz"
prepare
[[ "$(wc -l < "$fixture/downloads")" == 2 ]]
[[ "$(file_sha256 "$fixture/cache/$archive_checksum.tar.gz")" == "$archive_checksum" ]]
# Neither a transport failure nor a bad digest can replace a previously staged runtime.
printf 'preserve\n' > "$fixture/output/preserved"
rm "$fixture/cache/$archive_checksum.tar.gz"
if FAIL_DOWNLOAD=true prepare > "$fixture/error" 2>&1; then exit 1; fi
[[ -x "$fixture/output/linux-x86_64/bin/java" && -f "$fixture/output/preserved" ]]
printf 'damaged\n' >> "$fixture/source.tar.gz"
if prepare > "$fixture/error" 2>&1; then exit 1; fi
grep -F 'checksum mismatch' "$fixture/error" > /dev/null
[[ -f "$fixture/output/preserved" && ! -f "$fixture/cache/$archive_checksum.tar.gz" ]]
[[ -z "$(find "$fixture" -maxdepth 1 -name '.runtimes.*' -print)" ]]
printf 'Runtime download regression tests passed.\n'
