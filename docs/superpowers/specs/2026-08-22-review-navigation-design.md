# Review navigation: entry points, reading order and links

*Adapted from `review-helper v2 — review-driven navigation for code changes`
(2026-08-21), an external design for a Python/web review tool. This spec keeps
that design's navigation model and replaces its machinery with what Drydock
already is: one JVM process, an agent bound to every scope, and a diff-scoped
lens that says what it is.*

## 1. Why

Drydock's Review surface can already show you a change. It cannot tell you
where to start reading it, or why one hunk follows another.

The intent rail is ordered — but by `FallbackIntents`' kind heuristic
(production before refactors before config before tests) and then
alphabetically by directory. That is a better order than the diff's own, and
it is still not the order the change was *made* in. A reviewer opening a
45-file branch lands on intent 1 because it sorted first, not because it is
the thing the rest depends on.

What is missing is the middle of a review: a data-model change read before
the code that uses it, an interface read before its implementation, and a
visible answer to "what does this hunk have to do with the one I just read".

## 2. What this adapts, and what it does not

The source design is a five-stage pipeline: resolve the source, build an
ephemeral SQLite knowledge graph with tree-sitter and a capped ingest, order
hunks against it, have an LLM name the sections, and serve a local web UI.

Most of that pipeline already exists in Drydock under other names, and the
parts that do not exist are the parts worth building.

| review-helper v2 | Drydock |
|---|---|
| `resolve-source.py` (uncommitted / branch / PR) | `SessionReviewScopes`, `ReviewScopeRegistry` — `WORKTREE`, `WORKING_TREE`, `PR` |
| `serve-ui.py`, `ui/hunk-view.js` | `SessionReviewView`, `ReviewDiffColumn` |
| Sections with title + explanation | `ReviewIntent` (title, kind, risk, rationale) |
| Pi agent naming sections | `review_intents` over MCP |
| `annotations.py`, `post-comments.py` | `AnnotationStore`, `SubmitPlan`, `GitHubReviewService` |
| **`order-hunks.py`** | **new: `ChangeGraph` + `ReadingPath`** |
| **Concept map, Leiden communities** | **cut — see §2.2** |
| **SQLite KG, `kg-ingest.py`, `repo-intel.py`** | **cut — see §2.1** |
| tree-sitter + `tree_sitter_languages` | `io.github.bonede:tree-sitter` + grammar jars (§8) |
| networkx | ~200 lines of Kahn and Tarjan (§2.3) |

### 2.1 The knowledge graph is a file for a reason that does not apply here

v2 persists a SQLite graph because it is a multi-process pipeline:
`kg-ingest.py` writes it and `order-hunks.py` reads it. Drydock is one
process. The graph becomes an in-memory object with scope lifetime, rebuilt
when the diff is re-read — structurally what `SymbolIndex` already does for
the symbol lens.

Nothing is persisted, so nothing has to be invalidated, migrated, garbage
collected, or reconciled with a worktree that moved under it. The `rh-gc`
equivalent does not need to exist.

### 2.2 The concept map is cut; same-concept links are not

These were bundled in an earlier draft of this spec and they do not belong
together.

**The map goes.** Drydock already has the overview it would be: the intent
rail is `1..N` cards with a kind tag, a risk heat bar, file badges and
click-to-filter on the diff column. A graph panel would be a second, weaker
answer to a question the rail already answers, and there is no width for it —
`RailLayout` only keeps rails expanded above 1320px, the narrow-width
`BROWSE`/`DETAIL` paging was deleted only because the queue column went away,
and the headless test screen had to be widened to 1920×1200 to stop the
Review scene overflowing the software pixel buffer. A fourth column is the
thing the scoped-session-review change just finished removing. v2 reaches the
same verdict from the other direction, calling v1's map "a visualization
looking for a purpose".

**Same-concept links stay**, and change their source. v2 derives them from
Leiden communities. Drydock derives them from a shared changed symbol: two
hunks are same-concept linked when both mention a name that is declared
somewhere in the change. The link therefore names its own reason — `↔ both
touch ReviewScope` — where a community id cannot, and every other marker on
this surface states its reason.

That also removes the last caller of a clustering algorithm, which is why no
community detection is specified anywhere in this document. If a clustering
tier is ever wanted, label propagation over the same `ChangeGraph` is about
sixty lines and still needs no third-party graph library.

### 2.3 No graph library

What this design asks of a graph is a topological sort, strongly-connected
components, and reachability, over a graph of tens of nodes. That is Kahn and
Tarjan, in one small class, fully unit-testable against hand-built graphs.

`org.jgrapht:jgrapht-core` was considered and rejected on value, not quality:
1.27 MB plus `jheaps` and an arbitrary-precision math transitive, a new entry
in the jlink `--add-modules` list that `RuntimeImageModuleListTest` pins
against jdeps, and a POM dependency for a `jbangJar` that deliberately
bundles nothing. Three textbook algorithms do not buy that.

## 3. Scope of this change

In scope: a change graph over the scope's diff, a reading path computed from
it, a second mode for the intent rail, per-hunk links in the diff column, one
optional field on `review_intents`, and tree-sitter parsing with a lexical
fallback.

Out of scope, and unchanged: scope identity, the findings margin, verdicts,
progress counting, the submit sheet, the annotation store, and every other
`review_*` tool. Nothing here changes what a human settles or how it is
recorded. This adds a reading order to a board that already works.

## 4. The change graph

### 4.1 Nodes and edges

`app.drydock.review.ChangeGraph` — nodes are **changed symbols** (a symbol
whose declaration span overlaps a changed line in the scope's `UnifiedDiff`),
edges are **references** (source uses target). Built off the FX thread, cached
per scope, discarded and rebuilt when the diff changes.

A hunk maps to the symbols whose spans overlap its line range. A hunk may map
to several symbols, or to none — a comment change, a resource file, a
generated blob. Hunks mapping to no symbol are not errors; they sort last, as
in v2.

### 4.2 Two front ends, one matching rule

Per file, whichever applies:

- **A grammar is loaded for the file's language.** Declarations and
  identifier uses come from the parse tree, with their spans.
- **No grammar.** The file is scanned lexically under the existing
  `SymbolWords` rules (keywords excluded, identifiers shorter than three
  characters excluded). Every occurrence is a *use*; the file contributes no
  declarations, because a lexical scan cannot tell one from the other without
  guessing.

**Edge matching is the same rule either way**: a use of a name resolves to a
declaration only when **exactly one** changed declaration in the scope carries
that name, and only across files. Ambiguous names produce no edge; intra-file
edges are dropped as noise. This is the rule v2 settled on in
`_resolve_cross_file_references_v2`, and it is worth being explicit about what
it means for tree-sitter:

> Tree-sitter tells you that a token is a declaration and another is a call.
> It does not tell you which declaration a call resolves to. The unique-name
> match survives it. Tree-sitter therefore raises the **precision of
> classification**, not the **correctness of resolution** — which is exactly
> why a file with no grammar degrades to a usable graph rather than to
> nothing, and why "we resolved this reference" is never claimed anywhere in
> the UI.

### 4.3 The diff is the world, and the one place it is not

The graph covers the diff plus the twelve lines of context Review already
asks git for. It does not index the repository. That is a position this
codebase has taken twice already — *"the lens indexes the diff, not the
repository"* — and reversing it would mean an index with an invalidation
story and a per-language resolver.

It costs one thing, and the cost is the signal a reviewer most wants. v2's
strongest entry-point heuristic is "public API — a changed symbol called by
code **outside** the diff", and v2 computes it by ingesting unchanged caller
files (its caller-expansion pass, capped at five caller files per changed
symbol). Under a diff-scoped graph that signal does not exist.

It is restored without an index, by **one bounded `git grep`**: a single
`ProcessRunner` spawn of `git grep -F -f <names> -- <worktree>`, listing every
uniquely-named changed declaration, excluding the changed files themselves,
counting out-of-diff occurrences per name. One invocation for the whole
scope, not one per symbol. It obeys the house rules for spawns — argument
list, `--end-of-options`, a short timeout, `destroyForcibly` on expiry, and a
failure that is logged and distinct from an empty result.

This is a lexical count of occurrences, not a call count, and the UI says so
in the same voice the symbol popover already uses.

## 5. The reading path

`app.drydock.review.ReadingPath` computes, from a `ChangeGraph`:

### 5.1 Order

Kahn topological sort over changed symbols, foundation first: if changed
symbol A is referenced by changed symbol B, A comes before B.

Among the units Kahn can emit next — those whose dependencies are all placed
— the highest-ranked entry point (§5.2) goes first, then `FallbackIntents`'
existing kind order, then path. Ranking inside the sort rather than after it
is what makes "the first card" and "the entry point" the same card by
construction; ordering first and marking second would let them disagree, and
a `START HERE` badge sitting on card 4 would read as a bug. With no edges at
all this degrades to the entry-point rank and then to today's fallback order,
rather than to alphabetical chaos.

Cycles are found with Tarjan. Each strongly-connected component collapses to
one unit whose members are ordered by path, and **the cycle is named on
screen**. v2 breaks cycles arbitrarily and notes them in its JSON; a cycle
among changed symbols is a fact about the change worth showing a reviewer,
and a silent arbitrary break is the kind of unexplained ordering this whole
feature exists to remove.

### 5.2 Entry points

Ranked by, in order:

1. **Out-of-diff fan-in** (§4.3) — called from outside the change.
2. **In-degree within the changed set** — the foundation the rest builds on.
3. **Not a test** — test paths (`*_test.*`, `*Test.java`, `__tests__/`, and
   the rest of v2's list) rank after production code. This is the one place
   this design takes a side where v2 offers a preference: Drydock's fallback
   grouping already puts tests last, and two orderings disagreeing about it
   would be worse than either.
4. **Not a leaf** — nothing changed depends on it, so it is an endpoint.

The top-ranked unit is marked `START HERE` — by construction the first card
in a computed order (§5.1), and wherever it falls in an agent-supplied one,
because that order is the reviewer's and is not re-sorted (§5.4).

### 5.3 Links

Per hunk, cross-file only, deduplicated by target hunk:

- **`calls`** — a changed symbol this hunk's symbols reference.
- **`called by`** — a changed symbol that references this hunk's symbols.
- **`same concept`** — a hunk sharing a changed symbol with this one (§2.2),
  labelled with the symbol they share. The shared name must be uniquely
  declared in the scope, the same test an edge passes (§4.2); an ambiguous
  name links nothing. Cross-*file* only, like the other two, but **not**
  restricted to crossing an intent boundary: two files inside one intent that
  share a symbol are linked, because the rail groups them without saying what
  they have in common, and that is the thing this link says.

Labels name files and symbols (`③ SessionReviewScopes.java`), never raw node
ids.

### 5.4 The reviewer's order wins

`ReadingPath` orders `FallbackIntents` **only**.

When an agent has supplied intents, `IntentGrouping.set` already renumbers
them `1..N` in the agent's own order. That array *is* the reading order, from
a reviewer that read the change; recomputing over it would be Drydock
overruling the reviewer, which is the one thing this surface is built not to
do. This mirrors `IntentGrouping`'s existing shape exactly — the reviewer's
grouping wins, the computed one is what the surface falls back to — and it
is what keeps Review fully functional with no reviewer configured.

Links and entry-point marks are computed in both cases: they are facts about
the diff, not a grouping, so they do not compete with the agent's judgement.

## 6. Where it surfaces

### 6.1 The rail has two modes

`p` toggles the intent rail between **INTENTS** and **PATH**. A mode, not a
fourth column: the width budget that ruled out the concept map (§2.2) rules
out a new column just as firmly, and `RailLayout` is untouched.

- **INTENTS** — today's rail, ordered per §5.4, with `START HERE` on the
  first card and a named cycle marker where one exists.
- **PATH** — one row per hunk in reading order, across intent boundaries.
  Each row carries its intent number, the reason it sits where it does
  ("referenced by ③", "called from 7 places outside the change", "test"), and
  its link count.

Selecting a row in either mode drives the diff column, as selecting an intent
does today.

### 6.2 Links in the diff column

A hunk with links gains a footer row beneath it:

```
    ↳ called by  ③ SessionReviewScopes.java:forCheckout
    ↔ both touch ReviewScope   ⑤ ReviewScopeRegistry.java
```

Clicking one selects the target hunk. Footer rows are part of the hunk's row
model, so folding, density and the unchanged-run collapse all apply to them
unchanged.

### 6.3 Keys

`p` is free; `f d c [ ] n a r u ⏎ i m ⇧F \` are taken. `[` and `]` step
whatever the rail is currently listing — intents in INTENTS mode, hunks in
PATH mode — so the mode adds one key rather than a parallel set, and existing
muscle memory survives. `n` remains "next unsettled intent" in both modes,
because progress is intent-keyed regardless of what the rail is showing.

`ShortcutsOverlay` gains the `p` row: advertised and bound must match.

## 7. MCP surface

One optional field, no new tool.

`review_intents` gains per-intent **`reads: [intentId]`** — the intents this
one is built on. Drydock renders the assertion and never verifies it, which
is the `ReviewIntent.Collapse` precedent: the agent asserts, drydock shows the
assertion and keeps the evidence one click away.

With `reads` present, the rail's order is the agent's declared dependency
order (topologically sorted, cycles named as in §5.1). With `reads` absent,
the agent's array order stands (§5.4). With no agent at all, `ReadingPath`
supplies the order. Three sources, one rendering path.

`review_scope` is unchanged. Exposing the computed links to the agent as an
optional include was considered and deferred (§13): the agent can already
read the diff, and an include that exists so the agent can correct Drydock's
lexical guesses is a feature that should be asked for before it is built.

## 8. Parsing and packaging

### 8.1 The binding

`io.github.bonede:tree-sitter:0.25.3`, plus one artifact per grammar. Its jar
bundles `aarch64-macos`, `x86_64-macos`, `x86_64-windows`, and both Linux
natives — precisely Drydock's supported set, including the Windows path the
JediTermFX backend serves.

`ch.usi.si.seart:java-tree-sitter:1.12.0` is the alternative binding and was
not chosen: the bonede artifacts carry the grammars as sibling Maven
coordinates, which is what makes §8.2 a packaging decision rather than a
build-a-grammar-toolchain project.

### 8.2 `GrammarRegistry`

Extension to grammar, resolved by lookup at first use. **A grammar absent
from the classpath is the lexical path (§4.2), not an error.** That single
rule is what keeps the shipped language set a packaging decision instead of
an architectural one, lets the `.app` and the `jbangJar` ship different sets,
and means an unsupported language never produces a broken surface — only a
coarser one.

Starter set and jar sizes, from Maven Central: java 324 KB, kotlin 1706 KB,
python 402 KB, javascript 304 KB, typescript 750 KB, go 255 KB, rust 617 KB,
c 436 KB, cpp 1456 KB, plus the 774 KB core — about 7.0 MB.

### 8.3 Deviations, stated rather than discovered

- **The loader writes outside Drydock's profile directory.**
  `NativeUtils.loadLib` extracts the platform-matched library from the jar to
  `~/.tree-sitter/tree-sitter-lib/`, rooted at `user.home`, CRC32-verifies it
  and `System.load`s it. There is no system property to redirect it; the path
  is a compiled-in constant. Removing this would mean forking the binding.
  It is recorded here because a file appearing under a user's home directory
  that Drydock did not obviously create is exactly the sort of thing that
  should be written down before it is found.
- **First load is disk I/O and a native load**, so it runs on a background
  executor and never on the FX thread.
- **JNI, not FFM.** The AGENTS.md native rules govern FFM upcalls and AppKit
  threading; there are no upcalls and no callbacks here, so they do not
  apply. `--enable-native-access=ALL-UNNAMED` is already in
  `applicationDefaultJvmArgs` and covers JDK 26's restricted `System.load`.
- **`RuntimeImageModuleListTest` runs jdeps against the app jar and its
  runtime classpath.** The jlink `--add-modules` list may need to move; the
  test is the thing that will say so.

## 9. Degradation

Every failure has one stated outcome, and none of them is a silently empty
reading path.

| Failure | Outcome |
|---|---|
| No grammar for a file's language | Lexical scan (§4.2). Not logged — it is the normal case. |
| Native library fails to load | Every file lexical. WARNING once per process, not per file. |
| Unsupported OS/arch (`Does not support arch`) | Same as above. |
| `git grep` missing, failing or timed out | Out-of-diff fan-in absent; entry points rank on the remaining three signals. WARNING with an stderr excerpt. |
| Cycle among changed symbols | Named on screen (§5.1). |
| No edges at all (nothing references anything) | Order falls back to the kind heuristic — i.e. exactly today's `FallbackIntents` behaviour. |

## 10. Deletions and additions

**Added**: `ChangeGraph`, `ReadingPath`, `GrammarRegistry`, a graph-algorithm
class (Kahn, Tarjan), the rail's PATH mode, per-hunk link footer rows, the
`p` shortcut and its overlay row, `reads` on `review_intents`, and the
tree-sitter dependencies.

**Changed**: `ReviewIntentRail` (two modes), `ReviewDiffColumn` (footer
rows), `ReviewDiffRows` (the row model gains a link row), `ReviewToolCodec`
and `McpToolRouter` (`reads`), `ShortcutsOverlay`, `app/build.gradle.kts`.

**Deleted**: nothing. This is additive to a surface that works.

**Not built**: no concept map, no community detection, no persisted graph, no
repository index, no graph library, no new MCP tool.

## 11. Verification

Headless tests:

- `ChangeGraph`: unique-name match produces an edge; an ambiguous name does
  not; an intra-file reference does not; a file with no grammar contributes
  uses but no declarations.
- `ReadingPath`: foundation-before-dependent on a hand-built graph; a cycle
  becomes one named unit rather than an arbitrary break; an edgeless graph
  reproduces `FallbackIntents`' existing order exactly (a pinned regression —
  this is what "no reviewer configured still works" means).
- Entry-point ranking: each of the four signals in isolation, and the tie
  order between them.
- Fan-in: `git grep` absent, failing, and timing out are three distinct
  logged outcomes, and none of them empties the path.
- `GrammarRegistry`: a missing grammar takes the lexical path and logs
  nothing; a failing native load logs once and takes the lexical path.
- `review_intents` with `reads`: order follows it; a `reads` cycle is named;
  a `reads` entry naming an unknown intent is rejected with the batch, since
  a batch is all-or-nothing.
- The rail's PATH mode: `[`/`]` step hunks, `n` still steps unsettled
  intents, and the mode round-trips through `p`.

In the running app, with screenshots rather than assertions about them:

- The rail in PATH mode at a realistic window width — rails have truncated
  before, and this mode's rows carry more text than an intent card.
- A hunk with all three link kinds, at each density.
- A named cycle.

## 12. Risks

- **The links are lexical and will sometimes be wrong.** The unique-name rule
  makes a false edge unlikely rather than impossible, and the mitigation is
  honesty rather than accuracy: the UI calls them occurrences, as the symbol
  popover already does, and the agent's `reads` overrides the computed order
  where it matters.
- **7 MB of grammars** in the `.app` and the `.dmg`, growing with every
  language added. §8.2 is what keeps this a decision that can be revisited
  per artifact rather than a commitment.
- **A second native-loading path** beside libghostty, with its own extraction
  directory and failure mode. It is JNI and callback-free, which is what
  keeps it from interacting with the FFM rules, but it is still a second way
  for a launch to fail on someone's machine.
- **`git grep` on a large repository.** One spawn with a short timeout, whose
  failure costs one ranking signal and nothing else.

## 13. Open items

- **Exposing computed links to the agent** through a `review_scope` include,
  so a reviewer can correct a bad lexical edge. Deferred until asked for
  (§7).
- **Hunk-to-symbol mapping is by line-range overlap**, which is coarse for a
  hunk touching two adjacent declarations. Carried over from v2 unresolved.
- **Entry-point ranking is a first cut.** Four signals in a fixed order, with
  no weighting and no evidence yet that the order is right.
- **A clustering tier** for same-concept links, if shared-symbol proves too
  noisy (§2.2).
- **Cross-language edges** — a Java call into a native symbol, a template
  referencing a handler. The unique-name rule spans languages by accident
  rather than by design, and nothing here decides whether that is a feature.
