# integrated-review — wrap-up (2026-10-21)

Cairn investigation `integrated-review` (graph: `~/.cairn/investigations/btraceio--drydock/integrated-review/integrated-review`).
Question: how to integrate Drydock's review approach with what the IntelliJ Platform provides. Research only.
All platform claims are bytecode-checked against IU-2025.3.3 (253.31033.145); nothing was run in a live IDE.

## Settled
- The plugin already compiles the whole review core (scopes, findings, verdicts, tour, ReadingPath, MCP router),
  but no platform code uses it. Agents launched from the plugin reach no `review_*` tool (`find-plugin-review-core-dormant`).
- Agents address review state by git line keys `n<N>`/`o<N>`, hunk ids and hunk digests. Git's diff stays the
  authority for hunks. `DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER` lets the IDE viewer draw exactly those hunks
  (`find-line-key-contract`, `hyp-git-diff-stays-authoritative`).
- Review tab: the stock Local Changes view cannot host branch scope. A `changesViewContent` tab on
  `AsyncChangesBrowserBase(showCheckboxes=false)` uses public API. It lands in the Commit tool window only when that
  window is shown, so resolve the host with `ChangesViewContentManager.getToolWindowIdFor` (`find-review-tab-*`).
- Findings in the diff viewer and editor: the collaboration-tools framework fits. Its (Side, line) converters map 1:1
  to line keys, and humans do write line comments. It is coroutine-based and its entry points change between
  releases, so wrap it in a thin Kotlin adapter. The plugin can depend on the IDE's bundled Kotlin 2.2 and
  coroutines 1.10.1-intellij-5. Plain-API fallback exists, and those signatures are stable from 2025.3 to 2026.2
  (`find-java-kotlin-bridge`, `find-collab-model-matches-drydock`, `find-public-api-stable-across-releases`).
- MCP: keep Drydock's own McpServer for `review_*`. The IDE server exists only when the user enabled it. It
  can identify sessions (`IJ_MCP_HEADER_*` → `McpCallInfo.getHeaders`), but use it only for IDE-only extras.
- Tour: T2 (native viewer). Hide PREDICT rows with a synthetic right-side text, no folding. The step panel goes in
  the chain-level `BOTTOM_PANEL`, which the processor constructor reads once.
- Hand-offs need a `submitPrompt` on `AgentExecutionRegistry` (write to the PTY).
- PSI and inspections can only upgrade the existing tiers. Agents may run in other windows, and the index can be
  in dumb mode. Enabling review is the first time tree-sitter's native libraries load inside the IDE JVM (a crash risk).
- State: plugin-owned stores next to the plugin's `state.json`. Keep the gh-based submit.
- Synthesis: `find-integration-blueprint`.

## Refuted
Stock Changes view for branch scope; IDE MCP server as the review host; folding-based PREDICT hiding;
the `changesGroupingPolicy` EP for sections; PSI *replacing* tree-sitter.

## Open
- `hyp-ide-inspections-as-evidence`: `CodeSmellDetector.findCodeSmells` runs on a background thread. Value vs noise is untested.
- `q-split-mode-remote-dev`: risk only; cannot be checked offline.
- Known issue, not fixed: a `startSession` that races a finished delete brings the row back. See `docs/known-issues.md`.

## Next step
P1 "Review backbone" is implemented (2026-10-22) through the dev-process pipeline.
- Spec: `docs/superpowers/specs/2026-10-22-intellij-integrated-review-design.md`. The user chose P1 only and accepted all 8 defaults.
- Plan: `docs/superpowers/plans/2026-10-22-intellij-integrated-review.md`, 9 tasks in 4 waves.
- Two in-depth review workflows ran after the build; every confirmed finding is fixed:
  - `wf_aa0140fc-beb`: 7 findings. Fixes: `LaunchClaims`, `holdsAccess` and NoTools, instructions name only served tools, late backend closed on dispose, honest `PromptOutcome`, scope refused while another running session holds it.
  - `wf_a0822545-9a8`: 7 low findings. Fixes: `Error` leaked a claim; the `finish` contract; delete during launch is refused; access released when the launch command does not carry the credential; an ended agent is Refused, not NoTools; test gaps closed and checked by reintroducing each defect.
- Gotchas: `find-p1-impl-gotchas`. Run Gradle with `JAVA_HOME=~/.sdkman/candidates/java/21.0.11-tem`.
- Two test failures are pre-existing and also fail on a clean HEAD checkout: the flaky `testAReleasedAgentKeepsRunningAndIsNotShown`, and the app's `adoptingARemoteOnlyBranch…`.
- Live `runIde` check (2026-10-22): a Claude task loaded the `drydock` MCP server. But `submitPrompt` lost the start of the review prompt (729 of 2773 chars arrived), and the user had to press Enter. It is now written as a bracketed paste (`find-prompt-needs-bracketed-paste`); a repro against a real Claude TUI delivered 3085/3085 chars and submitted it.
- Not run live yet: tree-sitter natives inside the IDE JVM. Retest on a task that has a change once the prompt fix lands.
- Next: rerun the live check, then P2 (Review tab and finding inlays) after live spikes.

Live spikes for later phases: Review tab and collab inlays (with the Kotlin adapter build); CUSTOM_DIFF_COMPUTER
rendering; T2 PREDICT swap and BOTTOM_PANEL; tree-sitter natives in the IDE JVM; PTY prompt injection for all
three agents; S2 dumb-mode timing.
