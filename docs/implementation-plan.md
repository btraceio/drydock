# Claude Code Project Manager — Detailed Implementation Plan

## 1. Objective

Build a local desktop project manager for the Claude Code CLI.

The application must allow the user to:

- register and switch between local Git repositories;
- create, name, open, close, and resume multiple Claude Code sessions per repository;
- run the real locally installed `claude` CLI without bypassing its authentication, managed settings, environment variables, hooks, gateway configuration, or permission model;
- browse repository files;
- preview source files;
- inspect Git status and diffs;
- keep multiple active Claude sessions open in tabs;
- resume inactive sessions without tmux or another terminal multiplexer;
- package the application as a self-contained macOS application image containing the application, Java runtime, JavaFX modules, native libraries, and all required JVM options.

The initial target is:

- macOS on Apple Silicon;
- JDK 26;
- JavaFX 26;
- libghostty embedded through the Foreign Function & Memory API;
- Gradle;
- a self-contained `jlink` application image;
- a generated executable launcher containing all required JVM arguments;
- optional `.app`/`.dmg` wrapping after the executable image is functional.

Do not implement a replacement Claude client. The application is a process and workspace manager around the official `claude` CLI.

---

## 2. Product Principles

### 2.1 CLI purity

Every interactive Claude session must be an actual invocation of the installed executable:

```bash
claude
```

or:

```bash
claude --resume '<session-id-or-name>'
```

The application must:

- inherit the user environment;
- preserve the selected working directory;
- use the normal Claude Code settings hierarchy;
- use the normal Claude Code authentication;
- display Claude Code's original terminal UI;
- never send prompts directly to Anthropic;
- never reimplement Claude Code permissions;
- never parse terminal output to simulate a chat interface.

### 2.2 No tmux

Do not introduce:

- tmux;
- screen;
- terminal multiplexing daemons;
- detached shells;
- long-lived external supervisor processes.

Each active session is a direct child process attached to a PTY managed by libghostty.

An inactive Claude session does not need a live process. Claude Code provides its own persistent session mechanism.

### 2.3 Fail conservatively

If the application cannot reliably determine a Claude session ID, it must still preserve the terminal session while active and allow resumption through:

```bash
claude --resume
```

Do not make undocumented Claude transcript formats part of the core process lifecycle.

### 2.4 Narrow native boundary

All libghostty interaction must be isolated behind one Java package and, if required, one small native macOS host library.

No UI, project-management, Git, or persistence code may directly use:

- `MemorySegment`;
- `Linker`;
- `MethodHandle`;
- generated libghostty bindings;
- native pointers;
- AppKit handles.

### 2.5 Self-contained runtime image

The application must ship with its own JDK 26-derived runtime image built with `jlink`.

The user must not need:

- a separately installed JDK;
- a separately configured `JAVA_HOME`;
- Gradle;
- JavaFX;
- libghostty;
- any manually installed native application dependency other than the approved `claude` and `git` executables.

The generated application launcher must include all required JVM arguments, including native-access flags.

---

## 3. Initial Scope

### 3.1 Required for version 0.1

- Add an existing local Git repository.
- Remove a repository from the application without deleting files.
- Display repositories in a sidebar.
- Create a new Claude session in a selected repository.
- Assign an application-visible session name.
- Run `claude -n '<name>'` when supported.
- Open several Claude sessions in tabs.
- Close an active terminal process.
- Resume a known session using `claude --resume`.
- Fall back to the official Claude session picker when no stable session identifier is known.
- Browse files in the repository.
- Preview text files with basic syntax highlighting.
- Open a file in the configured external editor.
- Display Git status.
- Display unified diffs.
- Persist project and UI metadata.
- Restore the previous application layout after restart.
- Build and run entirely from Gradle.
- Produce a self-contained executable application image using `jlink`.
- Produce a macOS `.app` wrapper from that image.
- Optionally produce a `.dmg` after the `.app` is stable.

### 3.2 Deferred until after version 0.1

- automatic Git worktree creation;
- Git staging and commits;
- embedded source editing;
- GitHub API integration;
- cloning repositories;
- remote SSH repositories;
- Windows support;
- Linux support;
- Intel macOS support;
- universal binaries;
- application auto-update;
- public notarized distribution;
- terminal process survival after application exit;
- background agents;
- custom Claude chat rendering;
- parsing all Claude JSONL transcript contents;
- moving sessions between repositories;
- running one Claude session simultaneously in several terminal tabs.

### 3.3 Explicit non-goals

Do not build:

- a general IDE;
- another terminal emulator;
- another Git client;
- an Anthropic API client;
- a Claude Desktop replacement;
- an account or authentication system;
- a plugin marketplace;
- collaborative or cloud functionality.

---

## 4. High-Level Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│ JavaFX application                                          │
│                                                             │
│ ┌─────────────┐ ┌─────────────────────────────────────────┐ │
│ │ Repository  │ │ Workspace tabs                          │ │
│ │ sidebar     │ │                                         │ │
│ │             │ │ ┌─────────────────────┬───────────────┐ │ │
│ │ repository  │ │ │ Ghostty terminal    │ File/Git pane │ │ │
│ │  ├ session  │ │ │ running `claude`    │               │ │ │
│ │  ├ session  │ │ │                     │ tree/preview  │ │ │
│ │  └ session  │ │ └─────────────────────┴───────────────┘ │ │
│ └─────────────┘ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│ Terminal abstraction                                        │
│                                                             │
│ TerminalView / TerminalSession / TerminalProcess            │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│ Ghostty adapter                                             │
│                                                             │
│ Generated FFM bindings + handwritten lifecycle wrapper      │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│ Bundled libghostty + optional AppKit host shim              │
└─────────────────────────────────────────────────────────────┘
```

Packaging:

```text
Gradle build
   │
   ├── compile application modules
   ├── compile/copy native libraries
   ├── build custom runtime with jlink
   ├── generate executable launcher
   └── assemble app image
          ├── bin/
          ├── runtime/
          ├── app/
          └── lib/
```

---

## 5. Repository Structure

Use a Gradle multi-project build so the native boundary remains isolated.

```text
claude-project-manager/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/
│       │   └── app/cpm/
│       ├── main/resources/
│       │   ├── app.css
│       │   ├── icons/
│       │   └── syntax/
│       └── test/java/
├── terminal-api/
│   ├── build.gradle.kts
│   └── src/main/java/
├── terminal-ghostty/
│   ├── build.gradle.kts
│   ├── src/main/java/
│   ├── src/generated/java/
│   └── src/test/java/
├── native-host/
│   ├── CMakeLists.txt or build.zig
│   └── src/
│       ├── terminal_host.h
│       └── terminal_host.m
├── third_party/
│   └── ghostty/
├── packaging/
│   ├── macos/
│   │   ├── Info.plist
│   │   ├── entitlements.plist
│   │   ├── launcher-template.sh
│   │   └── app.icns
│   └── scripts/
│       ├── assemble-app-image.sh
│       ├── create-macos-app.sh
│       └── create-dmg.sh
├── docs/
│   ├── architecture.md
│   ├── native-integration.md
│   ├── session-model.md
│   └── packaging.md
└── scripts/
    ├── verify-environment.sh
    ├── build-ghostty.sh
    └── smoke-test-app.sh
```

The project may initially remain a single Gradle module during the feasibility spike. Split it into the modules above once the terminal prototype works.

---

## 6. Technology Decisions

### 6.1 Java

Use JDK 26.

Configure Gradle toolchains:

```kotlin
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}
```

Fail the build with a clear message when JDK 26 is unavailable.

### 6.2 JavaFX

Use JavaFX 26 modules:

- `javafx.base`;
- `javafx.controls`;
- `javafx.graphics`.

Avoid FXML initially. Build views in Java so generated UI changes remain easy to trace and refactor.

Add `javafx.fxml` only if it produces a concrete benefit later.

### 6.3 Build language

Use Gradle Kotlin DSL.

Required top-level commands:

```bash
./gradlew run
./gradlew test
./gradlew integrationTest
./gradlew runtimeImage
./gradlew appImage
./gradlew macApp
./gradlew dmg
```

The exact internal task names may differ, but provide these aliases.

### 6.4 FFM native access

Launch the application with:

```text
--enable-native-access=app.cpm.terminal.ghostty
```

Use `ALL-UNNAMED` only during the earliest non-modular spike.

Prefer a modular application once native loading is stable.

### 6.5 Runtime image

Use `jlink` to build the application runtime image.

The image must contain:

- the JDK modules required by the application;
- JavaFX modules;
- the application modules;
- generated launch scripts;
- all required JVM arguments;
- application resources;
- bundled native libraries.

The launcher must carry JVM arguments such as:

```text
--enable-native-access=app.cpm.terminal.ghostty
-Djava.library.path=$APP_HOME/lib
-Dfile.encoding=UTF-8
-Djava.awt.headless=false
```

Add only arguments that are actually required and verified.

Prefer a Gradle plugin capable of generating a `jlink` application image and launch scripts, or implement the `jlink` command explicitly if the plugin obscures the generated layout.

The build must make the launcher reproducible and inspectable.

### 6.6 Metadata storage

Use SQLite or a small atomic JSON store.

Prefer SQLite if session and repository queries become nontrivial. Prefer JSON for the first spike.

Default location:

```text
~/Library/Application Support/ClaudeProjectManager/
```

Suggested contents:

```text
state.db
logs/
cache/
```

Application metadata must never be stored inside user repositories unless explicitly enabled.

### 6.7 Git integration

Invoke the installed `git` executable.

Do not introduce JGit initially.

Use commands such as:

```bash
git -C <repo> rev-parse --show-toplevel
git -C <repo> status --porcelain=v2 --branch -z
git -C <repo> diff --no-ext-diff --no-color
git -C <repo> diff --cached --no-ext-diff --no-color
git -C <repo> worktree list --porcelain
```

Pass arguments as a process argument list. Never construct a shell command by concatenating paths.

### 6.8 Claude integration

Use the installed `claude` executable.

At startup, discover it using:

1. explicitly configured path;
2. inherited `PATH`;
3. common installation paths only as a convenience fallback.

Run:

```bash
claude --version
```

Store the detected executable path and version.

Do not assume every recent flag is supported. Introduce capability detection based on:

```bash
claude --help
```

Represent capabilities explicitly:

```java
record ClaudeCapabilities(
    boolean supportsName,
    boolean supportsResume,
    boolean supportsForkSession,
    String version
) {}
```

---

## 7. Phase 0 — Feasibility Gates

Do not build the complete application before these gates pass.

### Gate 0A: Build libghostty

Pin Ghostty to an exact commit.

The build must:

- initialize the submodule;
- build libghostty for macOS arm64;
- produce a dylib or static library suitable for application embedding;
- copy public headers to a deterministic build directory;
- record the Ghostty commit in generated build metadata.

Deliverables:

```text
build/native/macos-arm64/libghostty.dylib
build/native/include/ghostty.h
build/generated/ghostty-version.properties
```

Acceptance criteria:

- clean checkout can build the library with one Gradle command;
- no manually installed files are required outside documented build prerequisites;
- rebuilding without source changes is incremental;
- failure output clearly identifies missing Zig/Xcode dependencies.

### Gate 0B: FFM smoke test

Create a small Java command-line program that:

- loads the built libghostty library using FFM;
- invokes a harmless version, initialization, or configuration function;
- validates a returned value;
- shuts down without a crash.

Acceptance criteria:

```bash
./gradlew :terminal-ghostty:ffmSmokeTest
```

must exit successfully.

### Gate 0C: Render a terminal surface

Create the smallest possible JavaFX window that embeds one Ghostty terminal surface.

The spike must establish which model is viable:

#### Preferred model

Libghostty renders into a native macOS view or layer hosted inside the JavaFX window.

#### Alternative model

Libghostty exposes a caller-managed render buffer that JavaFX can display efficiently.

Do not commit to the alternative until its API actually exists and works at the pinned Ghostty commit.

Acceptance criteria:

- a window opens;
- terminal content is rendered;
- window resizing updates terminal dimensions;
- focus works;
- keyboard input reaches the terminal;
- the application closes without a crash.

### Gate 0D: Run an interactive shell

Spawn:

```bash
/bin/zsh -l
```

inside the embedded terminal.

Verify:

- prompt rendering;
- ordinary typing;
- Return;
- Backspace;
- arrow keys;
- Ctrl+C;
- Ctrl+D;
- Option-based key combinations;
- copy and paste;
- selection;
- terminal resizing;
- scrollback;
- Unicode;
- wide characters;
- colour output;
- alternate-screen applications;
- `vim` or another full-screen TUI;
- process exit.

### Gate 0E: Run Claude Code

Run the real CLI in a test repository:

```bash
claude
```

Verify:

- startup;
- workspace trust prompt;
- login/configuration behaviour;
- multiline prompt editing;
- permission prompts;
- streaming output;
- tool execution display;
- Ctrl+C cancellation;
- terminal resizing during output;
- copy/paste;
- quitting and relaunching;
- `claude --resume`;
- inherited managed settings;
- inherited gateway environment variables.

### Gate 0F: Build a self-contained runtime image

Build the first `jlink` image containing:

- the application module;
- JavaFX modules;
- FFM usage;
- generated launcher;
- required JVM arguments;
- bundled libghostty.

Acceptance criteria:

```bash
./gradlew runtimeImage
build/image/bin/claude-project-manager
```

must launch the terminal spike without:

- an external JDK;
- Gradle;
- `JAVA_HOME`;
- native libraries outside the image.

### Gate decision

Proceed with the full application only if Gates 0A–0F pass reliably.

If native-view embedding fails, do not immediately abandon JavaFX. Implement the minimal AppKit host shim described below.

---

## 8. Native macOS Host Shim

Implement this only if JavaFX cannot directly provide the native host expected by libghostty.

The shim must be intentionally small.

Suggested API:

```c
typedef void *cpm_terminal_host_t;

cpm_terminal_host_t cpm_terminal_host_create(
    void *parent_nsview
);

void cpm_terminal_host_set_frame(
    cpm_terminal_host_t host,
    double x,
    double y,
    double width,
    double height
);

void *cpm_terminal_host_content_view(
    cpm_terminal_host_t host
);

void cpm_terminal_host_set_visible(
    cpm_terminal_host_t host,
    bool visible
);

void cpm_terminal_host_set_focused(
    cpm_terminal_host_t host,
    bool focused
);

void cpm_terminal_host_destroy(
    cpm_terminal_host_t host
);
```

Responsibilities:

- create and destroy an AppKit child view;
- attach it to the JavaFX window's native view hierarchy;
- update frame coordinates;
- support visibility changes;
- support focus transfer;
- expose whatever view/layer handle libghostty requires.

It must not:

- spawn Claude;
- manage sessions;
- store application state;
- parse keyboard shortcuts;
- implement terminal rendering;
- implement file browsing;
- know about repositories.

All shim calls must occur on the appropriate macOS UI thread.

Document JavaFX-thread/AppKit-thread interactions explicitly.

---

## 9. Terminal Abstraction

Create a terminal API that hides Ghostty.

```java
public interface TerminalView extends AutoCloseable {
    Node node();

    TerminalSession start(TerminalLaunchRequest request);

    void requestFocus();

    void setVisible(boolean visible);

    @Override
    void close();
}
```

```java
public interface TerminalSession extends AutoCloseable {
    UUID id();

    ProcessState state();

    OptionalLong pid();

    CompletionStage<Integer> exitCode();

    void resize(TerminalSize size);

    void sendText(String text);

    void sendInterrupt();

    void terminate();

    @Override
    void close();
}
```

```java
public record TerminalLaunchRequest(
    Path executable,
    List<String> arguments,
    Path workingDirectory,
    Map<String, String> environment,
    String displayName
) {}
```

```java
public enum ProcessState {
    STARTING,
    RUNNING,
    EXITED,
    FAILED,
    TERMINATING
}
```

Do not expose libghostty pointers or callbacks through this API.

### Lifecycle rules

- Every terminal surface has one owning Java object.
- Native resources are explicitly closed.
- `Cleaner` may be used only as a safety net, not as the normal lifecycle.
- Closing a tab prompts before terminating a running process.
- Application shutdown prompts once for all active processes.
- A force-quit path must exist.
- Callback stubs remain strongly reachable while native code can call them.
- No callback may update JavaFX controls off the JavaFX application thread.
- Native callback exceptions must be caught and logged before returning across the FFM boundary.

---

## 10. Application Domain Model

### 10.1 Repository

```java
record RepositoryId(UUID value) {}

record Repository(
    RepositoryId id,
    Path root,
    String displayName,
    Instant addedAt,
    Instant lastOpenedAt,
    RepositorySettings settings
) {}
```

Repository invariants:

- `root` is absolute and normalized;
- repository existence is revalidated when opened;
- symlink behaviour is explicit;
- Git root is detected with `git rev-parse`;
- duplicate repositories resolve by canonical root path;
- removing a repository removes only application metadata.

### 10.2 Managed Claude session

```java
record ManagedSessionId(UUID value) {}

record ManagedClaudeSession(
    ManagedSessionId id,
    RepositoryId repositoryId,
    String displayName,
    Optional<String> claudeSessionId,
    Optional<String> claudeSessionName,
    Path workingDirectory,
    Optional<Path> worktreeRoot,
    SessionStatus status,
    Instant createdAt,
    Instant lastOpenedAt,
    Optional<Integer> lastExitCode
) {}
```

```java
enum SessionStatus {
    INACTIVE,
    STARTING,
    RUNNING,
    EXITED,
    FAILED,
    MISSING_WORKING_DIRECTORY
}
```

Keep these identifiers distinct:

- application session ID;
- Claude Code session ID;
- Claude Code session name;
- OS process ID.

Never overload one field to represent another.

### 10.3 Workspace state

Persist:

- selected repository;
- selected session;
- open tabs;
- sidebar width;
- right-pane width;
- last selected file;
- expanded repository nodes;
- file-preview visibility;
- Git-pane visibility.

Do not attempt to persist the live native terminal surface.

---

## 11. Session Creation and Resumption

### 11.1 Create a new session

When the user creates a session:

1. Require a repository.
2. Generate a default display name.
3. Allow immediate renaming.
4. Create the application session metadata.
5. Start:

```bash
claude -n '<name>'
```

when the installed version supports `--name`.

Otherwise start:

```bash
claude
```

6. Use the repository root or selected worktree as the working directory.
7. Inherit the application environment.
8. Add only application-specific environment variables that are strictly necessary.
9. Mark the session running after successful process creation.

Do not pass permission-bypass flags.

### 11.2 Resume a session

If a trusted Claude session ID is known:

```bash
claude --resume '<session-id>'
```

If only an explicitly assigned Claude session name is known:

```bash
claude --resume '<name>'
```

If neither is known:

```bash
claude --resume
```

This opens the official session picker.

Always launch from the stored working directory.

If that directory no longer exists:

- do not silently switch to another repository;
- mark the session as missing its directory;
- allow the user to choose a replacement;
- record the reassignment explicitly.

### 11.3 Duplicate-open protection

Do not open the same Claude session in two tabs by default.

Maintain an application-level map:

```text
Claude session ID -> active terminal tab
```

If the user tries to open an already active session:

- focus the existing tab;
- provide an explicit “Open anyway” advanced action only later.

### 11.4 Session identification

Use official CLI-supported identifiers whenever possible.

Do not initially depend on parsing private JSONL formats to discover the session ID of a newly created interactive terminal.

Implement session identification in stages:

#### Stage 1

Application-managed names plus `claude --resume <name>`.

#### Stage 2

Optional discovery from supported Claude commands, if a stable machine-readable command exists in the installed version.

#### Stage 3

Read-only local transcript discovery under `~/.claude/projects/`, isolated behind a `ClaudeSessionDiscovery` interface.

Private file parsing must never be required to launch or resume through the official picker.

---

## 12. Repository Sidebar

Use a two-level tree initially:

```text
java-profiler
  pthread cleanup
  allocation sampling
  JFR context

jdk
  jmethodID investigation
```

Repository row:

- display name;
- current branch;
- dirty indicator;
- number of running sessions.

Session row:

- session name;
- status icon;
- running/idle/failed state;
- optional branch or worktree;
- last-opened time in tooltip.

Context actions:

Repository:

- New Claude session;
- Refresh;
- Open in Finder;
- Open in external editor;
- Remove from manager.

Session:

- Open;
- Resume;
- Rename;
- Stop process;
- Reveal working directory;
- Remove application entry.

Do not delete Claude transcripts from the first version.

---

## 13. Main Workspace

Use a `SplitPane`.

Left/main section:

- one tab per open managed session;
- each tab hosts one Ghostty terminal view;
- tab title displays session name;
- dirty/running/attention state may be reflected with a small icon.

Right section:

- Files;
- Git;
- optionally Session Info.

Suggested structure:

```text
TabPane: terminal tabs
SplitPane:
  terminal area
  inspector area:
    TabPane:
      Files
      Git
      Session
```

Do not create one JavaFX `Stage` per terminal initially.

---

## 14. File Browser

### 14.1 Tree loading

Load directories lazily.

Ignore by default:

```text
.git
.gradle
.idea
build
out
target
node_modules
```

Do not hardcode the ignore logic permanently. Create:

```java
interface FileVisibilityPolicy {
    boolean isVisible(Path path);
}
```

Potential later inputs:

- `.gitignore`;
- application settings;
- user exclusions.

### 14.2 Filesystem watching

Use `WatchService` carefully.

Do not recursively register the entire repository synchronously at startup.

Version 0.1 may use:

- refresh on focus;
- refresh after known Git operations;
- explicit refresh button;
- watchers only for expanded directories.

Debounce refresh events.

### 14.3 Preview

Support:

- plain text;
- Java;
- Kotlin;
- C;
- C++;
- headers;
- Python;
- shell;
- JSON;
- YAML;
- TOML;
- Markdown;
- Gradle files;
- Git diff.

For large files:

- define a configurable threshold, initially 2 MiB;
- show metadata instead of automatically loading;
- provide an explicit “Open anyway” action.

For binary files:

- show file type and size;
- do not attempt text decoding.

Initial preview can use a non-editable text control. Introduce RichTextFX only if needed for practical syntax highlighting or performance.

### 14.4 External editor

Provide a configurable command template.

Examples:

```text
code --goto {file}:{line}:{column}
idea --line {line} {file}
open -a "IntelliJ IDEA" {file}
```

Execute without a shell whenever possible.

---

## 15. Git Panel

### 15.1 Status model

Parse:

```bash
git status --porcelain=v2 --branch -z
```

Represent:

```java
record GitStatus(
    String branch,
    Optional<String> upstream,
    int ahead,
    int behind,
    List<GitFileChange> changes
) {}
```

### 15.2 Display

Group changes:

- staged;
- unstaged;
- untracked;
- conflicts.

Selecting a changed file displays its diff.

### 15.3 Diff loading

Use:

```bash
git diff --no-ext-diff --no-color -- <path>
git diff --cached --no-ext-diff --no-color -- <path>
```

Protect against:

- unusual filenames;
- spaces;
- newlines in paths;
- deleted files;
- renamed files;
- binary files;
- repositories with no commits.

### 15.4 Refresh policy

Refresh:

- when the Git tab becomes visible;
- after a terminal process exits;
- after filesystem changes, debounced;
- through an explicit refresh action.

Do not poll aggressively.

---

## 16. Process Environment

A macOS GUI application may not inherit the same `PATH` as an interactive shell.

Implement explicit environment diagnosis.

At startup display or log:

- application `PATH`;
- detected `claude` path;
- detected `git` path;
- detected shell;
- `claude --version`;
- `git --version`;
- Java version;
- application architecture;
- libghostty commit.

Provide a settings screen allowing explicit executable paths.

Do not silently run the application through:

```bash
zsh -lc
```

for every child process.

If login-shell environment import is required, implement it once as an explicit optional feature:

```bash
/usr/bin/env -i HOME="$HOME" /bin/zsh -l -c 'env -0'
```

Parse the result and show the user what was imported.

Never log secrets or token values.

---

## 17. Persistence

Create a repository abstraction:

```java
interface ApplicationStateRepository {
    ApplicationState load();

    void save(ApplicationState state);
}
```

Required properties:

- atomic writes;
- schema version;
- migration support;
- backup of the previous state;
- recovery from truncated or malformed state;
- no corruption if the application crashes during save.

If using JSON:

1. write to a temporary file;
2. fsync where practical;
3. atomically rename;
4. retain one backup.

If using SQLite:

- enable WAL;
- use transactions;
- include a schema-version table;
- keep migrations deterministic.

---

## 18. Threading Model

### JavaFX application thread

Owns:

- UI controls;
- observable UI state;
- view creation and destruction;
- visible selection state.

### Native/UI integration thread

AppKit view operations must run on the macOS main thread as required.

If this is the same native thread used by JavaFX, document the verified mechanism. Do not assume.

### Background executor

Use virtual threads or a bounded executor for:

- Git commands;
- Claude capability detection;
- file loading;
- metadata persistence;
- process waits;
- directory scanning.

Never block the JavaFX application thread on:

- `Process.waitFor`;
- Git;
- filesystem traversal;
- transcript scanning;
- native terminal process completion.

### Native callbacks

Native callbacks must:

1. validate arguments;
2. copy transient native data before returning where required;
3. catch all Java exceptions;
4. enqueue UI work through `Platform.runLater`;
5. avoid blocking libghostty's rendering or I/O threads.

---

## 19. Logging and Diagnostics

Write logs to:

```text
~/Library/Logs/ClaudeProjectManager/
```

Include:

- application startup;
- Java and JavaFX version;
- architecture;
- native library path;
- Ghostty commit;
- Claude executable and version;
- repository registration;
- process launches without secret environment values;
- process exits;
- native lifecycle operations;
- uncaught exceptions.

Provide:

- “Open Logs” action;
- “Copy Diagnostic Summary” action.

Diagnostic summary must redact:

- API keys;
- authorization headers;
- tokens;
- full environment;
- prompt content;
- session transcript content.

---

## 20. Error Handling

Use user-visible errors that state:

- what failed;
- which executable or path was involved;
- the exit code;
- the relevant stderr excerpt;
- the recovery action.

Examples:

- Claude executable not found.
- Git executable not found.
- Repository path no longer exists.
- Directory is not a Git repository.
- Claude process could not start.
- Native terminal could not initialize.
- Session cannot be found in this working directory.
- Native library architecture mismatch.
- Application lacks permission to access the directory.

Never reduce these to “Something went wrong.”

---

## 21. Security Constraints

- Do not expose an HTTP server.
- Do not listen on network ports.
- Do not add telemetry.
- Do not upload logs.
- Do not inspect or copy authentication tokens.
- Do not modify managed Claude settings.
- Do not bypass Claude permissions.
- Do not pass `--dangerously-skip-permissions`.
- Do not run repository paths through a shell string.
- Treat repository filenames as untrusted input.
- Never interpret file content as application configuration without explicit opt-in.
- Keep terminal process environment inheritance predictable.
- Do not persist full prompts or terminal scrollback unless explicitly added later.

---

## 22. Testing Strategy

### 22.1 Unit tests

Cover:

- repository path normalization;
- duplicate-repository detection;
- application-state serialization;
- state migration;
- Claude capability parsing;
- process argument construction;
- Git porcelain parsing;
- filenames with spaces and unusual characters;
- session lifecycle state transitions;
- executable discovery;
- environment redaction.

### 22.2 Integration tests

Use temporary Git repositories.

Cover:

- clean repository;
- modified tracked file;
- staged file;
- untracked file;
- renamed file;
- deleted file;
- merge conflict;
- repository with no commits;
- Git worktree;
- repository path containing spaces;
- repository path containing Unicode.

### 22.3 Native integration tests

Cover:

- native library load;
- Ghostty initialization;
- terminal create/destroy loop;
- repeated tab creation and closure;
- process start and exit;
- resize storm;
- focus changes;
- application shutdown with active sessions;
- callback invocation after rapid closure;
- absence of callbacks after resource destruction.

Run a stress test that creates and destroys at least 100 terminal surfaces sequentially.

### 22.4 Manual terminal test checklist

Maintain a checked-in checklist covering:

- shell;
- Claude Code;
- Vim;
- coloured output;
- Unicode;
- emoji;
- selection;
- clipboard;
- resizing;
- alternate screen;
- Ctrl+C;
- Ctrl+D;
- Cmd+C;
- Cmd+V;
- Option+arrow;
- Home/End;
- Page Up/Page Down;
- application tab switching;
- application hide/show;
- sleep/wake;
- external display disconnect;
- Retina scaling.

### 22.5 Runtime-image smoke test

On a clean macOS user account:

1. build the runtime image;
2. copy it outside the Gradle build directory;
3. launch it without a system JDK;
4. add a repository;
5. start `/bin/zsh`;
6. start Claude;
7. close and reopen the application;
8. resume the session.

The image must not rely on:

- `JAVA_HOME`;
- Gradle;
- source-tree-relative paths;
- native libraries outside the image.

---

## 23. Packaging and Runtime Image

### 23.1 Primary packaging mechanism

Use `jlink` as the primary packaging mechanism.

The generated image must include:

```text
ClaudeProjectManager/
├── bin/
│   └── claude-project-manager
├── runtime/
│   ├── bin/
│   ├── conf/
│   ├── legal/
│   └── lib/
├── app/
│   ├── app modules/jars
│   └── resources
└── lib/
    ├── libghostty.dylib
    └── libcpm_terminal_host.dylib
```

The exact layout may differ, but it must be deterministic and documented.

### 23.2 Launcher

Generate an executable launcher that:

- resolves its installation directory without depending on the current working directory;
- launches the bundled Java executable;
- sets module path and main module;
- applies required JVM options;
- points native library loading at bundled libraries;
- preserves user arguments;
- returns the application exit code.

Required JVM options must be embedded in build configuration rather than supplied manually by the user.

Example:

```text
--enable-native-access=app.cpm.terminal.ghostty
-Dfile.encoding=UTF-8
-Djava.library.path=<image>/lib
```

Do not add speculative JVM flags.

### 23.3 macOS `.app`

After the raw executable image works, wrap it in:

```text
Claude Project Manager.app/
└── Contents/
    ├── Info.plist
    ├── MacOS/
    │   └── Claude Project Manager
    ├── runtime/
    ├── app/
    ├── Frameworks/
    │   ├── libghostty.dylib
    │   └── libcpm_terminal_host.dylib
    └── Resources/
```

The `.app` launcher may invoke the bundled `jlink` launcher or be generated directly from the same launcher metadata.

Ensure native library lookup does not depend on:

```text
DYLD_LIBRARY_PATH
```

Use loader-relative paths such as `@rpath` or `@loader_path` where appropriate.

### 23.4 Distribution stages

#### Stage 1

Run from Gradle with native libraries in the build directory.

#### Stage 2

Produce a raw `jlink` executable image.

#### Stage 3

Produce an unsigned `.app`.

#### Stage 4

Produce a local `.dmg`.

#### Stage 5

Ad hoc sign nested native components for local Gatekeeper sanity.

#### Stage 6

Add Developer ID signing and notarization only when external distribution is required.

All nested native libraries and executables must be signed in the correct order before signing the outer application bundle.

---

## 24. Build Reproducibility

Pin:

- JDK distribution/version;
- JavaFX version;
- Gradle version;
- Ghostty Git commit;
- Zig version if required;
- native host build tooling.

Generate a build information file:

```properties
application.version=...
git.commit=...
build.timestamp=...
jdk.version=26
javafx.version=26.x
ghostty.commit=...
target.os=macos
target.arch=aarch64
```

Expose this through an About dialog and diagnostic summary.

Do not download unversioned “latest” native artifacts.

---

## 25. Milestones

### Milestone 0 — Project bootstrap

Deliver:

- Gradle wrapper;
- JDK 26 toolchain;
- JavaFX application with one empty window;
- test infrastructure;
- environment verification script;
- basic CI compile/test job on macOS arm64 where available.

Exit criteria:

```bash
./gradlew clean test run
```

works.

### Milestone 1 — libghostty feasibility

Deliver:

- pinned Ghostty source;
- native build task;
- FFM bindings;
- FFM smoke test;
- one terminal surface in JavaFX;
- interactive shell.

Exit criteria:

All Phase 0 gates except Claude and runtime-image packaging pass.

### Milestone 2 — Claude terminal validation

Deliver:

- executable discovery;
- environment inheritance;
- Claude launch;
- Claude resume;
- terminal keyboard/clipboard correctness;
- manual compatibility report.

Exit criteria:

A real Claude Code session is usable for normal work inside the application.

Do not proceed if this remains flaky.

### Milestone 3 — jlink runtime image

Deliver:

- modular application layout;
- custom runtime image;
- generated launcher;
- embedded JVM arguments;
- bundled JavaFX;
- bundled libghostty;
- bundled native host shim if required;
- clean-user smoke test.

Exit criteria:

The terminal spike and Claude Code launch from the generated image without an installed JDK.

### Milestone 4 — Repository manager

Deliver:

- add/remove repository;
- persistence;
- repository sidebar;
- branch and dirty indicators;
- open in Finder/editor.

Exit criteria:

Repositories survive application restart and refresh correctly.

### Milestone 5 — Managed sessions

Deliver:

- new session;
- session naming;
- terminal tabs;
- process state;
- stop/close;
- resume;
- duplicate-open prevention;
- persisted session metadata.

Exit criteria:

At least three Claude sessions can be opened across at least two repositories and resumed after application restart.

### Milestone 6 — File browser and preview

Deliver:

- lazy file tree;
- preview;
- binary/large-file handling;
- external-editor integration;
- refresh behaviour.

Exit criteria:

The repository can be navigated comfortably without leaving the application.

### Milestone 7 — Git inspection

Deliver:

- Git status;
- grouped changes;
- staged and unstaged diff;
- branch/ahead/behind;
- refresh.

Exit criteria:

Git changes made by Claude appear reliably without restarting the application.

### Milestone 8 — macOS application packaging

Deliver:

- `.app`;
- optional `.dmg`;
- native-library bundling;
- local signing;
- clean-account smoke test;
- packaging documentation.

Exit criteria:

The application runs from the packaged bundle without Gradle or an installed JDK.

### Milestone 9 — Hardening

Deliver:

- stress tests;
- shutdown handling;
- crash recovery;
- state backup;
- diagnostics;
- performance profiling;
- sleep/wake testing;
- external-display testing.

Exit criteria:

The application is reliable enough to replace separate terminal windows for daily work.

---

## 26. Performance Targets

- Application window visible within 3 seconds on a warm start.
- Repository sidebar restored within 1 second after window display.
- Terminal input latency not perceptibly worse than Ghostty itself.
- File selection preview under 200 ms for ordinary source files.
- Git status refresh under 1 second for normal repositories.
- No full recursive repository scan on the JavaFX application thread.
- Idle application CPU near zero.
- Hidden terminal tabs must not continuously repaint.
- Memory must return close to baseline after repeatedly opening and closing terminal tabs.

Record baseline measurements after Milestone 2.

---

## 27. Important Implementation Rules for Claude Code

1. Work milestone by milestone.
2. Do not scaffold later milestones before the current milestone works.
3. Keep the application runnable after every meaningful commit.
4. Run tests after each structural change.
5. Do not replace failing native integration with mocked success.
6. Do not invent libghostty APIs. Inspect the pinned headers and examples.
7. Do not assume JavaFX exposes an `NSView` through a public API. Verify it.
8. Do not use internal JavaFX APIs without documenting the exact dependency and failure risk.
9. Prefer a tiny explicit native host shim over extensive reflection into JavaFX internals.
10. Do not parse private Claude session files until basic launch and resume work through official CLI commands.
11. Never change Claude authentication or managed-settings files.
12. Never use a shell where direct process arguments are sufficient.
13. Add cleanup and ownership logic when native objects are introduced, not later.
14. Add a focused regression test for every native crash or lifecycle bug.
15. Record unresolved risks in `docs/architecture.md`.
16. Before introducing a dependency, explain what problem it solves and why existing dependencies cannot solve it.
17. Keep generated FFM sources separate from handwritten code.
18. Make generated bindings reproducible from the pinned header.
19. Treat warnings from native compilation, FFM, `jlink`, and packaging as errors where practical.
20. Do not claim a milestone complete until its exit criteria have been manually or automatically verified.
21. Treat the `jlink` runtime image as the canonical deployable artifact.
22. Keep JVM arguments in version-controlled build configuration.
23. Make the generated launcher inspectable and reproducible.
24. Do not require users to install or select a JDK.
25. Do not introduce `jpackage` as the core runtime builder; it may be used only as an optional wrapper around the already working `jlink` image.

---

## 28. First Execution Sequence

Begin with the following tasks only.

### Task 1

Create the Gradle JDK 26 and JavaFX 26 project.

Add:

- application entry point;
- one JavaFX window;
- unit-test setup;
- Gradle wrapper;
- environment verification;
- README with exact development prerequisites.

### Task 2

Add Ghostty as a pinned Git submodule.

Determine from the pinned repository:

- supported libghostty build command;
- produced native artifacts;
- public C headers;
- required macOS frameworks;
- architecture settings;
- embedding examples;
- lifecycle and thread constraints.

Write findings to:

```text
docs/native-integration.md
```

Do not guess.

### Task 3

Create a Gradle task that builds libghostty for macOS arm64.

Make the output deterministic and place it under `build/native`.

### Task 4

Generate or handwrite the smallest FFM binding needed to initialize libghostty.

Run it from a command-line smoke test.

### Task 5

Investigate native surface embedding.

Produce a minimal JavaFX terminal window with no repository manager or Claude-specific code.

### Task 6

Run `/bin/zsh -l`.

Complete the terminal interaction checklist.

### Task 7

Run the installed `claude` CLI in a temporary test repository.

Document every incompatibility before proceeding.

### Task 8

Create the first `jlink` runtime image.

The image must:

- bundle Java 26 runtime modules;
- bundle JavaFX;
- bundle the application;
- bundle libghostty;
- include the native-access JVM option;
- run without `JAVA_HOME`;
- run outside the source tree.

Stop after Task 8 and report:

- what works;
- what does not;
- whether an AppKit shim was required;
- what undocumented or unstable APIs are being used;
- native ownership rules;
- packaging implications;
- exact generated runtime-image layout;
- exact launcher JVM arguments;
- whether the architecture remains viable.

Do not implement project management until this report is complete.

---

## 29. Definition of Version 0.1 Complete

Version 0.1 is complete when:

- the self-contained runtime image launches without an installed JDK;
- the macOS `.app` wrapper launches the same image;
- the user can register multiple Git repositories;
- each repository can contain multiple managed Claude sessions;
- several sessions can be active in terminal tabs;
- inactive sessions can be resumed in their correct repository or worktree;
- repository files can be browsed and previewed;
- Git changes and diffs can be inspected;
- Claude runs exclusively through the local CLI;
- no tmux or external terminal multiplexer is involved;
- closing and reopening the app does not lose application-managed session metadata;
- all required JVM arguments are embedded in the generated launcher;
- no known reproducible native crash remains in normal session creation, resizing, tab switching, or shutdown;
- the build and packaging process is documented and reproducible.
