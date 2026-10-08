# Guided review: a tour through whole files, with checks that gate approval

## 1. Why

Review today is hunk-centric. A reviewer reads isolated added/removed hunks
with twelve lines of context and has to build the mental model of the change
around them unaided; per-hunk approval then records that they clicked, not
that they understood. The reading path and sections (2026-08-22 spec) fixed
the *order*. They did not fix the *frame* -- hunks instead of files -- or the
*warrant* behind an approval.

What the reviewer asked for:

1. Guidance through the change in an order that builds understanding, with
   pedagogical checks that validate it, so an approval is a knowing one and
   not a rubber stamp.
2. The change shown in its whole source file, changes highlighted -- not as
   isolated hunks.
3. For each change, how it affects its callers and callees and whether it
   might break the code around it.
4. Effortless movement to any of those places and back: follow usages,
   search for tokens, a breadcrumb trail.

**Success:** at the end of a review the reviewer could explain the change to
a colleague, and the tool caught at least one place where their model was
wrong before they approved.

## 2. Decisions

| Decision | Rejected alternatives, and why |
|---|---|
| **The tour runs in Review's diff column, which shows whole files** (§5): a display-only diff with unlimited context, so the column holds every line of every changed file, removed lines included. Peek, the trail and search move out of the Explorer into shared components that both surfaces use. | *Embed the Explorer's `FileViewer`.* It reads the file from disk, which is not the diff's post-image for a `BASE` scope with working-tree edits. It lexes the displayed text as the file's text, so interleaved removed lines break highlighting. Its second instance would open a second `FileEditSession` and disk poller on the same files. It is package-private (`FileViewer.java:65`). *A separate Tour destination:* a third place to be, the thing `3df00e5` removed. *Copying peek and trail into Review:* two copies that drift. Moving them to shared components is not copying. |
| **The agent is assumed.** | *A computed, check-less tour.* Drydock is agent-based. A tour without checks is the reading path under another name. If the agent fails, Review offers Retry and the plain diff review. |
| **Checks gate approval per step**, with an explicit, recorded override. | *Advisory scores; a final quiz before Submit.* Neither stops a rubber stamp at the moment it happens. |
| **Check kinds: PREDICT, TRACE, RISK** (§3). | *Explain-it-back.* Left out by the reviewer. |
| **The agent proposes, the human decides** (§4). Nothing the agent says is graded against the reviewer, sent to the author or posted until the reviewer has looked at it. | Treating agent findings as facts. The agent can be wrong. |
| **LSP is out of scope**; a `UsageProvider` seam ships with the lexical implementation (§6). | LSP in this spec, or before it. LSP brings server discovery, a long-lived JSON-RPC process per worktree and language, and a client dependency decision. That is its own spec. |

## 3. The tour model

In `app.drydock.review`:

```
ReviewTour(scopeId, diffFingerprint, List<TourStep> steps)
TourStep(id, title, narrative, List<Anchor> anchors,
         List<ImpactNote> impactNotes, List<TourCheck> checks)
Anchor(file, startKey, endKey, note?)     // line keys: n<newLine> / o<oldLine>
ImpactNote(file, line, text)              // provenance CLAIMED
TourCheck(id, kind, prompt, choices, answerKey, explanation,
          List<TourCheck> alternates)     // kind ∈ {PREDICT, TRACE, RISK}
```

- **Anchors are line-key ranges**, the same keys findings already use
  (`UnifiedDiff.Line#lineKey`). A range over post-image line numbers cannot
  address a hunk that only removes lines, or a deleted file; line keys can.
- `narrative` is two to four sentences: why this part exists and what it
  changes, written under the authoring rules below.
- `note` (optional, ≤ 400 characters) is the one claim an anchored range
  supports. The diff column shows it under the range's last row (§5), so the
  explanation sits next to the code it is about. A tour with no notes is
  valid and renders from the narrative alone.
- PREDICT and TRACE carry choices and an answer key that must be one of the
  choices. RISK carries neither. Every check carries at least one alternate.
- **The correct answer's position carries no signal.** Whatever order the
  agent drafts (its drafts put the right answer first, which teaches the
  reader to stop reading the options), the agent boundary scatters it
  deterministically from the check id and the key -- the key moves with its
  choice, the store never re-scatters on load, so progress recorded against
  these positions stays gradable.
- **Which kind to pick** follows from what the reader can see. A PREDICT hides
  the step's added rows until it is answered (§5), so its question must be
  answerable from the removed and surrounding code -- "what will this do?",
  asked over the old version. A question about the added lines themselves ("which
  line makes this safe?") is a TRACE, which hides nothing. The agent is told
  this in its prompt and in `review_tour`'s schema, and the validator enforces
  the floor of it: a PREDICT (or a PREDICT alternate) on a step whose anchors
  cover only added rows is rejected, since nothing is left to read.


**Authoring rules (evidence-based).** A tour is studying material, and the
instruction typed on Run review and refresh carries these rules, each
grounded in the reading-science literature:

- **Lead with the point, then only what bears on the decision.** The most
  important message first, no history and no asides -- the plain-language
  rule that readers scan for what they must know or do (CDC Clear
  Communication; ODPHP Health Literacy Online,
  <https://odphp.health.gov/healthliteracyonline/>), and Mayer's coherence
  principle: interesting-but-irrelevant material costs comprehension
  (Mayer, *Multimedia Learning*).
- **Explain before the check asks.** The narrative or an anchor note
  establishes the change's reasoning first; the PREDICT/TRACE then asks the
  reviewer to apply it to the code. This is the worked-example effect:
  studying a worked instance before solving the transfer problem beats
  unguided problem solving for novices (Sweller & Cooper, 1985;
  <https://doi.org/10.1207/s15516709cog1202_4> on load), and it is why the
  grounding rule (§6) and this rule are two faces of one contract: the
  check may ask only what the tour taught or the code shows.
- **One concept per step, in one file's contiguous rows where possible.**
  Splitting a step that carries two independent ideas is Mayer's segmenting
  principle (learner-paced small segments) and Chandler & Sweller's
  split-attention work: making a reader hold one file's state while
  reading another is extraneous load (<https://doi.org/10.1111/j.2044-8279.1992.tb01017.x>).
- **Plain active-voice sentences, one idea each, terms defined at first
  use.** The health-literacy canon: short sentences, active voice, common
  words, define the term where it first appears (CDC "Simply Put",
  <https://www.cdc.gov/health-literacy/php/develop-materials/plain-language.html>).
- **The reader is an expert in the language, new to the change.** Explain
  the change, not the language: guidance that over-explains what an expert
  already knows is the expertise reversal effect -- support that helps
  novices actively harms experts (Kalyuga, Ayres, Chandler & Sweller,
  <https://doi.org/10.1207/s15326985EP3801_4>). The flip side of the
  plain-language rule: the domain's words are the reader's common words.

**Rendering follows the same evidence.** The step panel wraps the narrative
(no 80+ character lines -- WCAG 1.4.8 sets 80 glyphs as the AAA ceiling,
<https://www.w3.org/WAI/WCAG21/Understanding/visual-presentation>), widens
its leading (WCAG 1.4.12's line-height floor is 1.5,
<https://www.w3.org/WAI/WCAG22/UNDERSTANDING/text-spacing.html>), keeps
sentence case, and never fixes a text container's height -- a fixed height
is what fails 1.4.12 when spacing grows. Staged reveals (a PREDICT hiding
the added rows until answered) are the segmenting principle in the
interface: the reader controls the pace.

**Coverage invariant.** Every hunk of the scope's diff is covered by at least
one anchor. An approval that skipped part of the change is exactly the rubber
stamp this design exists to prevent, so an uncovered hunk is a validation
error, not a warning. This includes deletion-only hunks, deleted and added
files, and renames with edits. A file the diff shows without hunks -- binary,
a pure rename, a mode change -- has nothing to anchor to. It is listed in the
tour outline's footer as "N files without line changes", and the reviewer
acknowledges it there.

**Grading.** PREDICT and TRACE are graded locally and instantly against the
answer key. RISK goes to the agent (`review_check`, §8). The step shows
"checking…" until the verdict arrives, and every completion path -- verdict,
timeout, failure -- clears it.

**A wrong answer** shows the explanation, then the next alternate. When the
alternates run out, the reviewer may ask the agent in a thread, or override
the step with a reason.

**Staleness.** Step progress is keyed to the digests of the hunks a step
covers (`HunkDigest`, content-keyed). When the diff changes:

- Tours are looked up by scope, and the stored tour is migrated to the new
  fingerprint. A step whose hunk digests all still exist keeps its progress.
- A step touching a changed or vanished hunk is marked stale.
- Hunks that are new since the last diff are uncovered.
- The agent is asked to re-issue the stale steps and add steps for the
  uncovered hunks (`review_tour` with `onlySteps`, §8).

Digests include the `REVIEW_CONTEXT_LINES` window. So the full-context
display diff (§5) never replaces the review diff as the source of digests,
verdicts or anchors.

## 4. Agent findings: proposals, withheld bugs, blockers

**Triage.** A `triage` field, separate from `AnnotationStatus`'s delivery
lifecycle:

- Agent findings land `PROPOSED`.
- *Confirm* makes one `CONFIRMED`. Its severity can be adjusted through the
  existing `severityOverride`; no second severity field is added.
- *Dismiss* takes a one-line reason and makes it `DISMISSED`. The reason is
  appended to the finding's thread so the agent sees it.
- *Not sure* opens the thread (`review_answer`) and leaves it `PROPOSED`.
- Human findings are `CONFIRMED` on creation.
- **Existing findings migrate as `CONFIRMED`**, so findings already posted
  or sent keep working.

Only `CONFIRMED` findings can be posted to the PR, sent to the author, or
block a step. Agent findings already default to `postToPr = false`
(`ReviewToolCodec.java:567`).

**Non-blocking bugs become checks.** A `QUESTION`, `DEVIATION` or `NIT`
finding may be filed with `withheldBy: <checkId>`, naming a RISK or PREDICT
check on a step whose anchors contain the finding's lines.

- The finding is hidden in the tour until that check is answered. It is then
  revealed at its lines, whatever the outcome. That reveal is its triage
  moment.
- Dismissing it voids the check. The reviewer's answer is not counted
  wrong, and no alternate is required.
- Overriding a step reveals every finding its checks withheld. An override
  never skips past a known problem.
- `BLOCKING` findings are never withheld.

**Blocking findings stop the tour before step 1.** A banner reads "the agent
proposes N blocking problems", and each one opens in place for triage. If
the reviewer dismisses them all, the tour starts. Otherwise:

- **Send back to the author:** the `CONFIRMED` blockers only, through the
  existing send path (`OPEN` → `SENT`). The tour is shelved. When the author's
  commits land, staleness (§3) re-issues only the affected steps.
- **Review anyway:** the blockers are shown up front, not as checks. A step
  whose anchors contain a `CONFIRMED`, unresolved blocker can only be
  rejected or overridden. This needs a store query by line range; the old
  intent-keyed `hasOpenBlockingFinding` is gone, and `TourFindings` answers it.

**To pass, a step needs every agent finding on its anchored lines triaged.**
Submit shows untriaged proposals as a count, and never posts them.

## 5. Screen and navigation

**Whole files in the diff column.** `DiffService` gains a display-only
variant: the same scope and base with unlimited context, which yields every
line of each changed file in diff form, removed lines included. The diff
column renders it with its existing rows, highlighting and line keys. Runs
of unchanged lines remain foldable through the existing `⋯ N unchanged` row,
but in tour mode they render expanded by default. When a file would push the
column past `MAX_RENDERED_ROWS` (4000, `ReviewDiffColumn.java:68`), the runs
farthest from any changed line start folded, and the file header says so.
Both diffs are fetched off the FX thread. If the display fetch fails, the
column falls back to the review diff and says "whole file unavailable".

The tour needs a diffable scope (`ReviewScope.diffable()`). A PR read
patch-only gets the existing checkout gate before a tour can start.

**Layout:** three columns, as today. `RailLayout` derives its collapse
threshold from the rail widths, so the step panel's default width is chosen to
keep the threshold where it is. The reader can drag the edge between the code
and the step panel to trade code width for reading width (286–900px, and never
below the code column's 560px floor); the arrow keys do the same when the edge
has focus, and a double-click or `Home` restores the default. A chosen width
is one global preference (`reviewStepPanelWidth`), and narrow mode still takes
the panel back down to 286px when the window cannot hold it -- the floor
outranks a chosen width, as it outranks every rail.

- **Left: the tour outline**, replacing the intent rail. One row per step,
  with its state: not started, checking, passed, overridden, stale. The
  footer holds the files without line changes (§3). A second tab holds the
  shared search rail: names and contents, scoped to the change or the
  worktree.
- **Centre: the diff column, showing whole files.** The current step's
  anchored rows are strongly highlighted. Changes belonging to other steps
  get a faint highlight and a gutter note "step N".
- **Right: the step panel**, replacing the findings margin. It holds the
  narrative, then the impact (agent notes pinned on top, then called-from
  and calls lists with their provenance), then the check, then any findings
  on the step's lines that still need triage. Findings also show in the
  column gutter. While a PREDICT is open the order flips: a notice -- the step's
  added lines are hidden until you answer, the code around them is not --
  then the links to the step's ranges, then the check, with the narrative held
  back because it states what the added lines do. The reader is told what is
  hidden and pointed at what is not before being asked anything.
- **Bottom: the key-hints strip, then the shared trail bar.** The strip lists
  only the keys that work in the current state (answering a check, reading,
  step decided) and hides with `h`; hidden, a "Shortcuts h" button remains.
  The choice persists as `reviewKeyHintsHidden` in the UI state.

**Claims in the column.** When a step's anchors carry notes, each range gets a
numbered badge on its first row and a callout under its last row. One claim
is active: its callout is expanded and its rows keep the strong highlight;
the others collapse to one line and their rows recede. Claims are not drawn
while the step's PREDICT is open, or on rows another step's PREDICT hides --
they are the explanation, and showing them first would answer the check.

**Shared components.** `PeekLayer`, `NavigationTrail`, `TrailBar`,
`SearchRail` and `SymbolPeekService` move from `ui.explorer` to a shared
package. Each surface owns its own instances: Review's trail is per session
and persisted like the Explorer's, under its own key in
`ExplorerTrailStore`. A waypoint records a line key as well as a file line,
because a removed row has no line in the post-image.

**Navigation:**

- **Peek.** Clicking an impact entry or an identifier in the column opens a
  peek card over it. This is the existing card, which shows the code at its
  location. It adds no waypoint, and `Esc` closes it. While a peek is open it
  owns `⏎` (open for real), `u` (usages), `a` (ask the agent) and `Esc`.
  Review's key filter, which today catches keys before its children
  (`SessionReviewView.java:847`), yields these to the open peek. `u`
  **replaces** the code with the occurrence list -- one scroll region, never
  the code's own scrollbar nested in a second one -- and every row is a real
  button that jumps to the place claiming the usage: Review peeks at that
  location in place (another card on the stack), the Explorer opens the
  file at that line and collapses the stack onto the trail. `u` again brings
  the code back.
- **Waypoints.** Step changes add one, labelled "Step N", and so do promoted
  peeks and search results. The trail bar's `‹ ›` buttons walk it. `⌘[` and
  `⌘]` do NOT: while Review is showing they keep their session-tab meaning
  (walking a trail whose waypoints are the tour's steps read as the step
  keys having been hijacked -- reported as a clash), and the Explorer keeps
  its own trail-first claim on them only while the Explorer is showing.
- `b` returns the column to the current step's first anchor. A "↩ back to
  step N" pill shows whenever the viewport is off the step's rows.
- **⌘F finds in the diff.** A bar over the column's top-right corner
  searches the rendered diff's own lines -- folded ones included, so a hit
  inside a collapsed run still counts and the walk opens the fold when it
  lands there -- case-insensitively, from two characters up. Enter /
  shift-Enter step and wrap; every hit row is marked and the row the walk
  is on carries the accent bar (signaling, not just scrolling); Esc closes
  the bar and clears the marks. The key is Review's while its board is
  showing and falls through everywhere else (the terminal's own ghostty
  find keeps it inside its sub-tab).
- **Every reveal opens what hid it.** A jump whose target row is missing is
  indistinguishable from a dead button, so `revealLine` falls back before
  giving up: a target inside a collapsed run opens that hunk's folds (a jump
  is a navigation intent; `c` re-folds), a target past the row cap reaches
  its hunk header, and a target the rendered diff does not carry returns
  false so the caller says so over the column instead of silently doing
  nothing.
- `.` and `,` move to the next and previous anchor within a step -- the
  claims, when it makes them. With no check open to answer, `1`–`4` jump to
  that claim; a click on a callout makes it active without scrolling. A
  multi-file step shows its anchors as chips.

**Checks in the panel:**

- **PREDICT** renders the step's added rows under a hatched "hidden until you
  answer" band. Removed and unchanged rows stay visible, so the old code and
  its surroundings can be read. Answering reveals the added rows.
- Choice answers are real `Button`s, keys `1`–`4`. RISK is a text area,
  submitted with `⌘⏎`.

**Approval:**

- `a` passes the step only when its checks are passed and its findings
  triaged. Otherwise it moves focus to the first unmet requirement, so the
  key always does something visible.
- `r` requests changes on the step, `u` undoes, `n` goes to the next
  unsettled step, and `[` / `]` to the previous / next step.
- **`v` toggles tour ↔ hunk diff.** While a tour exists, an approval made in
  hunk-diff mode (`a`, `⇧A`) is recorded as an override on every step
  covering that hunk, and the submit sheet lists it with the others.
  Switching views cannot bypass the gate.

**Keys retired or moved.**

- `p` (path mode) and `i` (collapse intents) go with the intent rail; both are
  now unbound.
- `m` collapses the step panel.
- `h` hides or shows the key-hints strip (tour only).
- `c` (show unchanged lines) toggles the folding of unchanged runs.
- `d` stays density. The search rail's scope toggle, which is `d` in the
  Explorer, is a button and `⇧D` in Review.
- Every new key (`b`, `v`, `.`, `,`, `1`–`4`, `⌘⏎`) goes into
  `ShortcutsOverlay`, which its parity test enforces, and into
  `MainWorkspace.REPLAYABLE_OFF_REVIEW_SUBTREE`.

## 6. Impact

Three kinds of evidence, each labelled with its provenance.

**Measured: local, always present.**

- **Called from, outside the change.** `OutOfDiffFanIn` occurrences of the
  step's declared symbols, grouped by file and labelled "occurrences, not
  resolved references". `parse` currently drops every occurrence in a
  changed file (`OutOfDiffFanIn.java:233-240`). It changes to drop only
  occurrences *on changed lines*. An unedited call site in an edited file is
  precisely where a signature change breaks.
  Two de-noising rules keep the list readable where a common name would
  otherwise swamp it with links to unrelated files. A file **outside the
  change whose own occurrence lines look like a declaration of the symbol**
  (its own field, method, type or local of that name -- a line-lexical
  probe, no per-file parse) is dropped whole for that symbol: its
  occurrences are uses of its own symbol, not callers of the change's.
  And a symbol with more than `MAX_ATTRIBUTABLE` (50) surviving occurrences
  is **counted, not listed**: its rows leave the caller list and show as
  one line -- "N occurrences outside the change — too common to attribute" --
  while the signature-changed flag keeps the real count, so suppressing a
  list never silences the signal.
- **Called from / calls, inside the change.** `ChangeGraph` edges. Each
  names the step that owns the other end ("→ `JmpCtxScope` · step 1").
- **Calls, outside the change.** Identifiers on the step's changed lines
  that are not declared in the change, resolved through `SymbolPeekService`'s
  best-candidate declaration. At most 8 per step, most-referenced first,
  because each is a search.

**Signature-changed signal: measured.** When a step's anchor contains a
declaration line, the out-of-diff callers that sit on unchanged lines are
flagged: "declaration changed · N call sites were not edited". This is the
data TRACE checks are built from.

**Claimed: the agent's impact notes.** These are pinned on top. A note that
describes a defect must be filed as a finding instead (§4). Impact data
and the agent's own usage searches are name matches, so a note must cite a
location the agent **read and confirmed genuinely references the change's
declaration** -- a same-named member in an unrelated file is a false link,
not an impact. A step carries **at most 8 impact notes**; more is rejected
by validation, the same all-or-nothing list as every other tour rule.

**Behaviour.** Measured data is computed off the FX thread, once per scope,
alongside the graph build. "Finding callers…" shows until the grep returns.
`OutOfDiffFanIn.Result` gains a reason, so a timeout (the existing 30 s), a
failure, and no checkout each show as "callers unavailable: <reason>", never
as an empty list.

**A check's question is grounded.** Whatever its kind (PREDICT, TRACE,
RISK), a question must be answerable from what the tour itself explains
(its narratives and anchor notes) or from code the reviewer can see around
it -- never from outside knowledge the tour has not taught (spec details,
encodings, instruction sets, hardware behaviour). A question that tests the
reader's background instead of the change stops the walk: teach it in the
narrative first, or do not ask it. The instruction typed on Run review and
refresh carries this rule alongside the kind-picking rule; validation
enforces only what it can check structurally (a PREDICT must have something
to read), the grounding itself is the agent's contract.

**`UsageProvider` seam.** It answers usages, declaration and callees for a
symbol at a file line. Each result carries provenance `MEASURED`, `CLAIMED`
or `RESOLVED`. The lexical implementation ships here. A later LSP spec plugs
in a resolving one, used only when a server for the language is running and
indexed. The tour never waits on it.

## 7. Comments, verdicts, persistence

**Line comments** can go on any row of the column, including unchanged
lines, which the whole-file view makes reachable. At Submit:

- Comments on lines inside the GitHub diff post inline, as today.
- Comments on lines outside it -- which `SubmitPlan` refuses today
  (`SubmitPlan.java:111`) -- are folded into the review body as `path:line`
  with an excerpt.
- The submit sheet shows which route each comment takes.
- Sending to the author carries all of them.

**The submit sheet is a curate-then-post pass.** Confirmed findings post
by default (the confirm IS the vouch; the card's toggle remains the
explicit opt-out, and only the transition into confirmed promotes, so no
redundant confirm can undo an exclusion). Every posting row can be
reworded for the post (⌘⏎ saves, Esc drops the draft); an edited row is
chipped "edited" because the board still shows the original and the
difference must never be silent. Before the sheet opens, the PR's current
head sha is compared with the checkout's HEAD: equal shas skip the check,
a moved head re-anchors the whole plan against the PR's current diff --
a finding a newer push displaced is refused against "the PR's current
head", never folded into the body -- and an unanswered gh is said out
loud ("checked against the diff as reviewed") rather than implied.

**Verdicts are derived from steps, never written by them.** Step progress is
stored on its own. The one stored verdict per hunk (`ReviewVerdict`, keyed by
scope and hunk digest) is computed from the steps covering that hunk:

- `CHANGES` if any covering step requested changes.
- `APPROVED` only when *every* covering step has passed or been overridden.
- Otherwise unsettled.

So a step covering three lines of a fifty-line hunk does not approve the
fifty, and two steps never race for one verdict slot. `VerdictMerge`'s rule
-- an approval needs every hunk settled -- is unchanged and still holds.

**Overrides** are recorded with their reason and listed on the submit sheet
("2 steps approved without passing checks"). They are posted only if the
reviewer chooses. The verdict bar counts steps in tour mode and hunks in
diff mode.

**Persistence.** Tours and step progress live in a **sibling file** of the
annotation store (`review-tours.json`), not in `AnnotationStore`'s file:

- `AnnotationStore.loadFromDisk` discards the whole store on any decode
  exception (`AnnotationStore.java:520-545`), and the next save overwrites
  it. A tour must never be able to cost a reviewer their findings.
- The file holds one tour per scope, the latest. Superseded tours are
  dropped, so the file does not grow per fingerprint.
- Each tour entry is decoded on its own and leniently. A malformed step is
  dropped and re-requested, and a malformed tour is dropped and rebuilt.
- Writes are debounced on a background thread, with a `flushPendingSaves`
  that tests and `DrydockApplication.stop()` call. It has one writer, the
  tour store.

Triage is a field on the finding, so it stays in the annotation store.

## 8. Agent contract (MCP)

**Starting a tour.** The existing **Run review** -- one human click, typed
through `ReviewInstructions`, in a subagent where the harness has them --
asks for findings and a tour. Review shows "Building tour…" with the MCP
activity log. Failure clears to an error with **Retry** and **Open diff
review**.

**Refreshing and regenerating.** With a tour on screen the top bar offers two
buttons. **Refresh tour** asks only for the stale steps and the uncovered
hunks, so every step already walked keeps its progress; it is disabled while
the tour is current. **Regenerate tour** asks for the whole tour again through
the same instruction as Run review, for when the diff has not moved but the
tour should be rewritten (a tour written before anchor notes existed, a
question worth asking differently). A new tour starts every step's progress
afresh, so when the reviewer has approved or answered anything the first click
only arms the button ("Discard progress?", the tooltip says how many steps) and
a second click within eight seconds sends it; with nothing to lose it sends
at once. The old tour stays on screen until the new one replaces it -- and
for good if the agent's new tour is rejected.

**The tour replaces the intent rail and path mode.** `review_intents`
is retired: the tool, its descriptor and the `intents` array of `review_state`
are gone (tour progress is reported under `review_state.tour`), and a finding
no longer carries an `intentId` (an incoming one is ignored). The hunk diff is
per file, not per intent: `[` / `]` move between files, `n` finds the next
unsettled hunk, and the verdict bar names the file. Sections and the reading
path stay as computed input to the agent.

| Tool | Change |
|---|---|
| `review_scope` | New `impact` include: measured callers and callees per changed symbol, and the signature-changed flags. `sections` and the reading path remain, as the suggested order. |
| `review_tour` *(new)* | Submits the tour, or with `onlySteps` replaces stale steps and adds steps for uncovered hunks. Validation is all-or-nothing and lists concrete errors: a hunk not covered; an anchor key that is not a line of the diff; an impact note off-range; more than 8 impact notes on a step; a `withheldBy` finding with no check on a step anchoring its lines; a withheld `BLOCKING` finding; a check without an alternate; an answer key not among the choices; a PREDICT on a step whose anchored rows are all added rows. |
| `review_finding` | Agent findings land `PROPOSED`. Optional `withheldBy: checkId`. |
| `review_check` *(new)* | The agent's verdict on a RISK answer: `holds`, `partly` or `doesNotHold`, with a reason. |
| `review_state` | No longer reports `intents`. Tour progress; RISK answers awaiting a verdict, *with the answer text*; triage outcomes, including dismissal reasons. |

**RISK grading is pulled, not pushed.**

- Drydock types a one-line prompt carrying only the check id. The agent
  reads the answer through `review_state` and replies with `review_check`.
- The prompt goes to the session that ran the review. Requests are
  deduplicated per answer, following the `RecheckDispatch` claim pattern.
- While that agent is busy, requests queue and are sent one at a time.
- After 3 minutes without a verdict, the check shows "agent unavailable",
  with a retry and the override path.
- A verdict for a fingerprint other than the current one is dropped.

Tour JSON is decoded with the depth-bounded `JsonParser`, with per-field
size caps: narrative ≤ 1,000 characters, anchor note ≤ 400 characters,
≤ 40 steps, ≤ 6 checks per step.

## 9. Testing

**Unit (`app.drydock.review`):**

- One test per validation rule.
- Local grading, alternates, overrides.
- Staleness migration across a re-diff: kept steps, stale steps, new hunks.
- Verdict derivation: overlapping steps; a partial step; any-changes wins.
- The triage state machine, including dismissal voiding a check, and the
  `CONFIRMED` migration.
- `OutOfDiffFanIn.parse` keeping unchanged-line occurrences in changed
  files, and the reason on `Result`.
- The signature-changed signal.
- The lexical `UsageProvider`.
- `SubmitPlan` folding out-of-hunk comments into the body.
- Tour-store lenient decode, and that a corrupt tour file leaves the
  annotation store intact.

**MCP router tests**, in the `McpToolRouterReviewTest` style: `review_tour`
including `onlySteps`, `review_check` including a stale-fingerprint drop,
and the new includes.

**TestFX (`ui/review`):**

- Step-panel states.
- PREDICT hiding, then revealing.
- `a` moving focus to the first unmet requirement.
- `b`.
- Waypoints added by step changes and promoted peeks, but not by plain
  peeks.
- An open peek owning `⏎`, `u` and `a`.
- `v` round-trip keeping findings in place.
- A hunk-diff approval recorded as an override.
- Row-cap folding.
- Shortcut parity and the replayable-keys set.

**Visual:** the diag `shot:` harness at 1280 and 1920 widths, covering the
three-column layout, the PREDICT band and removed-row rendering.

**Live:** one real PR in this repo, reviewed end to end with a real Claude
reviewer. Report the steps, checks, validation retries, tour build time, and
whether a withheld finding round-trips.

**Not covered automatically:** the pedagogical quality of the agent's
checks. The live run gives one judgment on that, and it is tuned through the
review prompt, not tested in code.

## 10. Out of scope

- LSP integration: a follow-up spec on the `UsageProvider` seam.
- Explain-it-back checks.
- A no-agent tour.
- A repository-wide symbol index.
- Skim, minimap and symbol underlines in Review: they stay in the Explorer,
  where the displayed text is the file's text.
