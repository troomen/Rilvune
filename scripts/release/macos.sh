# macOS Intel packaging implementation; jpackage supplies a native Finder launcher.
package_application() {
    local jars app bundle_version
    shopt -s nullglob
    jars=("$distribution"/lib/rilvune-*.jar)
    [[ "${#jars[@]}" == 1 ]] || { printf 'Expected one Rilvune application JAR.\n' >&2; return 1; }
    # Include notices before jpackage constructs/seals the bundle; never mutate it afterwards.
    mkdir -p "$staging/input"
    cp -R "$distribution/lib/." "$staging/input/"
    cp -R "$distribution/licenses" "$staging/input/licenses"
    # CFBundleVersion accepts at most three components. The artifact retains the full
    # release identity (including any revision and commit hash).
    bundle_version="$(printf '%s' "${version%%-*}" | cut -d. -f1-3)"
    "$jdk/bin/jpackage" --type app-image --name Rilvune \
        --app-version "$bundle_version" --vendor Rilvune \
        --mac-package-identifier dev.rilvune.app \
        --dest "$staging/image" --input "$staging/input" \
        --main-jar "${jars[0]##*/}" --main-class dev.rilvune.app.RilvuneApplication \
        --runtime-image "$runtime" \
        --java-options '-Dfile.encoding=UTF-8' \
        --java-options '-Dsun.java2d.uiScale.enabled=true'
    app="$staging/image/Rilvune.app"
    # ditto preserves the app bundle's permissions, symlinks, and macOS metadata.
    ditto -c -k --sequesterRsrc --keepParent "$app" "$artifact"
}

unpack_for_smoke_test() {
    ditto -x -k "$executable" "$fixture/application with spaces"
    smoke_launcher="$fixture/application with spaces/Rilvune.app/Contents/MacOS/Rilvune"
    [[ -d "$fixture/application with spaces/Rilvune.app/Contents/app/licenses" ]]
}

test_release_integrity() {
    # ZIP integrity is checked during extraction; Linux has its own embedded payload digest.
    :
}
