#!/usr/bin/env bash
# Verify release identity against real local Git commits, without changing this repository.
set -euo pipefail
readonly source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly fixture="$(mktemp -d "${TMPDIR:-/tmp}/rilvune-version-test.XXXXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
readonly repository="$fixture/repository with spaces"
mkdir -p "$repository/scripts"
cp "$source_root/scripts/release-version.sh" "$repository/scripts/"
printf 'version = "2026.4.66"\n' > "$repository/build.gradle.kts"
git init --quiet "$repository"
if bash "$repository/scripts/release-version.sh" > "$fixture/error" 2>&1; then
    printf 'Versioning unexpectedly accepted a repository without commits.\n' >&2; exit 1
fi
git -C "$repository" -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgSign=false commit --quiet --allow-empty -m first
expected="2026.4.66-$(git -C "$repository" rev-parse --short=7 HEAD)"
[[ "$(bash "$repository/scripts/release-version.sh")" == "$expected" ]]
# Uncommitted files do not silently change the commit identifier.
printf 'dirty\n' > "$repository/uncommitted"
[[ "$(bash "$repository/scripts/release-version.sh")" == "$expected" ]]
git -C "$repository" -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgSign=false commit --quiet --allow-empty -m second
next="$(bash "$repository/scripts/release-version.sh")"
[[ "$next" != "$expected" && "$next" == "2026.4.66-$(git -C "$repository" rev-parse --short=7 HEAD)" ]]
printf 'version = "2026.4.66.1"\n' > "$repository/build.gradle.kts"
[[ "$(bash "$repository/scripts/release-version.sh")" == "2026.4.66.1-${next##*-}" ]]
printf 'version = "invalid"\n' > "$repository/build.gradle.kts"
if bash "$repository/scripts/release-version.sh" > "$fixture/error" 2>&1; then
    printf 'Versioning unexpectedly accepted an invalid base version.\n' >&2; exit 1
fi
printf 'Release version regression tests passed.\n'
