# Pi attention balloon — wrap-up (state of 2026-10-24)

## Settled findings

- **Root cause of "no balloon for Pi"**: `PiAgentProvider.activity()` returned
  `Optional.empty()` in BOTH the app and the plugin ("no activity badges",
  "Plan A: Claude only"). `SessionActivityMonitor` → `AttentionNotifier` is
  driven entirely by activity state files, and only `ClaudeHookInstaller`
  writes those. Nothing regressed — the balloon never existed for pi.
- The "pop" the user hears is pi's terminal bell in the console, not an IDE
  notification.
- **Pi's extension events** (verified against installed pi 1.3.0
  `dist/core/extensions/types.d.ts`): `agent_settled` is the true turn-end
  (no retry/compaction/queued continuation follows); `ui_prompt_start/end`
  is the blocking-user-prompt signal (Claude `permission_prompt` analog);
  `input` marks the user speaking. There is NO built-in inactivity event.
- **Fix (implemented, app + plugin)**: a new pi extension
  `drydock-activity.ts` writes the same ≤64-byte state words as Claude's
  hook script (`busy`/`idle`/`attention`, one file per session id from
  `ctx.sessionManager.getSessionId()`, write-then-rename) into the shared
  activity directory, passed via `DRYDOCK_ACTIVITY_DIR`. Idle→attention is a
  60 s extension-side timer (mirrors Claude's "amber a minute after
  finishing"), guarded by a monotonic generation counter.
- `PiActivityReporter` mirrors `ClaudeActivityReporter` (install + purge
  stale state at startup; `settingsFile()` always empty — pi enables
  reporting via `-e` in the launch command, and `buildResumeCommand` reuses
  `piCommand`, so resume is covered). Zero changes to
  `SessionActivityMonitor`/`AttentionNotifier`/`SessionActivityWatcher`.

## Verification

- Plugin: `intellij-plugin` `test --tests app.drydock.agent.providers.pi.*`
  green (43 provider tests incl. new command-shape assertions via the
  `withoutActivity` helper + `PiActivityReporterTest`); full plugin suite
  green.
- App: `:app:test --tests app.drydock.agent.providers.pi.*` green.
- Pre-existing, unrelated (reproduces on a stashed baseline): 5 `:app:test`
  failures in `GitStatusServiceTest` (×4) and
  `WorkspaceMcpSessionContextTest` (×1) — `git worktree add` timing out
  after 15 s under `/opt/homebrew/bin/git` in test fixtures.

## Open questions / next step

- Live check: run a pi session from the IDE, let it settle, wait 60 s —
  balloon should appear; switching to the tab should acknowledge.
- If balloons feel noisy, the 60 s constant is the knob.

## Follow-on work (same session, 2026-10-24)

- **Empty model combos (fixed)**: `PiModelCatalog` forked `pi --list-models`,
  whose launcher needs `node` on PATH — a Finder-launched IDE process has
  launchd's bare PATH, so the probe died with `env: node: No such file or
  directory` and every pi model combo came up empty. Primary source is now a
  direct read of `<agentDir>/models.json` (`PI_CODING_AGENT_DIR` honored;
  ids verbatim, the same strings `--list-models` prints in its model
  column); the CLI probe (with the node-PATH prefix the launch command
  always had) remains the fallback for installs without a models.json.
  Test seam: `drydock.pi.modelsJson` system property (user-global file).
- **Harness pick (fixed)**: `ReviewModelDialog` now offers the installed
  coding agents (radio row, seeded to the repo's last-used); model + tier
  pickers re-seed per harness from the repo's last-used values.
  `PullRequestReview.ModelChoice` carries the picked harness. The quick
  `Review (default)` keeps the auto-pick. Implement/Triage already used the
  New task dialog's model picker — their empty combos were this same bug.
- Not covered by automated tests: the dialog's Swing layout/radio behavior
  (compile-only); the flows' hand-off logic is the previously tested
  `awaitModelChoice`.
