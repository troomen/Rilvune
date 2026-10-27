#!/usr/bin/env bash
# Publish all staged platform artifacts; no credentials are persisted.
set -euo pipefail
readonly repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repository_root"
fail() { printf 'Rilvune: %s\n' "$*" >&2; exit 1; }
log() { printf '[%(%H:%M:%S)T] %s\n' -1 "$*" >&2; }
active_pid=
progress_pid=
cleanup() {
    [[ -z "$progress_pid" ]] || kill "$progress_pid" 2>/dev/null || true
    [[ -z "$active_pid" ]] || kill "$active_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'log "Publishing interrupted; uploaded assets remain in the draft."; exit 130' INT
trap 'log "Publishing cancelled; uploaded assets remain in the draft."; exit 143' TERM
run_step() {
    local label="$1" status=0
    shift
    log "$label — please wait."
    "$@" &
    active_pid=$!
    (
        timer_pid=
        trap '[[ -z "$timer_pid" ]] || kill "$timer_pid" 2>/dev/null || true' EXIT
        trap 'exit 0' TERM INT
        while true; do
            sleep 10 &
            timer_pid=$!
            wait "$timer_pid" || exit 0
            kill -0 "$active_pid" 2>/dev/null || exit 0
            log "$label — still working; please wait."
        done
    ) &
    progress_pid=$!
    wait "$active_pid" || status=$?
    active_pid=
    kill "$progress_pid" 2>/dev/null || true
    wait "$progress_pid" 2>/dev/null || true
    progress_pid=
    if (( status != 0 )); then
        log "$label FAILED (exit $status)."
        log 'Publishing stopped. If a draft was created, rerun make publish to resume it.'
        return "$status"
    fi
    log "$label — done."
}
command -v gh >/dev/null || fail 'GitHub CLI is required. Use make publish to run it in Docker.'
if [[ -z "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]]; then
    if [[ -t 0 ]]; then
        IFS= read -r -s -p 'GitHub token (hidden): ' GH_TOKEN || fail 'No GitHub token provided.'
        printf '\n' >&2
    else
        IFS= read -r GH_TOKEN || fail 'No GitHub token provided. Run make publish in a terminal.'
    fi
    [[ -n "$GH_TOKEN" ]] || fail 'The GitHub token cannot be empty.'
    export GH_TOKEN
fi
export GH_PROMPT_DISABLED=1 GIT_TERMINAL_PROMPT=0
check_auth() { gh auth status >/dev/null 2>&1; }
run_step 'Checking GitHub authentication' check_auth \
    || fail 'GitHub authentication failed. The token needs Contents: Read and write access to this repository.'
version="$(bash "$repository_root/scripts/release-version.sh")"
readonly version
commit="$(git rev-parse --verify HEAD)"
readonly commit
readonly tag="v$version"
readonly origin="$(git remote get-url origin)"
if [[ "$origin" =~ ^git@github\.com:([^/]+/[^/]+)$ ]]; then
    github_repository="${BASH_REMATCH[1]}"
elif [[ "$origin" =~ ^https://github\.com/([^/]+/[^/]+)$ ]]; then
    github_repository="${BASH_REMATCH[1]}"
else
    fail 'origin must be a GitHub SSH or HTTPS repository URL.'
fi
github_repository="${github_repository%.git}"
readonly github_repository
log "Preparing release $version for $github_repository."
run_step 'Checking repository access' gh repo view "$github_repository" --json nameWithOwner >/dev/null
readonly release_root="$repository_root/build/release"
log 'Validating release files and checksums — please wait.'
shopt -s nullglob
assets=()
artifact_count=0
for asset in "$release_root"/*; do
    [[ -f "$asset" && ! -L "$asset" ]] || fail "Expected a regular release file: ${asset##*/}"
    [[ "${asset##*/}" == "rilvune-$version-"* ]] || fail "Artifact has the wrong version or name: ${asset##*/}"
    if [[ "$asset" == *.sha256 ]]; then
        [[ -f "${asset%.sha256}" ]] || fail "Checksum has no matching artifact: ${asset##*/}"
    else
        [[ -f "$asset.sha256" ]] || fail "Missing checksum for ${asset##*/}"
        # Compare against the artifact directly; do not follow paths from a manifest.
        expected_checksum="$(sha256sum "$asset")"
        expected_checksum="${expected_checksum%% *}"
        read -r recorded_checksum _ < "$asset.sha256"
        [[ "$recorded_checksum" == "$expected_checksum" ]] || fail "Checksum mismatch for ${asset##*/}"
        artifact_count=$((artifact_count + 1))
    fi
    assets+=("$asset")
    log "Validated ${asset##*/}."
done
(( artifact_count > 0 )) || fail 'No release artifacts found. Run make release first.'
existing_state="$(run_step 'Checking existing releases' gh release list --repo "$github_repository" \
    --limit 100 --json tagName,isDraft --jq ".[] | select(.tagName == \"$tag\") | .isDraft")"
[[ "$existing_state" != false ]] || fail "Release $tag is already published. Choose a new version."
if [[ "$existing_state" == true ]]; then
    run_step "Resuming draft $version" gh release edit "$tag" --repo "$github_repository" \
        --title "$version" --notes ""
else
    run_step "Creating draft $version" gh release create "$tag" --repo "$github_repository" \
        --title "$version" --notes "" --draft --target "$commit"
fi
index=0
for asset in "${assets[@]}"; do
    index=$((index + 1))
    size="$(du -h -- "$asset")"
    size="${size%%[[:space:]]*}"
    run_step "Uploading $index/${#assets[@]}: ${asset##*/} ($size)" \
        gh release upload "$tag" "$asset" --repo "$github_repository" --clobber
done
run_step "Publishing release $version" gh release edit "$tag" --repo "$github_repository" --draft=false --latest
log "Release $version published successfully."
run_step 'Fetching release link' gh release view "$tag" --repo "$github_repository" --json url --jq .url
