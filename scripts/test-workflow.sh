#!/usr/bin/env bash
# Launcher regression tests: no Java, Docker, Make, or desktop required.
set -euo pipefail

readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/editor-workflow.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
readonly repository="$fixture/repository with spaces"
mkdir -p "$repository/scripts" "$fixture/bin"
cp -- "$source_root/scripts/container.sh" "$repository/scripts/container.sh"
export WORKFLOW_LOG="$fixture/commands" WORKFLOW_REPOSITORY="$repository"
export PATH="$fixture/bin:$PATH"
unset GRADLE_USER_HOME

cat > "$fixture/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker' >> "$WORKFLOW_LOG"
printf '<%s>' "$@" >> "$WORKFLOW_LOG"
printf '\n' >> "$WORKFLOW_LOG"
EOF
cat > "$fixture/bin/xvfb-run" <<'EOF'
#!/usr/bin/env bash
printf 'xvfb\n' >> "$WORKFLOW_LOG"
[[ "$1" == --auto-servernum && "$2" == --server-args=* ]] || exit 1
shift 2
exec "$@"
EOF
cat > "$repository/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$PWD" == "$WORKFLOW_REPOSITORY" ]]
[[ "$GRADLE_USER_HOME" == "${EXPECTED_GRADLE_HOME:-$WORKFLOW_REPOSITORY/.cache/gradle}" ]]
printf 'gradle' >> "$WORKFLOW_LOG"
printf '<%s>' "$@" >> "$WORKFLOW_LOG"
printf '\n' >> "$WORKFLOW_LOG"
[[ "${FAIL_GRADLE:-false}" != true ]] || exit 42
if [[ " $* " == *' installDist '* ]]; then
    mkdir -p build/install/editor/bin
    cp -- "$WORKFLOW_REPOSITORY/mock-editor" build/install/editor/bin/editor
fi
if [[ " $* " == *' uiTest '* ]]; then
    mkdir -p build/ui-snapshots
    for name in git-window project-colors tab-colors editor-git-blame editor-git-change; do
        printf 'mock screenshot\n' > "build/ui-snapshots/$name.png"
    done
fi
EOF
cat > "$repository/mock-editor" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'snapshot' >> "$WORKFLOW_LOG"
printf '<%s>' "$@" >> "$WORKFLOW_LOG"
printf '\n' >> "$WORKFLOW_LOG"
[[ "${FAIL_SNAPSHOT:-false}" != true ]] || exit 43
while (( $# )); do
    if [[ "$1" == --profile ]]; then
        [[ "$2" == "$WORKFLOW_REPOSITORY/build/preview-profile/"* ]]
    fi
    if [[ "$1" == --snapshot ]]; then
        printf 'mock screenshot\n' > "$2"
    fi
    shift
done
EOF
chmod +x "$fixture/bin/"* "$repository/gradlew" "$repository/mock-editor"

launch() { bash "$repository/scripts/container.sh" "$@"; }
assert_log() { grep -F -- "$1" "$WORKFLOW_LOG" >/dev/null; }

# Run outside the repository to verify working-directory normalization.
cd -- "$fixture"
launch --local build
launch --local test --tests 'example.SomeTest.someMethod'
launch --local test-ui --tests 'example.ui.*'
launch --local preview
mkdir -p "$repository/.local"
touch "$repository/.local/preserved" "$repository/.cache/gradle/preserved"
launch --local clean
[[ -f "$repository/.local/preserved" && -f "$repository/.cache/gradle/preserved" ]]
[[ -f "$repository/docs/concept.png" && -f "$repository/docs/git-window.png" ]]
[[ -z "$(ls -A "$repository/build/preview-profile")" ]]
assert_log 'gradle<--no-daemon><build><installDist>'
assert_log 'gradle<--no-daemon><test><--tests><example.SomeTest.someMethod>'
assert_log 'gradle<--no-daemon><uiTest><--tests><example.ui.*>'
assert_log 'gradle<--no-daemon><clean>'
assert_log 'xvfb'
if grep -q '^docker' "$WORKFLOW_LOG"; then
    printf 'Local workflow unexpectedly invoked Docker\n' >&2
    exit 1
fi
if launch --local run > "$fixture/error" 2>&1; then exit 1; fi
grep -F 'Launch from the host with make run' "$fixture/error" >/dev/null
if FAIL_GRADLE=true launch --local test; then exit 1; else [[ "$?" == 42 ]]; fi
if FAIL_SNAPSHOT=true launch --local preview; then exit 1; else [[ "$?" == 43 ]]; fi
[[ -z "$(ls -A "$repository/build/preview-profile")" ]]
GRADLE_USER_HOME="$fixture/cache" EXPECTED_GRADLE_HOME="$fixture/cache" launch --local test

# Host actions still use Docker and preserve focused-test arguments.
launch build
launch test --tests 'example.HostTest'
launch test-ui --tests 'example.HostUiTest'
launch clean
assert_log '<editor-dev:java25><./gradlew><--no-daemon><build><installDist>'
assert_log '<editor-dev:java25><./gradlew><--no-daemon><test><--tests><example.HostTest>'
assert_log '<./gradlew><--no-daemon><uiTest><--tests><example.HostUiTest>'
assert_log '<editor-dev:java25><./gradlew><--no-daemon><clean>'
printf 'Workflow regression tests passed.\n'
