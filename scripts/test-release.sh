#!/usr/bin/env bash
# Smoke-test a real standalone release without a host Java installation or display.
set -euo pipefail
readonly script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_root/release-platform.sh"
select_release_platform
source "$script_root/release/$release_packager.sh"
release_file="${1:?Usage: bash scripts/test-release.sh path/to/release}"
readonly executable="$(cd -- "$(dirname -- "$release_file")" && pwd -P)/${release_file##*/}"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-release-test.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/temp with spaces" "$fixture/working directory"
cat > "$fixture/bin/java" <<'EOF'
#!/bin/bash
printf 'The release tried to use host Java.\n' >&2
exit 99
EOF
chmod +x "$fixture/bin/java"
export PATH="$fixture/bin:$PATH" JAVA_HOME="$fixture/missing-java" TMPDIR="$fixture/temp with spaces"
cd -- "$fixture/working directory"
# A moved application with spaces in its path must work outside the repository.
# The platform hook sets smoke_launcher to a private copy, never the original artifact.
unpack_for_smoke_test
"$smoke_launcher" --help > "$fixture/help"
grep -F 'Rilvune [--workspace' "$fixture/help" >/dev/null
[[ -z "$(ls -A "$TMPDIR")" ]]
# Detect damage before launching Java, and clean up on that failure path too.
test_release_integrity
[[ -z "$(ls -A "$TMPDIR")" ]]
printf 'Standalone release smoke tests passed.\n'
