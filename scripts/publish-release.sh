#!/usr/bin/env bash
# Build and publish exactly the committed source; no credentials are persisted.
set -euo pipefail
readonly repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repository_root"
fail() { printf 'Rilvune: %s\n' "$*" >&2; exit 1; }
command -v gh >/dev/null || fail 'GitHub CLI is required. Use make publish to run it in Docker.'
token_file="${GH_TOKEN_FILE:-$repository_root/.local/github-token}"
if [[ -z "${GH_TOKEN:-${GITHUB_TOKEN:-}}" && -f "$token_file" ]]; then
    GH_TOKEN="$(< "$token_file")"
    export GH_TOKEN
fi
export GH_PROMPT_DISABLED=1 GIT_TERMINAL_PROMPT=0
gh auth status >/dev/null 2>&1 || fail 'Set GH_TOKEN or save a GitHub token in .local/github-token (Contents: Read and write).'
[[ -z "$(git status --porcelain)" ]] || fail 'Commit your changes before publishing so the release matches its source tag.'
readonly version="$(sed -n 's/^version = "\([0-9][0-9.]*\)"$/\1/p' build.gradle.kts)"
[[ "$version" =~ ^[0-9]{4}\.[1-4]\.[0-9]{1,2}(\.[0-9]+)?$ ]] || fail 'Expected a year.quarter.week version, optionally followed by a revision.'
readonly tag="v$version"
readonly notes="$repository_root/releases/$version.md"
[[ -s "$notes" ]] || fail "Write release notes in releases/$version.md before publishing."
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
readonly commit="$(git rev-parse HEAD)"
branch="$(git symbolic-ref --quiet --short HEAD)" || fail 'Publish from a branch, not a detached HEAD.'
readonly branch
github_git() {
    git -c credential.helper= -c 'credential.helper=!gh auth git-credential' "$@"
}
# Verify repository access before building. Refuse to replace any existing tag.
gh repo view "$github_repository" --json nameWithOwner >/dev/null
remote_tag="$(github_git ls-remote --tags "https://github.com/$github_repository.git" "refs/tags/$tag")"
[[ -z "$remote_tag" ]] || fail "Tag $tag already exists. Choose a new version; published releases are never overwritten."
./gradlew --no-daemon release
readonly asset="build/release/rilvune-$version-linux-$(uname -m)"
[[ -x "$asset" && -f "$asset.sha256" ]] || fail 'The release build did not produce its expected assets.'
(cd build/release && sha256sum --check "${asset##*/}.sha256")
[[ -z "$(git status --porcelain)" && "$(git rev-parse HEAD)" == "$commit" ]] \
    || fail 'Source changed during the build. Commit changes and retry.'
# Use the token for HTTPS Git without changing origin or writing credential files.
github_git push "https://github.com/$github_repository.git" "HEAD:refs/heads/$branch"
# Create as a draft, upload both files, then publish only once uploads have succeeded.
gh release create "$tag" "$asset#Rilvune · Linux $(uname -m)" "$asset.sha256#SHA-256 checksum" \
    --repo "$github_repository" --target "$commit" --title "Rilvune $version" \
    --notes-file "$notes" --draft
gh release edit "$tag" --repo "$github_repository" --draft=false --latest
gh release view "$tag" --repo "$github_repository" --json url --jq .url
