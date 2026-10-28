# Linux packaging implementation. Inputs are supplied by package-release.sh.
package_application() {
    local architecture payload_sha256
    architecture="$(uname -m)"
    mkdir -p "$staging/app"
    cp -a -- "$distribution/lib" "$distribution/licenses" "$staging/app/"
    cp -a -- "$runtime" "$staging/app/runtime"
    # Keep Java's own legal notices and release metadata inside the runtime.
    tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
        -C "$staging/app" -czf "$staging/payload.tar.gz" .
    payload_sha256="$(file_sha256 "$staging/payload.tar.gz")"
    sed -e "s/@PAYLOAD_SHA256@/$payload_sha256/g" -e "s/@ARCHITECTURE@/$architecture/g" \
        "$script_root/release-launcher.sh" > "$artifact"
    cat "$staging/payload.tar.gz" >> "$artifact"
    chmod 755 "$artifact"
}

unpack_for_smoke_test() {
    cp -- "$executable" "$fixture/Rilvune editor"
    smoke_launcher="$fixture/Rilvune editor"
}

test_release_integrity() {
    printf 'corrupted payload\n' >> "$smoke_launcher"
    if "$smoke_launcher" --help > "$fixture/output" 2>&1; then
        printf 'Corrupted release unexpectedly launched.\n' >&2
        return 1
    fi
    grep -F 'payload checksum mismatch' "$fixture/output" >/dev/null
}
