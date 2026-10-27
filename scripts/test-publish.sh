#!/usr/bin/env bash
# Exercise publication ordering and failure cases without contacting GitHub.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-publish-test.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
export PUBLISH_LOG="$fixture/log"
mkdir -p "$fixture/repository with spaces/scripts" "$fixture/bin"
cp "$source_root/scripts/publish-release.sh" "$fixture/repository with spaces/scripts/"
cp "$source_root/scripts/release-version.sh" "$fixture/repository with spaces/scripts/"
cat > "$fixture/bin/git" <<'EOF'
#!/bin/bash
set -euo pipefail
case "$*" in
    'remote get-url origin') printf 'git@github.com:troomen/Rilvune.git\n' ;;
    'rev-parse --short=7 HEAD') printf '%s\n' "${FIXTURE_COMMIT:-abc1234}" ;;
    'rev-parse --verify HEAD') printf 'abc1234000000000000000000000000000000000\n' ;;
    *) printf 'Forbidden Git operation: %s\n' "$*" >> "$PUBLISH_LOG"; exit 97 ;;
esac
EOF
cat > "$fixture/bin/gh" <<'EOF'
#!/bin/bash
set -euo pipefail
[[ "${GH_TOKEN:-}" == fixture-token ]]
if [[ "$1 $2" == 'auth status' ]]; then exit 0; fi
printf '%s\n' "$*" >> "$PUBLISH_LOG"
if [[ "$1 $2" == 'release list' ]]; then printf '%s' "${EXISTING_RELEASE_STATE:-}"; fi
if [[ "$1 $2" == 'release upload' ]]; then
    if [[ "${FAIL_UPLOAD:-false}" == true ]]; then exit 42; fi
    if [[ "${DELAY_UPLOAD:-false}" == true && ! -e "$PUBLISH_LOG.delayed" ]]; then
        touch "$PUBLISH_LOG.delayed"
        sleep 11
    fi
fi
EOF
chmod +x "$fixture/bin/"*
export PATH="$fixture/bin:$PATH"
unset GH_TOKEN GITHUB_TOKEN GH_TOKEN_FILE
cd "$fixture/repository with spaces"
printf '/build/\n/.local/\n' > .gitignore
printf 'version = "2026.4.66"\n' > build.gradle.kts
mkdir -p build/release
# Include non-executable installer files to exercise future platform support.
for asset in rilvune-2026.4.66-abc1234-linux-x86_64 rilvune-2026.4.66-abc1234-windows-x86_64.exe rilvune-2026.4.66-abc1234-macos-aarch64.dmg; do
    printf 'fixture artifact\n' > "build/release/$asset"
    (cd build/release && sha256sum "$asset" > "$asset.sha256")
done
printf 'fixture-token\n' | DELAY_UPLOAD=true bash scripts/publish-release.sh > "$fixture/output" 2> "$fixture/progress"
[[ ! -e .local/github-token ]]
grep -F 'Uploading 1/6:' "$fixture/progress" >/dev/null
grep -F 'still working; please wait.' "$fixture/progress" >/dev/null
grep -F 'published successfully' "$fixture/progress" >/dev/null
! grep -q 'fixture-token' "$fixture/progress"
grep -F 'release create ' "$PUBLISH_LOG" | grep -F -- '--draft' >/dev/null
! grep -q -- '--notes-file' "$PUBLISH_LOG"
[[ ! -e releases ]]
grep -F 'release create v2026.4.66-abc1234 ' "$PUBLISH_LOG" | grep -F -- '--title 2026.4.66-abc1234' >/dev/null
grep -F 'release create ' "$PUBLISH_LOG" | grep -F -- '--target abc1234000000000000000000000000000000000' >/dev/null
grep -F 'release edit v2026.4.66-abc1234' "$PUBLISH_LOG" | grep -F -- '--draft=false --latest' >/dev/null
[[ "$(sed -n '/^release create /s/.*/create/p; /^release upload /s/.*/upload/p; /^release edit /s/.*/publish/p' "$PUBLISH_LOG")" == $'create\nupload\nupload\nupload\nupload\nupload\nupload\npublish' ]]
for asset in build/release/*; do
    grep -F 'release upload ' "$PUBLISH_LOG" | grep -F "${asset##*/}" >/dev/null
done
! grep -q 'Forbidden Git operation' "$PUBLISH_LOG"

expect_failure() {
    : > "$PUBLISH_LOG"
    if printf 'fixture-token\n' | bash scripts/publish-release.sh > "$fixture/error" 2>&1; then
        printf 'Publication unexpectedly succeeded.\n' >&2; exit 1
    fi
    ! grep -q -- '--draft=false' "$PUBLISH_LOG"
}
# Publishing works with uncommitted source and without a Git history marker.
printf 'uncommitted\n' > dirty-file
printf 'fixture-token\n' | bash scripts/publish-release.sh > "$fixture/output" 2>&1
rm dirty-file
EXISTING_RELEASE_STATE=false expect_failure
! grep -q '^release upload ' "$PUBLISH_LOG"
: > "$PUBLISH_LOG"
printf 'fixture-token\n' | EXISTING_RELEASE_STATE=true bash scripts/publish-release.sh > "$fixture/output" 2>&1
! grep -q '^release create ' "$PUBLISH_LOG"
grep -q '^release upload ' "$PUBLISH_LOG"
FAIL_UPLOAD=true expect_failure
grep -q '^release upload ' "$PUBLISH_LOG"
grep -q 'FAILED (exit 42)' "$fixture/error"
FIXTURE_COMMIT=def5678 expect_failure
! grep -q '^release upload ' "$PUBLISH_LOG"
asset=build/release/rilvune-2026.4.66-abc1234-windows-x86_64.exe
mv "$asset.sha256" "$fixture/checksum"
expect_failure
! grep -q '^push$' "$PUBLISH_LOG"
mv "$fixture/checksum" "$asset.sha256"
printf 'damage\n' >> "$asset"
expect_failure
! grep -q '^push$' "$PUBLISH_LOG"
printf 'fixture artifact\n' > "$asset"
printf 'old release\n' > build/release/rilvune-2026.3.30-linux-x86_64
expect_failure
! grep -q '^push$' "$PUBLISH_LOG"
rm build/release/rilvune-2026.3.30-linux-x86_64
printf 'Publication regression tests passed.\n'
