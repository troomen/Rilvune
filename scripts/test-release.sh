#!/usr/bin/env bash
# Run the same release on any host, without host Java, a display or user credentials.
set -euo pipefail
release_file="${1:?Usage: bash scripts/test-release.sh path/to/release.tar.gz}"
readonly archive="$(cd -- "$(dirname -- "$release_file")" && pwd -P)/${release_file##*/}"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-release-test.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/application with spaces" "$fixture/working directory"
cat > "$fixture/bin/java" <<'JAVA'
#!/bin/bash
printf 'The release tried to use host Java.\n' >&2
exit 99
JAVA
chmod +x "$fixture/bin/java"
export PATH="$fixture/bin:$PATH" JAVA_HOME="$fixture/missing-java"
tar -xzf "$archive" -C "$fixture/application with spaces"
readonly app="$fixture/application with spaces/Rilvune"
readonly contents="$app/Rilvune.app/Contents"
for platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
    [[ -x "$contents/runtime/$platform/bin/java" && -d "$contents/runtime/$platform/legal" && -f "$contents/runtime/$platform/release" ]]
done
[[ -s "$contents/Info.plist" && -s "$contents/Resources/Rilvune.icns" && -s "$contents/app/licenses/HACK-LICENSE.md" ]]
cd -- "$fixture/working directory"
"$app/rilvune" --help > "$fixture/help"
grep -F 'Rilvune [--workspace' "$fixture/help" >/dev/null
"$contents/MacOS/Rilvune" --help > "$fixture/help"
grep -F 'Rilvune [--workspace' "$fixture/help" >/dev/null
printf 'Universal release smoke tests passed.\n'
