# improve-review-tour — wrap-up (refill artifact)

Read this first on resume, then `cairn current` / the graph INDEX.

## Where we are
Investigation `improve-review-tour`, active lock held by THIS pi session
(resumed after the embedded drydock-MCP session released it; if the lock
is foreign again, `resume improve-review-tour` is blocked by a live-lock
guard — write nodes without rebinding instead, and re-check the lock
state before concluding it is held).

Branch `feat/review_tour`, worktree
`.worktrees/drydock-review-tour`. All work committed and signed:

- 5bfeb2d2 — scoped usage binding, tier 2 (q-lsp-resolution-path)
- 734d4d73 — deep review ask after the tour (q-post-tour-deep-review)
- 563d87f3 — tour diagrams: monospace + staged reveals (q-tour-diagrams)
- 5f9122af — ask-answer inline (q-ask-answer-inline)
- 5ed0710c — spec: submit curate-then-post section
- 482e849e — fresh-head re-verification (q-publish-reverify)
- 062c0a48 — editable findings in submit sheet (q-findings-editable-before-post)
- 4df0c992 — confirmed-posts-by-default + composer gestures (q-submit-posts-to-pr)
- earlier: fan-in de-noise, agent contract, peek usages, ask-agent on
  findings, teaching rules, reveal fallback, choice scatter, bracket
  keys, find bar, knowledgebase commit 41322f90.

## Settled decisions (do not re-litigate)
- Ask answers: pending-question registry + `review_ask_answer` MCP tool
  (the review_check pattern). Foreign-session answers refused as unknown
  WITHOUT consuming the question. Explorer's `a` unchanged (its ask site
  is the conversation).
- Edits in the submit sheet change the POSTED text only; board keeps the
  original + "edited" chip. Persisting edits into threads is OPEN.
- Fresh-head verify is anchors-mechanical; validity stays with the deep
  review round. Patch-only scopes skip (no local head).
- Choice scatter only at the agent boundary (TourCodec.stepsFromAgent).
- rg `-r` REPLACES matches in output — it once manufactured a phantom
  regression (node dead-rg-replace-flag).

## Remaining queue
EMPTY — every cairn question node is resolved and committed. Follow-ups
recorded but not asked for: LSP tier 3 (its own spec, per §2/§6); the
fan-in scan over the scoped tier; non-Java binding strategies.

Settled this round (tier 2): binding requires the receiver statically
derivable AND resolution to the peek's own declaration (unanimous
disagreement re-centres the peek); Provenance.SCOPED is NOT RESOLVED
(no type checking; interfaces/overloads/chained receivers/java.lang stay
lexical per occurrence, pinned as refusal tests); tree-sitter shapes
were probe-verified (params carry name not declarator; single-segment
package = bare identifier, no field name; TSNode has no getText — byte
offsets; getChildByFieldName returns NULL NODES).

Settled this round (deep review): prompt is unconditional and
harness-agnostic (no capability probe — drydock frames, the agent's
tooling decides); the deep round amends findings only, never a second
tour; the amendments walk is the margin's "proposed" filter (new
findings arrive as proposals = the delta); the button shows only in
tour mode with every step settled; refusal names the one cause ("no
session to ask").

Settled this round (tour diagrams): one diagram per step, ≤ 4 stages,
tabs/blank/over-100-char-line rejected naming step+stage; stages
CONTINUE the drawing, revealed by a Button; reveal resets per show
(reading state, not progress); old tours byte-compatible (no diagram
member); 6-arg TourStep constructor keeps old construction sites
compiling.

## Environment notes
- Full-suite known failures: 5 `git worktree add` timeouts + SearchRail
  flake (machine/environmental — rerun the class in isolation).
- WorkspaceMcpSessionContextTest.adoptingARemoteOnlyBranchMintsATracking
  LocalBranch is one of the environmental ones, not a regression.
- Build via `./gradlew :app:test --tests "<class>"`, logs to
  `build/logs/` (randomized names).
- TestFX: FxTest base (no @ExtendWith), override `start(Stage)`, use
  `TestStages.show`, `interact()`, `FxSync.waitForFxEvents()`; the diff
  column only enters the scene after `view.showScopes(...)` — a test
  querying nodes in `diffStack` must show a scope first (hit this in
  ReviewAskAnswerTest).
