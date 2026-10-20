# drydock-intellij wrap-up (v0 extraction probe)

## Settled
- Feasibility report: `docs/IntellijPluginFeasibility.md` (concepts, mapping,
  risks, phasing). User decisions: repo-manager tool window shape; extraction
  first (library factor-out later if Drydock standalone stays valuable); v0
  probe approved.
- Probe lives at `intellij-plugin/` — standalone Gradle project (Gradle 9.6.1,
  plain `java` plugin), packages copied with original `app.drydock.*` names.
  NOT part of Drydock's build.
- `compileJava compilePlatformJava` green at `--release 21` (~5s): extracted
  core + tool-window/configurable glue fit JBR 21. Glue compiles against the
  LOCAL IntelliJ 2025.3.3 `Contents/lib/*.jar` (`-Pdrydock.intellijHome=...`).
- Hidden boundary deps found by javac (not grep): `app.drydock.app.
  LoginShellEnvironment` (FFM; preview on 21 → rewritten in probe as
  getenv("PATH") + no-op merge; IDE owns PATH repair), `app.drydock.ui.
  AgentLabels` (FX-free, copied as-is). `Path.resolve(String,String...)` (JDK
  23+) in `state/StateDirectory` + `config/UserConfigTest` patched to chained
  resolves.
- IntelliJ Platform Gradle plugin is a deadend in the probe (forces ~1GB IDE
  download before compiling). Revisit only for real packaging/verifier/runIde.

## Refuted dead-ends
- "FX-free grep proves extraction cleanliness" — it missed two load-bearing
  classes; javac against --release 21 is the boundary check.

## Open questions
- Test suite SOLVED: the 5 worktree-add failures are environmental, not a port
  issue. Chain: probe's isolated empty GIT_CONFIG_GLOBAL → git auto-detects
  committer ident for reflog writes → hostname lookup → this host's hostname
  (COMP-JVJJQ7R94Y) doesn't resolve (dscacheutil probe: 10.5s) → ~5s per
  spawned git child → 3 children per worktree add → 15s command timeout.
  Minimal repro: `git branch b1 HEAD` = 5.3s with empty config, 0.3s with
  user.name/email set. Fix: fixture ident in the isolated config
  (GIT_CONFIG_COUNT 1→3). Failing classes now pass in 10s. Note: drydock's own
  app/build.gradle.kts has the same empty-config isolation and pays the same
  tax on this machine.
- Sweep core for further JDK 23+ APIs.

## Immediate next step
(none — probe v0 complete; awaiting direction on v1.)
Read the test log tail: last "started" test = the wedge; exclude/fix, then get
the full `./gradlew test` green and report the probe result.
