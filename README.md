# Rilvune

<img src="images/editor-logo.svg" alt="Rilvune logo" width="64" height="64">

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

### One standalone release

Rilvune has one application build and one release download for Linux and macOS:

```bash
make promote
# Produces build/release/rilvune-<version>-<commit>.tar.gz and its .sha256
```

`make promote` runs headless tests, real Swing tests under Xvfb, release workflow
checks, and packaging smoke tests. `make release` runs the same checks without
the Swing suite. Docker supplies the development JDK. On a Mac or with a local
Temurin JDK 25, use `make promote-local` or `make release-local` instead.
Mac UI tests run on the native desktop; local Linux UI tests require Xvfb.

The archive contains the same application JARs for every platform and pinned
Temurin Java 25 runtimes for Linux x86_64/ARM64 and macOS Apple Silicon/Intel.
The shared launcher detects the host OS and CPU and runs the matching Java.
Java and Docker are not needed to run a release. Java archives are downloaded
only when building, verified against `scripts/java-runtimes.txt`, and cached
under `.cache/java-runtimes/`. The pinning follows the
[official Temurin download and verification guidance](https://adoptium.net/installation/ci-scripts).
The larger download keeps installation independent of runtime downloads.

Extract the archive, keeping its directory structure intact:

- **Linux:** run `./Rilvune/rilvune`. A glibc desktop with X11/XWayland and the
  standard Java desktop libraries (fontconfig, FreeType, ALSA, X11, Xext, Xi,
  Xrender and Xtst) is required. Alpine/musl is not supported by the bundled Java.
- **macOS, including M1:** move `Rilvune/Rilvune.app` into Applications and open it
  in Finder. The same archive supports Apple Silicon and Intel without Rosetta.
  The app is not Developer ID signed or notarized; use macOS's approval flow for
  applications you trust.

The Linux launcher and macOS app use the same launch options:

```bash
./Rilvune/rilvune --workspace "$HOME/projects/example"
/Applications/Rilvune.app/Contents/MacOS/Rilvune --workspace "$HOME/projects/example"
```

By default the editor browses your home directory and stores its profile in
`$XDG_DATA_HOME/rilvune` (or `~/.local/share/rilvune`) on Linux, and
`~/Library/Application Support/Rilvune` on macOS. `--profile`, `--browse-root`
and `--workspace` override defaults. To retain an earlier working-directory
profile, launch with `--profile /absolute/path/to/old/.local`; profiles are never
silently moved or deleted. The Docker development launcher still uses the
repository's `.local/` profile explicitly.

Application shortcuts use Control on Linux and Command on macOS. Control+Tab
continues to switch tabs on both. The macOS Quit action uses the normal save
and shutdown flow. OpenCode is optional: unsupported OS/CPU combinations show a
disabled assistant setup button while editing, autosave, search and Git continue
to work. On other platforms the Java application can run with an installed Java
25 or newer; bundled runtimes and native integrations are supported only where
explicitly listed.

With an installed Java on another desktop, launch the shared application directly:

```bash
java -cp "Rilvune/Rilvune.app/Contents/app/lib/*" dev.rilvune.app.RilvuneApplication
```

The **Verify universal release** GitHub Actions workflow builds the archive once,
then downloads and runs that exact artifact on Linux x86_64/ARM64 and macOS
Apple Silicon/Intel. It checks bundled Java, opens a real editor window, and runs
native OpenCode tests with a local mock provider. It never publishes a release.

### Promote and publish

After committing and merging the source into `main`:

```bash
git push origin main
make promote
make publish
```

Promotion tests and packages the current commit; it does not change branches,
commit code, create tags, or publish. The base version lives in `build.gradle.kts`;
the short `HEAD` hash completes the release identity and appears in About.
Rebuild if `HEAD` changes. Remove older artifacts from `build/release/` before
promotion (`make clean` also removes build output while preserving profiles and
cached downloads).

Publishing accepts exactly one universal archive and its matching SHA-256 file.
Stale versions and platform-specific artifacts are rejected. A failed package
build leaves the previous staged release intact. Publishing creates a GitHub
draft targeting the exact source commit, uploads the archive and checksum,
then marks the release Latest. An interrupted upload can be resumed by rerunning
`make publish`; published releases are never overwritten. The source commit must
already be pushed to GitHub. Publishing never commits or pushes source code.

The command prompts for a GitHub token with hidden input. For a fine-grained token,
select this repository and grant **Contents: Read and write**. The token stays in
memory; Docker receives it through standard input. `GH_TOKEN` or `GITHUB_TOKEN`
can supply it for automation. `make publish-local` uses a locally installed GitHub
CLI. Release notes can be added on GitHub later.

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
package. Linux x86_64/ARM64 packages use the same pinned version.
If the OS or architecture is unsupported, the panel shows the detected values and
disables downloading. No runtime is downloaded or started on an unsupported platform.
This runtime selection is separate from editor packaging. OpenCode is always
downloaded on demand and is not included in editor release artifacts.

Platform aliases are normalized by `HostPlatform`. OpenCode-specific artifact
names, URLs, and checksums live in
`src/main/resources/agents/opencode/release.properties`, with `platform.<os>-<arch>`
entries mapping to upstream packages. To add a target, add its alias to
`HostPlatform` if needed, register a pinned package in that manifest, and test the
mapping and installation. Existing upstream directory names remain stable so
verified installations can be reused across editor updates.

#### Platform extension points

- `HostPlatform` is the shared runtime OS/architecture detector. Keep OS aliases
  and executable naming conventions there, rather than reading system properties
  throughout the application.
- Each capability owns its selector and implementation. For example,
  `SystemBrowser` selects the Linux portal or Java desktop browser, while
  `LaunchDefaults` selects application-specific paths. No global platform registry
  or plugin framework is needed.
- `scripts/java-runtimes.txt` pins the Java runtimes included in the universal
  artifact. `scripts/prepare-runtimes.sh` verifies archives before staging them;
  `scripts/package-release.sh` assembles the single shared layout. The launcher
  selects Java at runtime; the application independently selects native features.
- To support another target, add a pinned runtime and launcher route, add an
  OpenCode manifest entry when upstream supports it, and verify the same archive
  on a matching runner. Do not introduce a separate application or release task.
  `scripts/test-packaging.sh` checks runtime routing and failure retention;
  `scripts/test-release.sh` runs the real archive on the current host.

## Development and tests

| Command | Purpose |
| --- | --- |
| `make build` | Build, run headless tests, and install the distribution in Docker |
| `make release` | Test and build the universal archive with Java included |
| `make promote` | Run all checks, including Swing tests, and stage the universal release |
| `make release-local` | Build the same universal release with a local Temurin JDK 25 |
| `make publish` | Publish the single universal archive and checksum to GitHub Releases |
| `make run` | Launch the editor |
| `make test` | Run headless JUnit tests in Docker |
| `make test-ui` | Run Swing integration tests using Xvfb in Docker |
| `make clean` | Remove build output while preserving the profile and dependency cache |
| `make help` | Show available commands |

The distribution is written to `build/install/rilvune/`. Archives are produced under `build/distributions/`.

## Local data

Build output, dependency caches, and the Docker development `.local/` profile are excluded from Git. Standalone releases store profiles in the user data directory described above. Keep custom profiles and authentication files out of the repository as well. Cleaning build output does not delete your profile.

Agent attachment snapshots live under `<profile>/agents/attachments/`, alongside chat preferences in `<profile>/agents/chats/` and native history in `<profile>/opencode/`. Back up the whole profile to preserve conversations and their files. Attachment files are private, deduplicated by content, and intentionally retained when removed from a draft or when a chat is deleted: another saved or branched conversation may still reference them. They are not stored in the project or a temporary directory.

## Contributing

Describe the problem and intended behavior in an issue or pull request. For code changes, run the relevant tests and include UI integration tests when changing Swing behavior. Include screenshots for visible interface changes when useful.

## License and third-party notices

Rilvune is licensed under the [Apache License, Version 2.0](LICENSE).

Third-party components remain subject to their respective licenses. Their notices are provided in [`licenses/`](licenses/), [`src/main/resources/fonts/`](src/main/resources/fonts/), and [`src/main/resources/agents/opencode/LICENSE.txt`](src/main/resources/agents/opencode/LICENSE.txt).
