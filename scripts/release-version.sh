#!/usr/bin/env bash
# Shared release identity for packaging and publication; never modifies Git history.
set -euo pipefail
readonly repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$repository_root"
base_version="$(sed -n 's/^version = "\([0-9][0-9.]*\)"$/\1/p' build.gradle.kts)"
[[ "$base_version" =~ ^[0-9]{4}\.[1-4]\.[0-9]{1,2}(\.[0-9]+)?$ ]] \
    || { printf 'Expected a year.quarter.week version, optionally followed by a revision.\n' >&2; exit 1; }
commit="$(git rev-parse --short=7 HEAD)" \
    || { printf 'A Git commit is required to identify this release.\n' >&2; exit 1; }
[[ "$commit" =~ ^[0-9a-f]{7,40}$ ]] || { printf 'Invalid release commit hash.\n' >&2; exit 1; }
printf '%s-%s\n' "$base_version" "$commit"
