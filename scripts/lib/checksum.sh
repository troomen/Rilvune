# SHA-256 on Linux (coreutils) and macOS (system Perl), without requiring Homebrew.
file_sha256() {
    local checksum
    if command -v sha256sum >/dev/null 2>&1; then
        checksum="$(sha256sum "$1")" || return
    else
        checksum="$(shasum -a 256 "$1")" || return
    fi
    printf '%s\n' "${checksum%% *}"
}
