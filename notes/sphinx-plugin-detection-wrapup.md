# sphinx-plugin-detection — wrap-up

Investigating (2026-10-27/28, worktree `drydock-check-if-it`): can Drydock
detect that the target harness has the sphinx plugin installed, and if so
offer a "Review in Sphinx" action for PRs and worktrees — app AND IntelliJ
plugin?

## Current best answer

DONE. Feasible and implemented in both trees on `check-if-it` (uncommitted).

## Settled findings

- `find-sphinx-detection-feasible` — per-harness detection paths (Claude
  installed_plugins.json slash command; Pi settings.json packages skill
  request; Codex/Terminal unavailable).
- `find-drydock-launch-plumbing-exists` — the reused launch/prompt plumbing.
- `dec-sphinx-action-requirements` — user-confirmed UX decisions.
- `find-sphinx-action-implemented` — what was built where, and verification.

## Refuted dead-ends

None.

## Open questions

None blocking. Noted limitations: Claude project-scope plugin enablement
(`.claude/settings.json` enabledPlugins without a user install) is an
accepted false negative; remote repositories never offer the action (the
probe describes the local machine's plugin cache).

## Next step

User review of the diff; commit in logical chunks (core / app UI / plugin).
