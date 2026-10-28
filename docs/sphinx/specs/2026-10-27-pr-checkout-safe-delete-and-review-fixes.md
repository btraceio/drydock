# Spec: pr-checkout-safe-delete-and-review-fixes

Run: 20261027-sphinx-review-srsNZBLtVjN3FkW-001. Design: `.sphinx/address/design.md` (clusters A–F).

## Problem

Eleven review findings on the current change set, grouped into six clusters:

- **A. `gh pr view` selector** (F-6485ca73ba6d, F-b4991baaa443, F-ce12d15228a2). The plugin's
  `viewPrBlocking` has a stray `;;`, which breaks its "kept identical to the app copy" promise.
  `prSelector` maps every `pr-<digits>` branch to a PR number, and its javadoc claims the alias
  is "drydock's own", which nothing verifies. No test pins the argv gh receives, so a call site
  that goes back to passing `branch` would still pass the suite.
- **B. PR checkout pre-gh delete** (F-2655d0534d61, F-919f5a639a3a). `checkoutBlocking` probes
  with `show-ref` and then runs `git branch -D pr-<n>`. That force delete can destroy a
  user's unmerged commits. The code justifies it with a false premise ("pr-<n> is drydock's
  scratch space, never the user's branch"), and the probe costs an extra subprocess. The app
  and plugin copies have the same block.
- **C. PR session bookkeeping** (F-28e6f34cda0d). In `MainWorkspace.startSessionOnCheckedOutPr`,
  one try covers both session creation and `updatePrState`. If `updatePrState` throws, the
  code reports "no session" and abandons a session that is actually running.
- **D. FX `ModelSelector`** (F-7a72679001ca). The per-kind cache stores raw catalogs, and
  dedup happens only on the read paths. The plugin twin dedups when it stores.
- **E. Plugin `ModelField`** (F-31ff53929b6b, F-6cca5cf4baed). `allItems` is a hand-synced copy
  of `suggestionsByKind.get(currentKind)`, and a comment contradicts the code next to it. No
  test directly asserts that a repeated catalog id appears once.
- **F. `NewWorktreeModalTerminalTest`** (F-d3843731dc18, F-44c7533e9e1b). The static
  `sharedClone` is lazily built without synchronization, and its temp directory is never
  deleted.

## Correct behaviour

### A — gh pr view selector
- A-1: for a `pr-<digits>` branch, gh receives the argv `pr view <n> --json number,state,url`.
  For any other branch it receives `pr view <branch> --json number,state,url`.
- A-2: a blank branch, or one starting with `-`, is refused before any subprocess runs. The
  refusal stays inside the static worker, so it applies on every path.
- A-3: `viewPr`/`viewPrBlocking` keep their signatures and results, including empty when gh is
  missing.
- A-4: in the viewPr region, the app and plugin `GhCliService` are textually identical except
  for the plugin's "Kept identical…" sentence. The two `GhCliViewPrSelectorTest.java` files
  stay byte-identical.
- A-5 / AB-1 (doc truthfulness): the `prSelector` and `viewPr` javadocs in both copies describe
  `pr-<digits>` as drydock's checkout naming convention. It is assumed by name alone and is
  NOT a reserved or enforced namespace. No sentence asserts as fact that a `pr-<digits>` branch
  was created by drydock or is "drydock's own".

### B — PR checkout pre-gh delete
- B-1 (scoped to the pre-gh step): drydock's own pre-gh step uses only `git branch -d`, so it
  never destroys unmerged commits. The guarantee does NOT cover step 3: `gh pr checkout --force`
  may reset an existing `pr-<n>` to the PR head, and the docs must say so.
- B-1a: no production `PrCheckoutService.java` (app or plugin) contains `scratch` (any case) or
  `not the user's`. `reserved` appears only in the form "not a reserved". No doc says an
  unmerged `pr-<n>` is "kept", "preserved" or "safe" across the whole flow.
  `pullRequestNumberOf`'s javadoc does not say "one of ours" as a fact about provenance.
- B-2: any non-zero exit from the delete means "not deleted, hand off to gh". It never throws
  and never aborts. The `"not found"` stderr match decides only whether a WARNING is logged.
- B-3: the delete runs inside the existing try, so a `run()` exception still reaches
  `removeQuietly`.
- B-4: exactly one git subprocess (`branch -d`) runs between `worktree add` and gh.
- B-5: the gh invocation `pr checkout <n> --branch pr-<n> --force` (cwd = worktree,
  CHECKOUT_TIMEOUT) does not change.
- B-6: the app and plugin production copies stay in lockstep, each keeping its own dash style.
  The two test files keep the same test methods and the same assertions.

### C — bookkeeping does not abort success
- C-1: only an `openWorktreeSession` failure leads to the failure UI and the early return.
- C-2: an `updatePrState` failure is logged at WARNING, and the success path continues
  (OpenReview step, scope resolution, review hand-off).
- C-3: `session` stays definitely assigned and effectively final.
- C-4: `SessionManager` and `WorktreeLifecycleController` do not change.

### D — FX ModelSelector store-time dedup
- D-1: dedup happens exactly once, at store time. Every reader of `suggestionsByKind` sees
  unique entries in first-occurrence order.
- D-2: the result is always stored. It is swapped into the popup only when it belongs to the
  current kind, and the guard comes after the `put`.
- D-3: what the user sees is unchanged: a repeated id shows once on load and once after
  switching away and back, with a single load per harness.

### E — Plugin ModelField single source of truth
- E-1: `suggestionsByKind` is the only source of the popup's items.
- E-2: `currentKind` is assigned before `setText`, so the swap is synchronous.
- E-3: a late load for a harness that is no longer selected only fills that harness's cache.
- E-4: dedup happens at store time, and the cached lists are immutable.
- E-5: with `currentKind == null`, filtering yields an empty list and does not throw.
- E-6: everything runs on the EDT. No new locking.

### F — Test fixture lifecycle
- F-1 (locking): every read and write of `sharedClone` goes through a `static synchronized`
  method.
- F-2 (ownership): if the build fails, the temp directory is deleted before rethrowing and the
  field stays null. A successful build is deleted in `@AfterAll`.
- F-3: the delete targets `sharedClone` itself, never its parent.
- F-4: deletion is best effort. No exception from `deleteTree`, checked or unchecked
  (including `UncheckedIOException` from the `Files.walk` stream), escapes it. A delete
  failure never replaces the original build failure.

## Constraints
- No build-file changes. JUnit 5 in `app`, IntelliJ `BasePlatformTestCase` (JUnit 3 style) in
  the plugin.
- Keep each file's existing comment dash style (`—` in app PrCheckoutService, `--` in plugin).
- Public API does not change. `viewPrWith` is package-private static, which follows the repo's
  existing pattern of package-private test seams.
- Shell-based tests carry `@EnabledOnOs({OS.MAC, OS.LINUX})`, like their neighbours.

## Scope

### Primary fixes
- intellij-plugin/src/main/java/app/drydock/git/GhCliService.java:208 — stray empty statement
  (`;;`) and twin drift — delete the second `;`.
- app/src/main/java/app/drydock/git/GhCliService.java:160 — a namespace assumption that is not
  documented — rewrite the `prSelector` javadoc in naming-convention form and add the
  "Namespace assumption" paragraph. No behaviour change.
- app/src/main/java/app/drydock/git/GhCliService.java:178 — the call-site argv has no test —
  split `viewPrBlocking` into `locate()` plus a static `viewPrWith(gh, root, branch)` and add a
  scripted-gh argv test.
- app/src/main/java/app/drydock/git/PrCheckoutService.java:203 — a false-premise comment and
  javadoc ("scratch space") — reword the inline comment and the class javadoc.
- app/src/main/java/app/drydock/git/PrCheckoutService.java:218 — a force delete of a branch
  that may hold user work, plus a redundant probe — replace both with one `git branch -d`.
- app/src/main/java/app/drydock/ui/MainWorkspace.java:1472 — a try that is too broad and turns
  a bookkeeping failure into "no session" — narrow it and add a separate try/log for
  `updatePrState`.
- app/src/main/java/app/drydock/ui/ModelSelector.java:234 — dedup only on read paths — dedup
  at store time and simplify the `setAgent` replay.
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/ModelField.java:136 — a
  duplicated mutable state (`allItems`) and a false comment — remove the field and read
  `suggestionsByKind`.
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/ModelField.java:249 — store-time
  dedup has no direct test — add a platform test (production line unchanged).
- app/src/test/java/app/drydock/ui/NewWorktreeModalTerminalTest.java:87 — an unsynchronized
  static lazy init — make the accessor `static synchronized`.
- app/src/test/java/app/drydock/ui/NewWorktreeModalTerminalTest.java:94 — a temp directory
  that is never deleted — clean up on build failure and in `@AfterAll`.

### Auto-expanded sibling fixes
- intellij-plugin/src/main/java/app/drydock/git/GhCliService.java:185-190 — namespace
  overclaim in the `prSelector` javadoc ("drydock's own") — same text as the app copy, and A-4
  requires parity.
  FLOW: javadoc read by maintainers → same misleading claim as F-b4991baaa443. PRECONDITION:
  the plugin copy keeps the old text while the app copy is fixed. REACHABLE: yes (doc drift is
  immediate). CONCLUSION: LOW; apply the identical javadoc.
- intellij-plugin/src/main/java/app/drydock/git/GhCliService.java:196 — the `viewPrBlocking`
  argv has no test (twin of F-ce12d15228a2).
  FLOW: `viewPr` → `viewPrBlocking` → `runIn(gh pr view …)`. PRECONDITION: the plugin call
  site reverts to `branch`. REACHABLE: yes. CONCLUSION: LOW; apply the same `viewPrWith` split
  and the mirrored test.
- intellij-plugin/src/main/java/app/drydock/git/PrCheckoutService.java:198-221 (and class
  javadoc :35-71) — the same `-D` force delete and scratch-space premise as F-2655d0534d61 and
  F-919f5a639a3a.
  FLOW: IDE review start → `checkout` → `checkoutBlocking` → `branch -D pr-<n>`.
  PRECONDITION: the user has a local unmerged `pr-<n>`. REACHABLE: yes. CONCLUSION: MEDIUM
  (data loss); apply the identical edit.
- app/src/main/java/app/drydock/git/PrCheckoutService.java:134-141 — the `pullRequestNumberOf`
  javadoc asserts provenance ("if it is one of ours … rather than … merely happens to be named
  pr-something").
  FLOW: javadoc relied on by `prSelector`, review scopes and the PR listing. PRECONDITION: a
  hand-made `pr-<digits>` exists. REACHABLE: yes (the doc is false for it). CONCLUSION: LOW;
  reword it in convention form and scope the "merely named" contrast to non-digit names.
- intellij-plugin/src/main/java/app/drydock/git/PrCheckoutService.java:130-137 — the same
  `pullRequestNumberOf` javadoc. Same reasoning; identical edit.
- app/src/main/java/app/drydock/git/GhCliService.java:141-143 and
  intellij-plugin/src/main/java/app/drydock/git/GhCliService.java:171-173 — the `viewPr` javadoc
  "the alias is drydock's own". Same defect class as F-b4991baaa443 (AB-1). REACHABLE: yes
  (doc). CONCLUSION: LOW; reword it identically in both copies.

## Assumptions
- Resolved at ≥90% confidence: the plugin `GhCliService` gets the same production refactor and
  javadoc as the app copy (design Cluster A parity decision).
- Resolved at ≥90% confidence: the design-adversary round-3 must_fix items are folded into
  this plan, even though design.md predates them. (1) `deleteTree` catches
  `IOException | UncheckedIOException`. (2) Option (a): the `prSelector`, `viewPr` and
  `pullRequestNumberOf` sentences that assert drydock ownership are reworded into
  naming-convention form, not just appended to.
- Resolved at ≥90% confidence: no unit test for Cluster C. `startSessionOnCheckedOutPr` is a
  private method of the large FX `MainWorkspace`, and no existing test reaches it. The fix is
  a mechanical try-narrowing, verified by compile and review. The gap is recorded.
- Resolved at ≥90% confidence: Cluster D gets an FX regression test that adds a CODEX provider
  with a duplicated catalog to `ModelSelectorFxTest`. D-3 says the visible behaviour is
  unchanged, so the test pins dedup and cannot fail on the pre-fix code.
- needs_runtime_evidence (C): downstream steps handle a session that was concurrently removed;
  this was not verified at runtime.
- `WorktreeService` `branch -D` (app :417, plugin :549) is user-confirmed deletion of a branch
  the worktree created. It is a different defect class and stays out of scope.
- `NewWorktreeModalTest.deleteSharedClone` has the same latent `UncheckedIOException` exposure.
  The design explicitly leaves that file alone, so it is noted but not expanded.
