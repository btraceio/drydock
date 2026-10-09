# Per-launch-path state directories

Two app instances running side by side — a kept-running working install and
the image of the build under test, which is how this repository verifies a
release — used to share one state directory. Each held the same
`state.json` in memory and wrote it through its own
`ApplicationStateStore` single writer: one instance's save deleted the
other's sessions, and the second launch's `McpConfigWriter.purgeStale()`
destroyed the first's per-session MCP tokens while their agents were alive.
An intermediate revision instead COPIED the shared directory's whole content
into each fresh install's directory on its first start — which re-created
the same problem one level down: the new instance started on the other
instance's open sessions, tabs, UI arrangement and reviews, as if they were
its own. Instances share nothing: state on disk belongs to the launch path
that wrote it.

## Layout

    ~/Library/Application Support/ClaudeProjectManager/
      state.json                                  ← the shared pre-per-install directory
      annotations.json, review-tours.json, …          (an old install's archive;
      mcp/, hooks/, activity/, eval/, pi/, …           no current code reads it)
      install-<id>/                               ← per launch path, from the code source
        state.json
        annotations.json, review-tours.json, …
        mcp/, hooks/, activity/, eval/, pi/, …

Every path the app derives from the state file — `state.json`'s siblings
(annotations, tour records, Explorer trails), `<state>/activity`,
`<state>/mcp`, `<state>/hooks`, `<state>/eval`, `<state>/pi`, the eval
containers — hangs off `<state file>.getParent()`, so one resolution point
(`StateDirectory`) moves all of them together.

The exception is deliberate: `~/.drydock/config.json` stays shared. It is a
user setting (`worktreesDirectory`), not instance state — every install
reading the same worktree root is the desired behavior, and the file is a
rarely-written two-key JSON.

## The install id

`installDirectoryName` hashes the classpath entry the entry class actually
loaded from (`getProtectionDomain().getCodeSource()`, realpath'd) — the
first 10 hex of SHA-256 — so:

- an install keeps its directory across restarts **and rebuilds** (the
  install's own jar path is the identity; nothing is stored, so nothing can
  be lost),
- the `build/dist` bundle, a runtime image, and a `gradlew run` of the
  working tree are three distinct installs with three distinct directories,
- a launch whose code source cannot be resolved (bootstrap classpath) keeps
  the shared directory it has always used.

There is no "primary" install and nothing migrates between them. The
identity is the install LOCATION, so installs are expected to live stably —
`/Applications/Drydock.app` across versions, `build/dist` and `build/image`
across rebuilds. Moving (or re-checking-out at another path) an install
means a new state directory: its repositories are re-registered once, and
the old directory stays until deleted.

## First start of an install: empty

A fresh install directory has no repositories, no sessions, no UI state —
nothing is read from or copied from another instance's directory, including
the pre-per-install shared directory (that copy was tried and reverted, see
the top). An EXISTING install's first start after this change is therefore
empty too: its repositories are registered once, and the sessions it had
are in the shared directory — which no current code reads. The old
directory is the old install's archive, not a migration source.

Session rows in particular never carry over: a RUNNING row means "a live
instance's hosted process", and a process spawned by one instance cannot be
owned or resumed by another — the sessions a new instance shows are the
ones it launched itself.

## One running instance per install directory

Per-install directories fix instances of DIFFERENT installs. Two launches
of the SAME install would reintroduce every defect above one level down —
so startup takes an exclusive advisory `FileLock` on
`<state directory>/instance.lock` before any service is built, and a second
launch is refused with an alert. The lock is held for the process lifetime
(closed last in `DrydockApplication.stop()`), OS-released on process death,
so there is no stale lock to clean up.

## Where this is decided in code

- `app.drydock.state.StateDirectory` — naming, resolution, the instance
  lock. Resolution is side-effect free; the directory is created when the
  state store first writes (and by the lock).
- `app.drydock.DrydockApplication#startOnFxThread` — resolution order:
  `app.drydock.diag.stateFile` override (a throwaway file for automated
  verification, and no per-launch identity at all) → per-launch-path state
  file → instance lock → refuse second instance.
- `app.drydock.state.JsonApplicationStateRepository` — the shared
  pre-per-install location survives only as `defaultStateFile()`, the
  no-identity fallback.