#!/bin/bash
# This template is followed by a compressed application and Java runtime.
set -euo pipefail
if [[ "$(uname -s)" != Linux || "$(uname -m)" != '@ARCHITECTURE@' ]]; then
    printf 'This Rilvune release requires Linux @ARCHITECTURE@.\n' >&2
    exit 1
fi
umask 077
release_root="$(mktemp -d "${TMPDIR:-/tmp}/rilvune.XXXXXXXX")"
trap 'rm -rf -- "$release_root"' EXIT
self="$0"
if [[ "$self" != */* ]]; then self="$(command -v -- "$self")"; fi
payload_line="$(awk '/^__RILVUNE_PAYLOAD__$/ { print NR + 1; exit }' "$self")"
tail -n +"$payload_line" -- "$self" > "$release_root/payload.tar.gz"
if ! printf '%s  %s\n' '@PAYLOAD_SHA256@' "$release_root/payload.tar.gz" | sha256sum --check --status; then
    printf 'Rilvune release is damaged: payload checksum mismatch.\n' >&2
    exit 1
fi
tar -xzf "$release_root/payload.tar.gz" -C "$release_root"
rm -- "$release_root/payload.tar.gz"
"$release_root/runtime/bin/java" \
    -Dfile.encoding=UTF-8 -Dsun.java2d.uiScale.enabled=true \
    -cp "$release_root/lib/*" dev.rilvune.app.RilvuneApplication "$@" &
java_pid=$!
trap 'kill -TERM "$java_pid" 2>/dev/null || true; wait "$java_pid" 2>/dev/null || true; exit 143' TERM
trap 'kill -INT "$java_pid" 2>/dev/null || true; wait "$java_pid" 2>/dev/null || true; exit 130' INT
status=0
wait "$java_pid" || status=$?
exit "$status"
__RILVUNE_PAYLOAD__
