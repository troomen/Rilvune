#!/usr/bin/env bash
# Exercise publication ordering and failure cases without contacting GitHub.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-publish-test.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
readonly real_git="$(command -v git)"
export PUBLISH_REAL_GIT="$real_git" PUBLISH_LOG="$fixture/log"
mkdir -p "$fixture/repository with spaces/scripts" "$fixture/bin"
cp "$source_root/scripts/publish-release.sh" "$fixture/repository with spaces/scripts/"
cat > "$fixture/bin/git" <<'EOF'
#!/bin/bash
set -euo pipefail
if [[ "$1" == -c && "$2" == credential.helper= ]]; then
    shift 4
    case "$1" in
        ls-remote)
            [[ "${EXISTING_TAG:-false}" != true ]] || printf 'abc\trefs/tags/v2026.4.44\n'
            exit 0 ;;
        push) printf 'push\n' >> "$PUBLISH_LOG"; exit 0 ;;
    esac
fi
exec "$PUBLISH_REAL_GIT" "$@"
EOF
cat > "$fixture/bin/gh" <<'EOF'
#!/bin/bash
set -euo pipefail
[[ "${GH_TOKEN:-}" == fixture-token ]]
if [[ "$1 $2" == 'auth status' ]]; then exit 0; fi
printf '%s\n' "$*" >> "$PUBLISH_LOG"
if [[ "$1 $2" == 'release create' && "${FAIL_UPLOAD:-false}" == true ]]; then exit 42; fi
EOF
chmod +x "$fixture/bin/"*
export PATH="$fixture/bin:$PATH"
unset GH_TOKEN GITHUB_TOKEN GH_TOKEN_FILE
cd "$fixture/repository with spaces"
mkdir -p releases
printf '/build/\n/.local/\n' > .gitignore
printf 'version = "2026.4.44"\n' > build.gradle.kts
printf 'Release notes\n' > releases/2026.4.44.md
git init --quiet --initial-branch=main
git add .
git -c user.name=Test -c user.email=test@example.com commit --quiet -m fixture
git remote add origin git@github.com:troomen/Rilvune.git
mkdir -p build/release
git rev-parse HEAD > build/release/.source-commit
# Include non-executable installer files to exercise future platform support.
for asset in rilvune-2026.4.44-linux-x86_64 rilvune-2026.4.44-windows-x86_64.exe rilvune-2026.4.44-macos-aarch64.dmg; do
    printf 'fixture artifact\n' > "build/release/$asset"
    (cd build/release && sha256sum "$asset" > "$asset.sha256")
done
printf 'fixture-token\n' | bash scripts/publish-release.sh
[[ ! -e .local/github-token ]]
grep -Fx push "$PUBLISH_LOG" >/dev/null
grep -F -- '--notes-file' "$PUBLISH_LOG" | grep -F -- '--draft' >/dev/null
grep -F 'release edit v2026.4.44' "$PUBLISH_LOG" | grep -F -- '--draft=false --latest' >/dev/null
[[ "$(sed -n '/^push$/p; /^release create /s/.*/create/p; /^release edit /s/.*/publish/p' "$PUBLISH_LOG")" == $'push\ncreate\npublish' ]]
for asset in build/release/*; do
    grep -F 'release create ' "$PUBLISH_LOG" | grep -F "${asset##*/}" >/dev/null
done
! grep -F 'release create ' "$PUBLISH_LOG" | grep -q '.source-commit'

expect_failure() {
    : > "$PUBLISH_LOG"
    if printf 'fixture-token\n' | bash scripts/publish-release.sh > "$fixture/error" 2>&1; then
        printf 'Publication unexpectedly succeeded.\n' >&2; exit 1
    fi
    ! grep -q '^release edit ' "$PUBLISH_LOG"
}
printf 'uncommitted\n' > dirty-file
expect_failure
! grep -q '^build$' "$PUBLISH_LOG"
rm dirty-file
EXISTING_TAG=true expect_failure
! grep -q '^build$' "$PUBLISH_LOG"
FAIL_UPLOAD=true expect_failure
grep -q '^release create ' "$PUBLISH_LOG"
asset=build/release/rilvune-2026.4.44-windows-x86_64.exe
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
printf 'uncommitted\n' > build/release/.source-commit
expect_failure
! grep -q '^push$' "$PUBLISH_LOG"
printf 'Publication regression tests passed.\n'
