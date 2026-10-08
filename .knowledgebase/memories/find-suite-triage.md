---
id: find-suite-triage
title: Rerun a failing test class in isolation before chasing it as a regression; two known non-regression modes exist
kind: finding
tags: [testing, triage, flakes, environment]
applies_to: [app/src/test/java]
source: improve-review-tour/find-suite-triage
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


Before debugging a full-suite failure as a regression, rerun the failing
class alone (`./gradlew :app:test --tests "<class>"`): both known
non-regression modes pass or fail identically in isolation.

- `git worktree add` timing out after 15s in temp dirs:
  GitStatusServiceTest (4 tests) and WorkspaceMcpSessionContextTest (1)
  fail with `McpWorktreeMayExistException` / `GitCommandFailedException
  ... timed out after 15s (killed)` — environment (spawn slowness under
  load), not the diff; the signature is a spawn timeout, never an
  assertion. Machine-dependent: a clean machine may not reproduce.
- Load flakes: SearchRailViewTest.repoScopeDimsOutOfChangeFilesRatherThanHidingThem
  has failed once under full-suite load and passed in isolation.

The distinction matters for verification claims: report these as
environmental with the isolation rerun as evidence, not as silently
ignored failures.
