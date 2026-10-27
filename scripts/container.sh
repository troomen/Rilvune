#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly container_image="${RILVUNE_IMAGE:-rilvune-dev:java25}"
local_mode=false
if [[ "${1:-}" == --local ]]; then
    local_mode=true
    shift
fi
readonly action="${1:-}"
if (( $# > 0 )); then shift; fi
readonly host_uid="$(id -u)"
readonly host_gid="$(id -g)"

fail() {
    printf 'Rilvune: %s\n' "$*" >&2
    exit 1
}

if "$local_mode"; then
    case "$action" in
        build|release|publish|test|test-ui|clean|preview) ;;
        *) fail 'Local mode supports build, release, publish, test, test-ui, clean and preview only. Launch from the host with make run.' ;;
    esac
    cd -- "$repository_root"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repository_root/.cache/gradle}"
    build_root="$repository_root"
else
    [[ "$repository_root" != *,* ]] || fail 'The repository path cannot contain a comma (Docker bind-mount syntax).'
    command -v docker >/dev/null || fail 'Docker is required on the host. Inside the development container use make build-local / test-local / test-ui-local.'
    build_root=/workspace
fi
mkdir -p "$repository_root/.cache/gradle" "$repository_root/build"

container_options=(
    --rm
    --init
    --user "$host_uid:$host_gid"
    --cap-drop ALL
    --security-opt no-new-privileges
    --workdir /workspace
    --env GRADLE_USER_HOME=/workspace/.cache/gradle
    --mount "type=bind,source=$repository_root,target=/workspace"
)

run_command() {
    if "$local_mode"; then
        "$@"
    else
        docker run "${container_options[@]}" "$container_image" "$@"
    fi
}

run_gradle() {
    run_command ./gradlew --no-daemon "$@"
}

run_ui_tests() {
    run_command xvfb-run --auto-servernum --server-args='-screen 0 1600x1000x24 -nolisten tcp' \
        ./gradlew --no-daemon uiTest "$@"
}

render_snapshot() {
    local workspace_path="$1"
    local profile_path="$2"
    shift 2
    if "$local_mode"; then
        xvfb-run --auto-servernum --server-args='-screen 0 1600x1000x24 -nolisten tcp' \
            "$repository_root/build/install/rilvune/bin/rilvune" --workspace "$workspace_path" \
            --browse-root "$workspace_path" --profile "$profile_path" "$@"
        return
    fi
    docker run "${container_options[@]}" \
        --mount "type=bind,source=$repository_root,target=$workspace_path" \
        "$container_image" \
        xvfb-run --auto-servernum --server-args='-screen 0 1600x1000x24 -nolisten tcp' \
        /workspace/build/install/rilvune/bin/rilvune --workspace "$workspace_path" \
        --browse-root "$workspace_path" --profile "$profile_path" "$@"
}

case "$action" in
    build)
        run_gradle build installDist
        ;;
    release)
        run_gradle release
        ;;
    publish)
        if "$local_mode"; then
            bash "$repository_root/scripts/publish-release.sh"
        else
            # Credentials are available only to the publishing container, never the image.
            publish_options=(--env GH_TOKEN --env GITHUB_TOKEN)
            if [[ -f "$repository_root/.local/github-token" ]]; then
                publish_options+=(--env GH_TOKEN_FILE=/run/rilvune-github-token
                    --mount "type=bind,source=$repository_root/.local/github-token,target=/run/rilvune-github-token,readonly")
            fi
            docker run "${container_options[@]}" "${publish_options[@]}" "$container_image" \
                bash ./scripts/publish-release.sh
        fi
        ;;
    test)
        run_gradle test "$@"
        ;;
    test-ui)
        run_ui_tests "$@"
        ;;
    clean)
        run_gradle clean
        ;;
    preview)
        run_gradle installDist
        mkdir -p "$repository_root/docs" "$repository_root/build/preview-profile"
        preview_profile="$(mktemp -d "$repository_root/build/preview-profile/session.XXXXXXXX")"
        trap 'rm -rf -- "$preview_profile"' EXIT
        render_snapshot "$repository_root" "$preview_profile" --snapshot "$build_root/docs/concept.png"
        render_snapshot "$repository_root" "$preview_profile" --snapshot "$build_root/docs/file-preview.png" --open src/main/java/dev/rilvune/app/RilvuneApplication.java
        render_snapshot "$repository_root" "$preview_profile" --snapshot "$build_root/docs/settings-appearance.png" --snapshot-view appearance
        render_snapshot "$repository_root" "$preview_profile" --snapshot "$build_root/docs/settings-font.png" --snapshot-view font
        mkdir -p "$preview_profile/fixtures" "$preview_profile/comparison"
        cat > "$preview_profile/fixtures/before.conf" <<'EOF'
# Service configuration
[application]
name = rilvune
version = 1

[server]
host = localhost
port = 8080
workers = 2

[storage]
driver = local
path = ./data

[logging]
level = debug
output = console

[features]
autosave = true
history = false

# End of configuration
EOF
        cat > "$preview_profile/fixtures/after.conf" <<'EOF'
# Service configuration
[application]
name = rilvune
version = 2

[server]
host = localhost
port = 9090
workers = 4
timeout = 30

[storage]
driver = local
path = ./data

[logging]
level = info

[features]
autosave = true
history = true
compare = enabled

# End of configuration
EOF
        render_snapshot "$repository_root" "$preview_profile/comparison" --snapshot "$build_root/docs/file-comparison.png" \
            --open "$preview_profile/fixtures/before.conf" --compare "$preview_profile/fixtures/after.conf"
        run_ui_tests --tests 'dev.rilvune.git.ui.GitWindowIntegrationTest.rendersGitPreview' \
                --tests 'dev.rilvune.app.ui.MainWindowIntegrationTest.projectTreeUsesIntelliJColorsAndUpdatesWithGitHidden' \
                --tests 'dev.rilvune.app.ui.MainWindowIntegrationTest.editorTabGitColorsUpdateWithoutGitWindowIncludingFilesOutsideMaterializedTree' \
                --tests 'dev.rilvune.app.ui.MainWindowIntegrationTest.editorGitMarkersBlamePreviewsRevertUndoAndCommitNavigation'
        cp -- "$repository_root/build/ui-snapshots/git-window.png" "$repository_root/docs/git-window.png"
        cp -- "$repository_root/build/ui-snapshots/project-colors.png" "$repository_root/docs/project-colors.png"
        cp -- "$repository_root/build/ui-snapshots/tab-colors.png" "$repository_root/docs/tab-colors.png"
        cp -- "$repository_root/build/ui-snapshots/rilvune-git-blame.png" "$repository_root/docs/rilvune-git-blame.png"
        cp -- "$repository_root/build/ui-snapshots/rilvune-git-change.png" "$repository_root/docs/rilvune-git-change.png"
        ;;
    run)
        [[ "$(uname -s)" == Linux ]] || fail 'This Docker GUI launcher currently supports Linux X11/XWayland. See README.md.'
        [[ -n "${DISPLAY:-}" ]] || fail 'DISPLAY is empty. Run from a desktop terminal with X11 or XWayland available.'
        [[ "$DISPLAY" =~ ^(:|unix:)[0-9]+(\.[0-9]+)?$ ]] \
            || fail 'Only a local X11/XWayland DISPLAY is supported (for example :0).'
        [[ -d /tmp/.X11-unix ]] || fail 'The local X11 socket directory /tmp/.X11-unix is unavailable.'

        project_path="${1-$repository_root}"
        launch_options=()
        if [[ -n "$project_path" ]]; then
            [[ -d "$project_path" ]] || fail "Project directory does not exist: $project_path"
            project_path="$(cd -- "$project_path" && pwd -P)"
            launch_options+=(--workspace "$project_path")
        fi
        browse_root="${2:-$(dirname -- "${project_path:-$repository_root}")}"
        [[ -d "$browse_root" ]] || fail "Browse root does not exist: $browse_root"
        browse_root="$(cd -- "$browse_root" && pwd -P)"
        [[ "$browse_root" != / ]] || fail 'Choose a specific BROWSE_ROOT directory instead of the host filesystem root.'
        [[ "$browse_root" != *,* ]] || fail 'The browse root cannot contain a comma (Docker bind-mount syntax).'
        if [[ -n "$project_path" ]]; then
            [[ "$project_path" == "$browse_root" || "$project_path" == "$browse_root/"* ]] \
                || fail 'PROJECT must be inside BROWSE_ROOT.'
        fi
        profile_path="${3:-$repository_root/.local}"
        [[ "$profile_path" != *,* ]] || fail 'The profile path cannot contain a comma (Docker bind-mount syntax).'
        mkdir -p "$profile_path"
        profile_path="$(cd -- "$profile_path" && pwd -P)"
        launch_options+=(--browse-root "$browse_root" --profile "$profile_path")

        xauthority_path="${XAUTHORITY:-${HOME}/.Xauthority}"
        [[ -r "$xauthority_path" ]] \
            || fail 'No readable Xauthority file. Set XAUTHORITY to your desktop session cookie file; do not disable X11 access control.'
        xauthority_path="$(realpath -- "$xauthority_path")"
        [[ "$xauthority_path" != *,* ]] || fail 'The Xauthority path cannot contain a comma (Docker bind-mount syntax).'

        run_gradle installDist
        # The host browser must reach OpenCode's random loopback OAuth callback.
        # Only the interactive Linux launcher shares networking; builds/tests stay isolated.
        desktop_options=(--network host)
        session_bus="${XDG_RUNTIME_DIR:-/run/user/$host_uid}/bus"
        if [[ -S "$session_bus" ]]; then
            [[ "$session_bus" != *,* ]] || fail 'The desktop bus path cannot contain a comma (Docker bind-mount syntax).'
            desktop_options+=(
                --env DBUS_SESSION_BUS_ADDRESS=unix:path=/run/rilvune-session-bus
                --mount "type=bind,source=$session_bus,target=/run/rilvune-session-bus,readonly"
            )
        fi
        # Matching the host name makes the mounted local Xauthority cookie valid.
        # The desktop socket and cookie are exposed read-only; no xhost changes.
        docker run "${container_options[@]}" \
            "${desktop_options[@]}" \
            --hostname "$(uname -n)" \
            --env "DISPLAY=$DISPLAY" \
            --env XAUTHORITY=/run/rilvune.xauthority \
            --mount type=bind,source=/tmp/.X11-unix,target=/tmp/.X11-unix,readonly \
            --mount "type=bind,source=$xauthority_path,target=/run/rilvune.xauthority,readonly" \
            --mount "type=bind,source=$browse_root,target=$browse_root" \
            --mount "type=bind,source=$profile_path,target=$profile_path" \
            "$container_image" /workspace/build/install/rilvune/bin/rilvune \
            "${launch_options[@]}"
        ;;
    *)
        fail 'Usage: scripts/container.sh [--local] {build|release|publish|test|test-ui|clean|preview|run [project-directory] [browse-root] [profile]}'
        ;;
esac
