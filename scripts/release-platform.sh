#!/usr/bin/env bash
# Build-side platform selector. Runtime code uses HostPlatform; IDs use the same conventions.
# Sourced by packaging and smoke tests, or run directly to resolve a release target.

select_release_platform() {
    local os arch
    os="$(printf '%s' "${1:-$(uname -s)}" | tr '[:upper:]' '[:lower:]')"
    arch="$(printf '%s' "${2:-$(uname -m)}" | tr '[:upper:]' '[:lower:]')"
    case "$os" in
        linux) os=linux ;;
        darwin|'mac os x'|macos|'mac os') os=macos ;;
        *) printf 'Unsupported release operating system: %s\n' "$os" >&2; return 1 ;;
    esac
    case "$arch" in
        amd64|x86_64|x86-64|x64) arch=x86_64 ;;
        aarch64|arm64) arch=arm64 ;;
        *) printf 'Unsupported release architecture: %s\n' "$arch" >&2; return 1 ;;
    esac
    release_target="$os-$arch"
    case "$release_target" in
        linux-x86_64) release_packager=linux; release_suffix=linux-x86_64 ;;
        # Preserve existing Linux artifact names while using canonical IDs internally.
        linux-arm64) release_packager=linux; release_suffix=linux-aarch64 ;;
        macos-x86_64) release_packager=macos; release_suffix=macos-x86_64.zip ;;
        *) printf 'Unsupported release target: %s\n' "$release_target" >&2; return 1 ;;
    esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    set -euo pipefail
    select_release_platform "$@"
    printf '%s\n' "$release_target"
fi
