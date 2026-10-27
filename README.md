# Rilvune

A desktop editor for working with local projects, built with Java 25, Swing, and FlatLaf. Rilvune combines text editing, project search, file comparison, Git inspection, and an integrated AI agent panel.

## Motivation

Rilvune was inspired by IntelliJ IDEA. Its author has enjoyed using IntelliJ for many years and values the care and professionalism behind it. For the way we work, though, it feels overdesigned, with many features we rarely need.

We wanted a tiny, fast editor that feels at home with any language or tool. Rilvune focuses on the visual work an editor does well: navigating files, reading and editing code, comparing changes, and exploring Git history.

The terminal remains central to our workflow. We use it for most commands and more extensive tooling, while the editor provides the visual experience the terminal cannot fully replace. Rilvune does not aim to include every IDE feature. Integrated agent chat brings AI assistance into that same workspace, alongside the files and changes we are working on.

## Features

- Tabbed text editing with syntax highlighting, line numbers, a minimap, and configurable fonts and indentation.
- Autosave, draft recovery, session persistence, and detection of external file changes.
- Project browsing and file operations, file search, and project-wide text search and replacement.
- Image viewing and file comparisons with inline differences.
- Git history, local changes, file and selection history, blame annotations, and editor change markers.
- An agent panel using OpenCode with a Codex/ChatGPT provider, conversation persistence, and approval prompts.

## Quick start

The included Docker workflow builds the application without requiring a host JDK.

### Requirements

- Linux with an X11 or XWayland desktop for the Docker GUI launcher.
- Docker, GNU Make, Bash, and Git.
- A running Docker daemon accessible to your user.
- Internet access for the initial image and dependency downloads.
- A readable Xauthority file for GUI access.

### Build and launch

```bash
git clone https://github.com/troomen/Rilvune.git
cd Rilvune
make build
make run
```

`make build` compiles the application, runs headless tests, and creates an installed distribution. `make run` builds the distribution as needed and opens the current repository as a project.

To work on a different project:

```bash
make run PROJECT=/path/to/project BROWSE_ROOT=/path/to PROFILE=/path/to/rilvune-profile
```

- `PROJECT` is the directory opened on launch; it defaults to this repository.
- `BROWSE_ROOT` controls which host directory is mounted for browsing and editing. It defaults to the parent of `PROJECT`, must contain `PROJECT`, and cannot be `/`.
- `PROFILE` stores preferences, sessions, recovery drafts, and agent state. It defaults to `.local/` in this repository.

The browse root is mounted with write access, so edits affect host files. Use a dedicated profile when you want separate settings and sessions.

To launch without opening a project:

```bash
make run PROJECT= BROWSE_ROOT=/path/to/projects
```

The Docker GUI launcher supports local X11/XWayland displays only. It uses your desktop's Xauthority cookie; if it cannot find that file, set `XAUTHORITY` to its actual location before running `make run`.

### Standalone release

```bash
make release
./build/release/rilvune-2026.4.66-abc1234-linux-x86_64
```

`make release` runs the headless tests and release smoke tests, then produces one executable under `build/release/`,
plus a `.sha256` checksum file. Copy the executable to another Linux machine with the
same CPU architecture; Java and Docker are not needed to run it. The filename uses
the project version, short Git commit hash, and build machine's architecture (`x86_64` or `aarch64`).
The examples use hash `abc1234`; replace it with the hash printed in your release filename.
The hash identifies `HEAD`; uncommitted changes are not represented by the hash.
Use `make release-local` inside the development container, or with a local JDK 25
configured for Gradle's toolchain.

After the new executable passes its smoke tests, `make release` replaces all previous
files in `build/release/`. Failed packaging leaves the previous release intact.

The executable contains the editor, dependency JARs, licenses, and a `jlink` Java
runtime. A Bash launcher verifies and extracts its embedded payload into a private
temporary directory on each launch, runs the bundled Java, then removes the temporary
files on exit. `TMPDIR` can select an executable filesystem with sufficient free
space. This is a self-extracting executable, not a native compilation of the Java code.
Existing launch options are supported, for example:

```bash
./build/release/rilvune-2026.4.66-abc1234-linux-x86_64 --workspace "$PWD" --profile "$HOME/.local/share/rilvune"
```

Releases target the build image's Linux baseline (Ubuntu 24.04, glibc 2.39 or newer)
and require X11/XWayland and standard desktop libraries, including fontconfig,
FreeType, ALSA, X11, Xext, Xi, Xrender, and Xtst. Bash, tar/gzip, and coreutils must
be available. Git features require the host's Git executable. Agent setup still
downloads its pinned OpenCode runtime and requires network access and authentication.
Build separate releases on each supported CPU architecture; Windows and macOS
releases are not currently implemented.

### Publish to GitHub Releases

The development image includes GitHub CLI:

```bash
make release
make publish
```

The command prompts for a GitHub token with hidden input. The token is kept in
memory for the publishing run and is never saved to a file. For a fine-grained
token, select this repository and grant **Contents: Read and write**. Docker
receives the token through standard input; it is not included in container
configuration, the image, or release assets. For automation, `GH_TOKEN` or
`GITHUB_TOKEN` can supply the token instead of prompting.

Packaging and publishing append the short `HEAD` commit hash to the base version
in `build.gradle.kts`, using the same version calculation. Publishing validates all files in
`build/release/`. It creates a draft titled with the version and an empty description,
uploads every artifact and checksum individually,
then publishes the release as Latest. Each step logs its start and completion.
Uploads show the filename, size, and file count; operations still running after
10 seconds print a reminder to wait. If an upload fails, rerun `make publish` to
resume the draft. Already published releases are never overwritten.

Publishing does not commit or push source code and does not require a clean working
tree or a `.source-commit` file. GitHub uses the existing `v<base-version>-<hash>` tag,
or creates it at the identified commit when absent. Push that commit before publishing;
publishing does not push it for you. Rebuild if `HEAD` changes between packaging and publishing.

Release notes can be added on GitHub later; no Markdown notes file is required.
For each release, update the version, then
run `make release` followed by `make publish`. Publishing does not rebuild or clean
the output, so additional platform builds can be collected there before upload.
Artifact names must start with `rilvune-<version>-`, and each artifact needs an
adjacent `.sha256` file. Publishing accepts any platform and file extension; only
Linux packaging is currently implemented.
`make publish-local` publishes the same staged files with local GitHub CLI.

## Using the editor

Open a project or file from the menus. Existing files are autosaved; use **Save as…** to give a draft a path or save a copy. Recovery drafts are available through **Drafts & recovery…**.

Git features require a Git repository and the Git executable. The development image includes Git.

The agent panel has a connection setup flow. Its runtime installer downloads a pinned OpenCode release and verifies its checksum. Agent runtime files and authentication state are kept under the selected profile's `opencode/` directory. Agent setup and requests require network access and authentication with the configured provider.

Attach files with the agent composer's paperclip button, drag files from the Project tree or your file manager, or paste a copied image directly into the message input. Attachments appear in a removable preview shelf, and can be sent without accompanying text. Up to 10 files totaling 20 MiB are accepted per message: UTF-8 text, PNG, JPEG, GIF, WebP, and PDF. Images and PDFs require a compatible model; WebP previews depend on the image decoders available to Java.

Sent attachments appear as compact filename links rather than inline previews. Click an image attachment to open its saved snapshot in a read-only image tab, or a text attachment to open a separate draft copy. Stored snapshots are never edited by these actions. PDF viewing in editor tabs is not supported.

Files are snapshotted when attached; subsequent edits or deletion of the original do not affect the attachment. The agent also receives the original source location, so it can distinguish editable project files from archived context. Clipboard images are saved as PNG. Unsent attachments are restored with each chat's draft. Saved conversations remain owned by OpenCode, including their attachment references.

OpenCode setup detects the operating system and architecture of the running Java
runtime and selects the matching download automatically. Intel Macs (`amd64`,
`x86_64`) use the macOS x64 baseline package; Apple Silicon uses the macOS ARM64
package. Linux and Windows x86_64/ARM64 package selections are also retained.
If the OS or architecture is unsupported, the panel shows the detected values and
disables downloading. No runtime is downloaded or started on an unsupported platform.
This runtime selection is separate from editor packaging, which currently builds
Linux releases only.

Platform aliases are normalized by `HostPlatform`. OpenCode-specific artifact
names, URLs, and checksums live in
`src/main/resources/agents/opencode/release.properties`, with `platform.<os>-<arch>`
entries mapping to upstream packages. To add a target, add its alias to
`HostPlatform` if needed, register a pinned package in that manifest, and test the
mapping and installation. Existing upstream directory names remain stable so
verified installations can be reused across editor updates.

## Development and tests

| Command | Purpose |
| --- | --- |
| `make build` | Build, run headless tests, and install the distribution in Docker |
| `make release` | Test and build one Linux executable with Java included |
| `make publish` | Publish all staged platform artifacts to GitHub Releases |
| `make run` | Launch the editor |
| `make test` | Run headless JUnit tests in Docker |
| `make test-ui` | Run Swing integration tests using Xvfb in Docker |
| `make clean` | Remove build output while preserving the profile and dependency cache |
| `make help` | Show available commands |

The distribution is written to `build/install/rilvune/`. Archives are produced under `build/distributions/`.

## Local data

Build output, dependency caches, and the default `.local/` profile are excluded from Git. Keep custom profiles and authentication files out of the repository as well. Cleaning build output does not delete your profile.

Agent attachment snapshots live under `<profile>/agents/attachments/`, alongside chat preferences in `<profile>/agents/chats/` and native history in `<profile>/opencode/`. Back up the whole profile to preserve conversations and their files. Attachment files are private, deduplicated by content, and intentionally retained when removed from a draft or when a chat is deleted: another saved or branched conversation may still reference them. They are not stored in the project or a temporary directory.

## Contributing

Describe the problem and intended behavior in an issue or pull request. For code changes, run the relevant tests and include UI integration tests when changing Swing behavior. Include screenshots for visible interface changes when useful.

## License and third-party notices

Rilvune is licensed under the [Apache License, Version 2.0](LICENSE).

Third-party components remain subject to their respective licenses. Their notices are provided in [`licenses/`](licenses/), [`src/main/resources/fonts/`](src/main/resources/fonts/), and [`src/main/resources/agents/opencode/LICENSE.txt`](src/main/resources/agents/opencode/LICENSE.txt).
