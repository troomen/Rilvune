#!/usr/bin/env bash
# One application, one artifact, and every supported runtime regardless of the build host.
set -euo pipefail
readonly script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
source "$script_root/lib/checksum.sh"
readonly distribution="$(cd -- "${1:?Installed application distribution}" && pwd -P)"
readonly runtimes="$(cd -- "${2:?Verified Java runtimes}" && pwd -P)"
mkdir -p -- "${3:?Release output directory}"
readonly output_root="$(cd -- "$3" && pwd -P)"
readonly version="${4:?Release version}"
[[ "$version" =~ ^[0-9]{4}\.[1-4]\.[0-9]{1,2}(\.[0-9]+)?-[a-f0-9]{7,40}$ ]] \
    || { printf 'Invalid release version.\n' >&2; exit 1; }
readonly name="rilvune-$version.tar.gz"
readonly bundle_version="$(printf '%s' "${version%%-*}" | cut -d. -f1-3)"
readonly staging="$(mktemp -d "$(dirname -- "$output_root")/.package.XXXXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
readonly contents="$staging/Rilvune/Rilvune.app/Contents"
mkdir -p "$contents/MacOS" "$contents/Resources" "$contents/app" "$contents/runtime"
cp -R "$distribution/lib" "$distribution/licenses" "$contents/app/"
cp "$distribution/branding/Rilvune.icns" "$contents/Resources/"
while read -r platform _; do
    [[ -n "$platform" && "$platform" != \#* ]] || continue
    [[ -x "$runtimes/$platform/bin/java" && -f "$runtimes/$platform/release" && -d "$runtimes/$platform/legal" ]] \
        || { printf 'Missing Java runtime: %s\n' "$platform" >&2; exit 1; }
    cp -R "$runtimes/$platform" "$contents/runtime/"
done < "$script_root/java-runtimes.txt"
cp "$script_root/release-launcher.sh" "$contents/MacOS/Rilvune"
chmod 755 "$contents/MacOS/Rilvune"
ln -s Rilvune.app/Contents/MacOS/Rilvune "$staging/Rilvune/rilvune"
# Finder metadata describes the same launcher used by Linux and by the CLI.
cat > "$contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>Rilvune</string>
<key>CFBundleDisplayName</key><string>Rilvune</string>
<key>CFBundleIdentifier</key><string>dev.rilvune.app</string>
<key>CFBundleExecutable</key><string>Rilvune</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleIconFile</key><string>Rilvune.icns</string>
<key>CFBundleShortVersionString</key><string>$bundle_version</string>
<key>CFBundleVersion</key><string>$bundle_version</string>
<key>RilvuneReleaseVersion</key><string>$version</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
cp "$script_root/release/README.txt" "$staging/Rilvune/README.txt"
# GNU tar normalizes archive metadata; BSD tar also packages this layout on a Mac.
if tar --version 2>/dev/null | grep -q 'GNU tar'; then
    tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -C "$staging" -czf "$staging/$name" Rilvune
else
    COPYFILE_DISABLE=1 tar -C "$staging" -czf "$staging/$name" Rilvune
fi
bash "$script_root/test-release.sh" "$staging/$name"
printf '%s  %s\n' "$(file_sha256 "$staging/$name")" "$name" > "$staging/$name.sha256"
# Publish only a smoke-tested complete release. Failed packaging retains the previous one.
mv -f -- "$staging/$name" "$output_root/$name"
mv -f -- "$staging/$name.sha256" "$output_root/$name.sha256"
printf 'Universal release: %s\n' "$output_root/$name"
