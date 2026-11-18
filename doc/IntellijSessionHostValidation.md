# IntelliJ session host — validation record

Status: **BASELINE BUILD PREPARED; MANUAL BASELINE NOT OBSERVED, NOT SIGNED.** The build result below is verified; no manual matrix result is a pass.
The operator explicitly approved **Implement now, compare later** after the baseline binary was preserved: preparation satisfies the pre-edit prerequisite, while baseline/candidate observations and sign-off remain mandatory final gates. See `IntellijSessionHost/OperatorApprovals.md`.
Nothing in the manual matrix is operator evidence until the operator fills and initials it. Automated test
results are never recorded in the manual matrix (INV-25).

## Baseline build (unmodified plugin)

| Item | Value |
|---|---|
| Pinned commit | `ceee22659b1ecd84f18cf7c5ee07adc0edd05200` (branch cheerful-silver-otter) |
| Source diff vs pin | `git diff --quiet ceee22659b1ecd84f18cf7c5ee07adc0edd05200 -- intellij-plugin` → no diff |
| Command | `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-tem ./intellij-plugin/gradlew -p intellij-plugin buildPlugin` → BUILD SUCCESSFUL |
| Artifact | `intellij-plugin/build/distributions/drydock-intellij-0.1.0.zip` (9,945,524 bytes) |
| SHA-256 | `b8c4a22e38034fd83891c1f5f2e15c0f7b829dfb758a42511e985841af2a4928` |
| Preserved copy | `/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-sandbox/artifact/drydock-intellij-0.1.0.zip` |
| Full log | `/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-buildPlugin-9909.log` |
| IDE for observation | IntelliJ IDEA Ultimate 263.7031.23 (build dependency, Gradle cache; no IDE was launched by the build: `buildSearchableOptions` is disabled in `build.gradle.kts`) |

## Isolation (path separation, NOT an OS sandbox)

Source trace at the pinned commit: the plugin's session catalog/hooks/activity root is
`PathManager.getConfigPath()/drydock` (`DrydockPluginService.defaultBaseDir`; `ClaudeHookInstaller` under it).
Everything else resolves from `user.home`/env: `~/.drydock/{projects.json,config.json,tasks,lsp}` (only `drydock.projectsFile`
and `drydock.tasksDirectory`/`DRYDOCK_TASKS_DIRECTORY` have overrides), the read-only `ClaudeProjectManager` seed,
`~/.claude/projects`, `~/.codex/sessions`, `~/.pi/agent`, `~/.ssh/config`, login-shell rc files. `runIde` is NOT used: it
redirects only projects and tasks. The sandbox launch sets idea.config/system/plugins/log paths, `-Duser.home`, `HOME`, the two
Drydock overrides and starts with `env -i`. Sandbox root: `/private/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-sandbox` (unzipped baseline plugin in `plugins/`).

VM options: `idea.vmoptions` = the shipped `bin/idea.vmoptions` (26 lines) + our overrides. The native launcher strings show a
"main VM options file" step then a "user VM options file" step, but whether `IDEA_VM_OPTIONS` replaces or adds to the main file
is not established from the distribution, so defaults are preserved in the file (duplicates are harmless under either reading).

Not proven / not covered: macOS Keychain access, external CLI wrapper or global configuration (e.g. provider CLIs resolving
accounts independently of `HOME`), network, other processes. Provider CLIs in the sandbox HOME are unauthenticated unless the
operator logs in there. This does not guarantee safety of provider authentication. No credentials or catalogs were copied in.
Before/after metadata snapshots (`diag-*.sh`) are non-attributable diagnostics only: the user's own live Drydock may change the
same files and an unchanged result proves nothing.

## Procedure (operator-authorized; T0 launched nothing)

1. `OPERATOR_AUTHORIZED=1 sh /private/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-sandbox/launch-sandbox-ide.sh [repo-dir-name]` — preflight refuses on: non-canonical or real-root-nested
   sandbox, wrong idea.properties / vmoptions, missing dirs/executables, escaping symlinks, repo argument that is not a single
   directory name inside `repos/`. Records pid in `ide.pid`.
2. **Immediately** `sh /private/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-sandbox/verify-runtime-paths.sh` (jcmd on the pid → restricted `runtime-properties.log`). Must print
   `PATHS PROVEN`; otherwise close the IDE and stop. Do not register repos, authenticate, or start provider sessions before that.
3. Put scratch git repos under `/private/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-sandbox/repos`; create twenty sessions per the matrix; time each row.
4. Close the sandbox IDE; `sh /private/var/folders/gb/lzs4lkjs2194q_sd_f3czyy80000gp/T/drydock-session-host-dev.0Fdj1r/T0-sandbox/diag-compare.sh` (exit 3 = change seen, inspect; not a verdict either way). Record here.

## Baseline matrix (unmodified plugin; to be filled by the operator)

| Starting state | Providers | Window sizes | Observation | Result (pass/fail/untested) | Operator sign-off |
|---|---|---|---|---|---|
| 20 sessions open, none focused; retrieve a named session | | | time / steps / tab count | untested | |
| A/B toggle between two sessions ×10 | | | effort per switch; repeatability spread | untested | |
| code → agent → code return | | | steps to return to the same editor and caret | untested | |
| Tab count with 20 sessions | | | editor tabs / navigator rows | untested | |
| Output burst in a background session, switch away and back | | | latency, UI stalls | untested | |
| Idle with 20 sessions | | | CPU %, heap MB (sandbox IDE) | untested | |
| Sustained output burst | | | CPU %, heap MB peak | untested | |
| Repeat full sequence ×3 | | | run-to-run variance | untested | |

Window sizes to cover: (fill, e.g. default / small / maximized). The existing research editor inventory is not a timed baseline.

Runtime path verification (PATHS PROVEN? pid/time): ______   Diagnostic compare: ______   Observer: ______   Date: ______
Manual baseline observation is complete only when every row above is filled and initialed by the operator. Its deferral permits implementation, not a claim of full verification.
