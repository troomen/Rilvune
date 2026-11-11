#!/bin/bash
# The same launcher and application run on every OS. Only Java is platform specific.
set -euo pipefail
launcher="$0"
if [[ "$launcher" != */* ]]; then launcher="$(command -v -- "$launcher")"; fi
while [[ -L "$launcher" ]]; do
    directory="$(cd -- "$(dirname -- "$launcher")" && pwd -P)"
    launcher="$(readlink "$launcher")"
    [[ "$launcher" == /* ]] || launcher="$directory/$launcher"
done
readonly contents="$(cd -- "$(dirname -- "$launcher")/.." && pwd -P)"
os="$(uname -s)"
arch="$(uname -m)"
case "$os" in Linux) os=linux ;; Darwin) os=macos ;; *) os=unknown ;; esac
case "$arch" in x86_64|amd64|x64) arch=x86_64 ;; aarch64|arm64) arch=arm64 ;; *) arch=unknown ;; esac
java_options=(-Dfile.encoding=UTF-8 -Dsun.java2d.uiScale.enabled=true)
if [[ "$os" == macos ]]; then
    java_options+=(-Xdock:name=Rilvune "-Xdock:icon=$contents/Resources/Rilvune.icns")
fi
if [[ "$os" != unknown && "$arch" != unknown ]]; then
    java="$contents/runtime/$os-$arch/bin/java"
    [[ -x "$java" ]] || { printf 'Rilvune is damaged: bundled Java for %s-%s is missing. Extract the complete release again.\n' "$os" "$arch" >&2; exit 1; }
else
    # The Java application remains usable on other desktops; optional native capabilities
    # independently decide whether they are supported. Never run a mismatched binary.
    java="${JAVA_HOME:+$JAVA_HOME/bin/java}"
    if [[ -z "$java" || ! -x "$java" ]]; then java="$(command -v java || true)"; fi
    [[ -n "$java" ]] || { printf 'Rilvune requires Java 25 or newer on this platform.\n' >&2; exit 1; }
fi
exec "$java" "${java_options[@]}" -cp "$contents/app/lib/*" dev.rilvune.app.RilvuneApplication "$@"
