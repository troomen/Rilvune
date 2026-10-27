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
./build/release/rilvune-2026.4.44-linux-x86_64
```

`make release` runs the headless tests and release smoke tests, then produces one executable under `build/release/`,
plus a `.sha256` checksum file. Copy the executable to another Linux machine with the
same CPU architecture; Java and Docker are not needed to run it. The filename uses
the project version and build machine's architecture (`x86_64` or `aarch64`).
Use `make release-local` inside the development container, or with a local JDK 25
configured for Gradle's toolchain.

The executable contains the editor, dependency JARs, licenses, and a `jlink` Java
runtime. A Bash launcher verifies and extracts its embedded payload into a private
temporary directory on each launch, runs the bundled Java, then removes the temporary
files on exit. `TMPDIR` can select an executable filesystem with sufficient free
space. This is a self-extracting executable, not a native compilation of the Java code.
Existing launch options are supported, for example:

```bash
./build/release/rilvune-2026.4.44-linux-x86_64 --workspace "$PWD" --profile "$HOME/.local/share/rilvune"
```

Releases target the build image's Linux baseline (Ubuntu 24.04, glibc 2.39 or newer)
and require X11/XWayland and standard desktop libraries, including fontconfig,
FreeType, ALSA, X11, Xext, Xi, Xrender, and Xtst. Bash, tar/gzip, and coreutils must
be available. Git features require the host's Git executable. Agent setup still
downloads its pinned OpenCode runtime and requires network access and authentication.
Build separate releases on each supported CPU architecture; Windows and macOS
releases are not currently implemented.

### Publish to GitHub Releases

The development image includes GitHub CLI. From a clean, committed working tree:

```bash
make publish
```

The command prompts for a GitHub token with hidden input. The token is kept in
memory for the publishing run and is never saved to a file. For a fine-grained
token, select this repository and grant **Contents: Read and write**. Docker
receives the token through standard input; it is not included in container
configuration, the image, or release assets. For automation, `GH_TOKEN` or
`GITHUB_TOKEN` can supply the token instead of prompting.

Publishing reads the version from `build.gradle.kts` and notes from
`releases/<version>.md`, builds and tests the executable, pushes the current branch
to `origin` using HTTPS, and creates a `v<version>` tag at the exact built commit.
It creates a draft, uploads the executable and checksum, then publishes the release
as Latest. Existing tags are never replaced. If an upload fails, the release stays
a draft for inspection and recovery on GitHub.

For each release, update the version and release notes, commit the changes, and
run `make publish`. `make publish-local` provides the same workflow with a local
JDK 25 and GitHub CLI.

## Using the editor

Open a project or file from the menus. Existing files are autosaved; use **Save as…** to give a draft a path or save a copy. Recovery drafts are available through **Drafts & recovery…**.

Git features require a Git repository and the Git executable. The development image includes Git.

The agent panel has a connection setup flow. Its runtime installer downloads a pinned OpenCode release and verifies its checksum. Agent runtime files and authentication state are kept under the selected profile's `opencode/` directory. Agent setup and requests require network access and authentication with the configured provider.

## Development and tests

| Command | Purpose |
| --- | --- |
| `make build` | Build, run headless tests, and install the distribution in Docker |
| `make release` | Test and build one Linux executable with Java included |
| `make publish` | Build and publish the current version to GitHub Releases |
| `make run` | Launch the editor |
| `make test` | Run headless JUnit tests in Docker |
| `make test-ui` | Run Swing integration tests using Xvfb in Docker |
| `make clean` | Remove build output while preserving the profile and dependency cache |
| `make help` | Show available commands |

The distribution is written to `build/install/rilvune/`. Archives are produced under `build/distributions/`.

## Local data

Build output, dependency caches, and the default `.local/` profile are excluded from Git. Keep custom profiles and authentication files out of the repository as well. Cleaning build output does not delete your profile.

## Contributing

Describe the problem and intended behavior in an issue or pull request. For code changes, run the relevant tests and include UI integration tests when changing Swing behavior. Include screenshots for visible interface changes when useful.

## License and third-party notices

No project-wide license is currently included in this repository. Third-party license notices are provided in [`licenses/`](licenses/), [`src/main/resources/fonts/`](src/main/resources/fonts/), and [`src/main/resources/agents/opencode/LICENSE.txt`](src/main/resources/agents/opencode/LICENSE.txt).
