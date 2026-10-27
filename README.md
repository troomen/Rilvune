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

## Using the editor

Open a project or file from the menus. Existing files are autosaved; use **Save as…** to give a draft a path or save a copy. Recovery drafts are available through **Drafts & recovery…**.

Git features require a Git repository and the Git executable. The development image includes Git.

The agent panel has a connection setup flow. Its runtime installer downloads a pinned OpenCode release and verifies its checksum. Agent runtime files and authentication state are kept under the selected profile's `opencode/` directory. Agent setup and requests require network access and authentication with the configured provider.

## Development and tests

| Command | Purpose |
| --- | --- |
| `make build` | Build, run headless tests, and install the distribution in Docker |
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
