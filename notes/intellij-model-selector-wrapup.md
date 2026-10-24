# intellij-model-selector — wrap-up

## Problem
"No option to specify the model when starting a new drydock task." The
starting-model feature (#45, `7b6547c3`) landed only in the standalone app
tree; the IntelliJ plugin has its own forked copy of the agent layer and never
received it, so every IDE-launched session ran the harness default.

## Settled findings (verified against the code)
- The plugin's `CreateContext` had no `model` component; its Pi/Claude/Codex
  providers rendered no model flag (`--model`, `--model`, `-m` respectively in
  the app tree).
- All IDE task entry points (⌃⌥N, tool-window button, Git branch menus, issue
  Implement/Triage) funnel through `NewTaskDialog` → `TaskLauncher` →
  `SessionDialogs.launchNewSession` → `DrydockSessionService` — one dialog
  covers them all.
- The PR-review launch auto-picks the agent with no dialog.

## What was implemented (uncommitted, on `missing-model-selector`)
- **Agent layer (main source set)**: `CreateContext.model`; SPI
  `AgentProvider.modelSuggestions()`; cached `AgentRegistry.modelSuggestions`;
  new `PiModelCatalog` (`pi --list-models`, via `ProcessRunner`); model flags
  in Pi/Claude/Codex providers; Claude suggestions `fable/opus/sonnet`, Codex
  none.
- **Memory**: `RepositorySettings.lastUsedModels` (per project+harness, port of
  the app's), codec member `lastUsedModels`, lenient per-entry decode;
  recorded at RUNNING in `DrydockSessionService.finalizeCreate` via
  `StartedLaunch.model` (launch-scoped — `ManagedAgentSession` deliberately
  untouched).
- **New Task dialog**: new shared Swing picker `ModelField` (editable,
  type-to-filter, free text first-class, per-harness editor memory, async
  suggestions); `NewTaskPlan.Choice.model`.
- **Review**: two actions in `TaskTreePanel` — quick `Review (<model>)`
  (dynamic label from the in-memory state store, launches with the remembered
  model) and `Review…` → `ReviewModelDialog` (asks, launch persists).

## Refuted dead end
Adding `model` to `ManagedAgentSession` — model is a launch-time input only
(resumes carry none), so it rides `StartedLaunch` and the session record/state
schema stay untouched.

## Verification
`./gradlew test` in `intellij-plugin/`: 2304 tests, 0 failures. `:app:compileJava`
clean (no app-tree changes).

## Open questions / next step
- None blocking. The diff is uncommitted; the user commits on request only.
