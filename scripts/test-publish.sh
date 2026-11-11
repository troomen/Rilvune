#!/usr/bin/env bash
# Publication ordering and failure cases without contacting GitHub or modifying history.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-publish-test.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
source "$source_root/scripts/lib/checksum.sh"
export PUBLISH_LOG="$fixture/log"
mkdir -p "$fixture/repository with spaces/scripts/lib" "$fixture/bin"
cp "$source_root/scripts/lib/checksum.sh" "$fixture/repository with spaces/scripts/lib/"
cp "$source_root/scripts/publish-release.sh" "$source_root/scripts/release-version.sh" "$fixture/repository with spaces/scripts/"
cat > "$fixture/bin/git" <<'GIT'
#!/bin/bash
set -euo pipefail
case "$*" in
    'remote get-url origin') printf 'git@github.com:troomen/Rilvune.git\n' ;;
    'rev-parse --short=7 HEAD') printf '%s\n' "${FIXTURE_COMMIT:-abc1234}" ;;
    'rev-parse HEAD') printf 'abc1234000000000000000000000000000000000\n' ;;
    *) printf 'Forbidden Git operation: %s\n' "$*" >> "$PUBLISH_LOG"; exit 97 ;;
esac
GIT
cat > "$fixture/bin/gh" <<'GH'
#!/bin/bash
set -euo pipefail
[[ "${GH_TOKEN:-}" == fixture-token ]]
if [[ "$1 $2" == 'auth status' ]]; then exit 0; fi
printf '%s\n' "$*" >> "$PUBLISH_LOG"
if [[ "$1" == api ]]; then
    [[ "${MISSING_REMOTE_COMMIT:-false}" != true ]] || exit 1
    printf 'abc1234000000000000000000000000000000000\n'
fi
if [[ "$1 $2" == 'release list' ]]; then printf '%s' "${EXISTING_RELEASE_STATE:-}"; fi
if [[ "$1 $2" == 'release upload' ]]; then
    if [[ "${FAIL_UPLOAD:-false}" == true ]]; then exit 42; fi
    if [[ "${DELAY_UPLOAD:-false}" == true && ! -e "$PUBLISH_LOG.delayed" ]]; then
        touch "$PUBLISH_LOG.delayed"
        sleep 11
    fi
fi
GH
chmod +x "$fixture/bin/"*
export PATH="$fixture/bin:$PATH"
unset GH_TOKEN GITHUB_TOKEN GH_TOKEN_FILE
cd "$fixture/repository with spaces"
printf 'version = "2026.4.66"\n' > build.gradle.kts
mkdir -p build/release
readonly asset=build/release/rilvune-2026.4.66-abc1234.tar.gz
printf 'fixture artifact\n' > "$asset"
printf '%s  %s\n' "$(file_sha256 "$asset")" "${asset##*/}" > "$asset.sha256"
printf 'fixture-token\n' | DELAY_UPLOAD=true bash scripts/publish-release.sh > "$fixture/output" 2> "$fixture/progress"
grep -F 'Uploading 1/2:' "$fixture/progress" >/dev/null
grep -F 'still working; please wait.' "$fixture/progress" >/dev/null
grep -F 'published successfully' "$fixture/progress" >/dev/null
! grep -q 'fixture-token' "$fixture/progress"
grep -F 'release create v2026.4.66-abc1234 ' "$PUBLISH_LOG" | grep -F -- '--draft --target abc1234000000000000000000000000000000000' >/dev/null
[[ "$(sed -n '/^release create /s/.*/create/p; /^release upload /s/.*/upload/p; /^release edit /s/.*/publish/p' "$PUBLISH_LOG")" == $'create\nupload\nupload\npublish' ]]
! grep -q 'Forbidden Git operation' "$PUBLISH_LOG"
expect_failure() {
    : > "$PUBLISH_LOG"
    if printf 'fixture-token\n' | bash scripts/publish-release.sh > "$fixture/error" 2>&1; then
        printf 'Publication unexpectedly succeeded.\n' >&2; exit 1
    fi
    ! grep -q -- '--draft=false' "$PUBLISH_LOG"
}
EXISTING_RELEASE_STATE=false expect_failure
! grep -q '^release upload ' "$PUBLISH_LOG"
: > "$PUBLISH_LOG"
printf 'fixture-token\n' | EXISTING_RELEASE_STATE=true bash scripts/publish-release.sh > "$fixture/output" 2>&1
! grep -q '^release create ' "$PUBLISH_LOG"
grep -q '^release upload ' "$PUBLISH_LOG"
FAIL_UPLOAD=true expect_failure
grep -q 'FAILED (exit 42)' "$fixture/error"
MISSING_REMOTE_COMMIT=true expect_failure
grep -q 'Push the source commit' "$fixture/error"
! grep -q '^release create ' "$PUBLISH_LOG"
FIXTURE_COMMIT=def5678 expect_failure
mv "$asset.sha256" "$fixture/checksum"
expect_failure
mv "$fixture/checksum" "$asset.sha256"
printf 'damage\n' >> "$asset"
expect_failure
printf 'fixture artifact\n' > "$asset"
for stale in rilvune-2026.4.66-abc1234-linux-x86_64 rilvune-2026.4.66-abc1234-macos-arm64.zip rilvune-2026.3.30-abc1234.tar.gz; do
    printf 'stale release\n' > "build/release/$stale"
    expect_failure
    ! grep -q '^release upload ' "$PUBLISH_LOG"
    rm "build/release/$stale"
done
mv "$asset" "$fixture/only-checksum"
expect_failure
rm "$asset.sha256"
expect_failure
printf 'Publication regression tests passed.\n'
