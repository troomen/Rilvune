#!/usr/bin/env bash
# Smoke-test a real standalone release without a host Java installation or display.
set -euo pipefail
readonly executable="$(realpath -- "${1:?Usage: bash scripts/test-release.sh path/to/release}")"
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
# A moved executable with spaces in its filename must work outside the repository.
cp -- "$executable" "$fixture/Rilvune editor"
"$fixture/Rilvune editor" --help > "$fixture/help"
grep -F 'Rilvune [--workspace' "$fixture/help" >/dev/null
[[ -z "$(ls -A "$TMPDIR")" ]]
# Detect damage before launching Java, and clean up on that failure path too.
printf 'corrupted payload\n' >> "$fixture/Rilvune editor"
if "$fixture/Rilvune editor" --help > "$fixture/output" 2>&1; then
    printf 'Corrupted release unexpectedly launched.\n' >&2
    exit 1
fi
grep -F 'payload checksum mismatch' "$fixture/output" >/dev/null
[[ -z "$(ls -A "$TMPDIR")" ]]
printf 'Standalone release smoke tests passed.\n'
