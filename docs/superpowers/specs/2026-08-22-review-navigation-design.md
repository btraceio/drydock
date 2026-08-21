# Review navigation: entry points, reading order and links

*Adapted from `review-helper v2 — review-driven navigation for code changes`
(2026-08-21), an external design for a Python/web review tool. This spec keeps
that design's navigation model and replaces its machinery with what Drydock
already is: one JVM process, an agent bound to every scope, and a diff-scoped
lens that says what it is.*

## 1. Why

Drydock's Review surface can already show you a change. With no reviewer
configured it cannot tell you what the change is *made of*, where to start
reading it, or why one hunk follows another.

Run against a real C++ pull request, the rail reads:

```
main/cpp · 12 files      test/cpp · 4 files      cpp/hotspot · 6 files
```

Those are `FallbackIntents`' (kind, directory) groups titled by the last two
path segments. Every card is individually correct and the rail as a whole
says nothing — which is precisely the failure that class was written to fix
one rung lower down, when it replaced one-intent-per-file whose titles all
clipped to the same prefix. It stopped one rung too early: **the grouping
still has no structural input at all.**

The same change, grouped structurally, reads `JmpCtxScope guard` (2 files),
then `Crash-protected resolve()`, then the tests for each. The difference is
not presentation. `guards.h` and `guards.cpp` are one idea, a directory key
splits them whenever the tree does, and no amount of better sorting or
naming recovers a group that was drawn in the wrong place.

So there are three things missing, and they are strictly ordered — later ones
are worthless without earlier ones:

1. **Grouping** that follows the code's own structure rather than its folders
   (§5). The binding constraint.
2. **Order** over those groups: a data-model change read before the code that
   uses it, an interface before its implementation (§6).
3. **Links** answering "what does this hunk have to do with the one I just
   read" (§6.3).

An earlier draft of this document addressed only (2) and (3), on the
assumption that grouping was already solved because `ReviewIntent` exists.
`ReviewIntent` is the right *container*; what fills it with no agent running
was the problem, and the rail above is what that assumption looks like in
practice.

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
| Sections with title + explanation | `ReviewIntent` — but only on the agent path; the computed path is **new** (§5) |
| Pi agent naming sections | `review_intents` over MCP |
| `annotations.py`, `post-comments.py` | `AnnotationStore`, `SubmitPlan`, `GitHubReviewService` |
| **`order-hunks.py`** | **new: `ChangeGraph` + `Sections` + `ReadingPath`** |
| **Concept map, Leiden communities** | **cut — see §2.2** |
| **SQLite KG, `kg-ingest.py`, `repo-intel.py`** | **cut — see §2.1** |
| tree-sitter + `tree_sitter_languages` | `io.github.bonede:tree-sitter` + grammar jars (§9) |
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

In scope: a change graph over the scope's diff; a **grouping** computed from
it that replaces the fallback's directory clustering; a reading path over
that grouping; a second mode for the intent rail; per-hunk links in the diff
column; `reads` on `review_intents` and a `sections` include on
`review_scope`; content-derived intent ids; and tree-sitter parsing with a
lexical fallback.

Out of scope, and unchanged: scope identity, the findings margin, the verdict
model, the submit sheet, and every other `review_*` tool. Nothing here changes
*what* a human settles — only how the things to be settled are drawn, ordered
and named.

One caveat on that last line, stated because it is the sort of thing that is
easier to find in a spec than in a bug report: redrawing the groups changes
what a verdict is *about*, which is why §9 is not optional and why
`AnnotationStore` appears in §12 despite the paragraph above.

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
`ProcessRunner` spawn of `git grep -n -F -f <names> -- <worktree>`, listing
every uniquely-named changed declaration, excluding the changed files
themselves. One invocation for the whole scope, not one per symbol. It obeys
the house rules for spawns — argument list, `--end-of-options`, a short
timeout, `destroyForcibly` on expiry, and a failure that is logged and
distinct from an empty result.

This is a lexical count of occurrences, not a call count, and the UI says so
in the same voice the symbol popover already uses.

**`-n`, and the locations are kept.** An earlier draft counted the matches and
discarded the output. That is the wrong half to keep: "called from 7 places
outside the change" with nowhere to click is a statistic, not comprehension,
and it lands at precisely the moment a reviewer wants to look. The `file:line`
list costs nothing extra — the spawn has already happened — and it is what
makes the signal an entry point into the code rather than a number beside a
card. Where it surfaces is §7.4.

## 5. Sections: grouping the change

### 5.1 What the fallback does now, and why it fails

`FallbackIntents` keys a group on (kind, directory) and titles it with
`shortDirectory()` — the last two segments of the parent path. On a C++ tree
laid out `src/main/cpp/...`, `src/test/cpp/...` that yields cards reading
`main/cpp · 12 files` and `test/cpp · 4 files`.

Each card is individually correct and the rail as a whole says nothing, which
is the exact failure this class was written to fix at the level below —
it replaced one-intent-per-file for the same reason and stopped one rung too
early. **The grouping has no structural input at all.** It is worst on C and
C++, where a header and its implementation routinely sit in different trees,
so the one pairing a reviewer most wants is the one a directory key is
guaranteed to break.

Ordering these groups better (§6) puts bad groups in a good sequence. Grouping
is the binding constraint, not order.

### 5.2 Components, not directories

Sections are the connected components of the **file-level** reference graph
projected from §4, plus two conventions carried over from the source design
because a C/C++ change is unreadable without them:

- A `.h` groups with the `.cpp` of the same basename.
- A header groups with any changed `.cpp` that references it, at file level,
  **even when the referenced symbol is not itself in a changed hunk** — the
  case of a new macro or counter header pulled into the section that uses it.

Sections are then ordered by dependency direction, topologically over the
file-level graph, foundation first. On the reference change that is what puts
the new RAII guard and its header in section 1, ahead of the section that
uses it — an ordering a directory sort cannot produce at any width.

Two things are deliberately absent. There is **no `co_change` edge kind**:
the source design found it connects every file of a single-commit change into
one giant section, and Drydock mines no history to build it from. And there
is no clustering algorithm — a component is a connected component, not a
community (§2.2).

### 5.3 Tests: Drydock's rule, applied deliberately

Drydock's fallback makes kind part of the group key, so a test never shares a
card with its subject, on a stated ground: *"'the change' and 'the tests for
the change' are the two things a reviewer most wants to look at separately."*

The reference output does both — two test files get their own sections while
two others stay inside the core section. That is not a principle, it is
whatever the components fell out as, and inheriting it would make Drydock's
behaviour depend on the shape of the call graph rather than on a decision.

**The rule is kept, and moved inside the component.** A component splits into
at most one production section and one test section, adjacent, production
first. That preserves the existing stated preference and produces the good
half of the reference output — a test section next to the subject it covers —
uniformly rather than by luck.

### 5.4 Titles and explanations: three rungs

The grouping is Drydock's; the naming is the agent's. That is the source
design's own split — its `order-hunks.py` computes sections and its agent
titles them — and it is what keeps the floor working: with no agent a section
is still correctly grouped and correctly ordered, only plainly named.

**Title:**

1. **The agent's**, via `review_intents` — prose that names the concept
   (`JmpCtxScope guard`, `Crash-protected resolve()`). The target quality.
2. **The hub symbol** — the highest-fan-in changed symbol in the component:
   `JmpCtxScope · 2 files · 3 hunks`. Names the thing rather than the folder,
   and is computable from §4 alone.
3. **The directory tail** — today's behaviour, when no symbol dominates.

**Explanation:** the agent's `rationale`, else the structural facts — files,
hunks, ±churn, hub symbol, and **what links this section to the one before
it**, which is information today's rationale line does not have and the graph
supplies for free.

### 5.5 The agent has to be able to see them

`review_scope` gains a **`sections`** include: Drydock's computed grouping,
with each section's files, hunk ids and structural title.

An earlier draft deferred exactly this, reasoning that the agent can read the
diff itself. The reference change settles it against that: Drydock now has a
grouping worth proposing, and an agent that cannot see it regroups from
scratch and loses the header convention and the dependency order — arriving
back at prose titles over structurally worse sections.

The agent may still regroup, and its grouping still wins (§6.4). The include
is what makes **accept-and-name** the cheap path and regrouping the
deliberate one.

## 6. The reading path

`app.drydock.review.ReadingPath` computes, from a `ChangeGraph`:

### 6.1 Order

Kahn topological sort over changed symbols, foundation first: if changed
symbol A is referenced by changed symbol B, A comes before B.

Among the units Kahn can emit next — those whose dependencies are all placed
— the highest-ranked entry point (§6.2) goes first, then `FallbackIntents`'
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

### 6.2 Entry points

Ranked by, in order:

1. **Out-of-diff fan-in** (§4.3) — called from outside the change. The places
   it names are kept, not just counted, and are one keystroke from the
   Explorer and from the agent (§7.4).
2. **In-degree within the changed set** — the foundation the rest builds on.
3. **Not a test** — test paths (`*_test.*`, `*Test.java`, `__tests__/`, and
   the rest of v2's list) rank after production code. This is the one place
   this design takes a side where v2 offers a preference: Drydock's fallback
   grouping already puts tests last, and two orderings disagreeing about it
   would be worse than either.
4. **Not a leaf** — nothing changed depends on it, so it is an endpoint.

The top-ranked unit is marked `START HERE` — by construction the first card
in a computed order (§6.1), and wherever it falls in an agent-supplied one,
because that order is the reviewer's and is not re-sorted (§6.4).

### 6.3 Links

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
ids, and carry their provenance (§6.5).

### 6.4 The reviewer's order wins

`ReadingPath` orders the **computed** grouping (§5) only.

When an agent has supplied intents, `IntentGrouping.set` already renumbers
them `1..N` in the agent's own order. That array *is* the reading order, from
a reviewer that read the change; recomputing over it would be Drydock
overruling the reviewer, which is the one thing this surface is built not to
do. This mirrors `IntentGrouping`'s existing shape exactly — the reviewer's
grouping wins, the computed one is what the surface falls back to — and it
is what keeps Review fully functional with no reviewer configured.

The same rule now governs grouping, and §5.5 is what keeps it from being a
loss: the agent is *shown* the computed sections, so overriding them is a
decision it makes having seen them, rather than the accident of never having
been offered one.

Links and entry-point marks are computed in both cases: they are facts about
the diff, not a grouping, so they do not compete with the agent's judgement.

### 6.5 Provenance: measured or claimed

Every ordering and every link is one of two things, and the surface says
which.

- **Measured** — computed here from the diff, by the rules in §4.2 and §4.3.
- **Claimed** — asserted by the reviewing agent, through `review_intents`
  and its `reads` (§8).

An earlier draft ended §8 with "three sources, one rendering path", which is
right about consistency and wrong about trust. The two fail in ways a
reviewer has to tell apart: a measured edge fails as a **false unique-name
match** — two unrelated things sharing a name — and is checkable on the spot
by looking. A claimed edge fails as a **plausible fabrication** and is not
checkable by looking at all; it is checkable only against the code the agent
says it read. A reviewer deciding how hard to squint at "③ depends on ①"
needs to know which of those they are holding.

This is not a new principle on this surface, only its consistent
application: `ReviewIntent.Collapse` already renders the agent's assertion
*as* an assertion, with its evidence and its stated method, precisely because
drydock does not verify it. Order and links get the same treatment. One
rendering path, two visibly different warrants.

## 7. Where it surfaces

### 7.1 The rail has two modes

`p` toggles the intent rail between **INTENTS** and **PATH**. A mode, not a
fourth column: the width budget that ruled out the concept map (§2.2) rules
out a new column just as firmly, and `RailLayout` is untouched.

- **INTENTS** — today's rail, ordered per §6.4, with `START HERE` on the
  first card and a named cycle marker where one exists.
- **PATH** — one row per hunk in reading order, across intent boundaries.
  Each row carries its intent number, the reason it sits where it does
  ("referenced by ③", "called from 7 places outside the change", "test"), and
  its link count.

Selecting a row in either mode drives the diff column, as selecting an intent
does today.

### 7.2 Links in the diff column

A hunk with links gains a footer row beneath it:

```
    ↳ called by  ③ SessionReviewScopes.java:forCheckout
    ↔ both touch ReviewScope   ⑤ ReviewScopeRegistry.java
```

Clicking one selects the target hunk. Footer rows are part of the hunk's row
model, so folding, density and the unchanged-run collapse all apply to them
unchanged.

### 7.3 Keys

`p` is free; `f d c [ ] n a r u ⏎ i m ⇧F \` are taken. `[` and `]` step
whatever the rail is currently listing — intents in INTENTS mode, hunks in
PATH mode — so the mode adds one key rather than a parallel set, and existing
muscle memory survives. `n` remains "next unsettled intent" in both modes,
because progress is intent-keyed regardless of what the rail is showing.

`ShortcutsOverlay` gains the `p` row: advertised and bound must match.

### 7.4 Out-of-diff callers, and the one keystroke to the agent

The fan-in count on a card or a path row opens the **existing occurrence
popover** — the one the symbol lens already uses, with its in-diff /
not-touched chips — listing the `file:line` matches §4.3 kept. From there,
the Explorer peek's existing keys apply unchanged: `⏎` opens the file for
real, `u` lists usages, `a` asks the agent about it. The jump goes through
the `openExplorerAt` / `searchInExplorer` bridge `ReviewDiffColumn` already
holds.

No new interaction is invented here, and that is the point. It is the same
popover on a third source.

It is also where this design is honest about its own ceiling. A lexical
occurrence list cannot tell a reviewer whether a signature change breaks the
caller it just found — nothing mechanical and diff-scoped can. What it can do
is put the reviewer one keystroke from the party that *can* answer, with the
question already pointed at the right file. **The mechanical layer's job is
not to be intelligent; it is to make sure the reviewer knows which question
to ask, and to be one key away from asking it.** That division is the whole
reason §4.3's boundary costs comprehension nothing: it bounds what drydock
asserts on its own authority, not what the reviewer can find out.

## 8. MCP surface

One optional field, no new tool.

`review_intents` gains per-intent **`reads: [intentId]`** — the intents this
one is built on. Drydock renders the assertion and never verifies it, which
is the `ReviewIntent.Collapse` precedent: the agent asserts, drydock shows the
assertion and keeps the evidence one click away.

With `reads` present, the rail's order is the agent's declared dependency
order (topologically sorted, cycles named as in §6.1). With `reads` absent,
the agent's array order stands (§6.4). With no agent at all, `ReadingPath`
supplies the order. Three sources, one rendering path — and the first two are
marked **claimed** while the third is marked **measured**, for the reasons in
§6.5.

`review_scope` gains one optional include, **`sections`** (§5.5) — the
computed grouping, so an agent can accept-and-name it rather than regroup
from scratch. An earlier draft deferred this; the reference change reversed
it, for the reasons in §5.5.

The computed *links* are still not exposed. An include existing so the agent
can correct Drydock's lexical guesses remains a feature that should be asked
for before it is built (§15).

## 9. Stability: what survives a re-run

### 9.1 Two layers that move for different reasons

The computed layer is deterministic given a diff — parse, unique-name match,
and a sort with a total tie-break (§6.1) — so it moves only when the code
moves. The agent layer moves when you **ask again**.

That asymmetry is not a quality judgement; an agent's grouping is the better
semantic answer and §6.4 stands. It matters because the human's accumulated
work is keyed to the grouping, and a layer that moves under a reviewer who
changed nothing will take that work with it.

### 9.2 The defect this closes

`ReviewVerdict` is keyed `(scopeId, intentId)` and `IntentGrouping.set`
replaces a scope's whole grouping. Agent intent ids are whatever string the
agent emitted. So the routine case — the author pushes a fix, the reviewer
asks for a re-review — mints new ids, and **every approval the human gave is
orphaned**. A finished review asks to be done again.

The structural version of this bug has already been paid for once.
`AnnotationStore.migrateLegacyVerdicts` exists because the fallback grouping
stopped emitting one intent per file, and its merge is deliberately
asymmetric: any `CHANGES` among a group's files makes the group `CHANGES`,
but `APPROVED` requires **every** file settled, because *"silently approving
code nobody looked at is the one outcome this must never produce."* That
machinery only recognises the `file:` prefix, so it cannot help an agent
regrouping.

This predates the present design. It is closed here rather than deferred
because §8's `reads` makes the **ordering** depend on agent ids too, which
turns a persistence bug into a navigation one.

### 9.3 Structure supplies identity, the agent supplies semantics

**Intent ids are content-derived**: a digest over the intent's ordered hunk
id set, computed at the MCP boundary, not trusted from the agent's string. A
re-run that produces the same grouping produces the same ids, so verdicts
survive by construction rather than by migration.

The agent's own `id` degrades to a **label local to one call** — what `reads`
references — and is resolved to content ids at decode time. It is not a
persisted key, which is the property that made it unsafe.

Two consequences worth stating rather than discovering:

- **A re-run with the same hunks and a better title is an upsert.** Same id,
  new title and rationale, verdict intact — the same contract `review_finding`
  already keeps, where a re-run refreshes the reviewer's statement and the
  human's decisions survive.
- **An intent naming no hunks has nothing to key on.** `ReviewIntent` already
  permits one, and it keeps the agent's string as its id and does not survive
  a re-run. Documented rather than papered over: there is no content to hash,
  and inventing stability for it would mean carrying a verdict onto a group
  whose membership is unknowable.

**Regrouping re-anchors by hunk overlap**, generalising
`migrateLegacyVerdicts` from its one-off `file:` case to any change of
grouping, and keeping its asymmetric merge verbatim — any `CHANGES` carries,
`APPROVED` needs full coverage, a partial group is left for the human to
re-settle and its old verdicts stay on disk.

### 9.4 Determinism is a requirement, not a property

Calling the computed layer stable is a claim the code has to keep:

- The sort's tie-break is total (§6.1), so no two runs can order equal units
  differently.
- **No `HashMap` or `HashSet` iteration order** anywhere in graph
  construction, edge matching or the sort. Insertion-ordered or sorted
  collections only. This is the cheapest way to lose the property and the
  hardest to notice, because a single-JVM test run will usually agree with
  itself.
- The same diff produces a byte-identical reading path, twice in one process
  and across two processes (§13).

## 10. Parsing and packaging

### 10.1 The binding

`io.github.bonede:tree-sitter:0.25.3`, plus one artifact per grammar. Its jar
bundles `aarch64-macos`, `x86_64-macos`, `x86_64-windows`, and both Linux
natives — precisely Drydock's supported set, including the Windows path the
JediTermFX backend serves.

`ch.usi.si.seart:java-tree-sitter:1.12.0` is the alternative binding and was
not chosen: the bonede artifacts carry the grammars as sibling Maven
coordinates, which is what makes §9.2 a packaging decision rather than a
build-a-grammar-toolchain project.

### 10.2 `GrammarRegistry`

Extension to grammar, resolved by lookup at first use. **A grammar absent
from the classpath is the lexical path (§4.2), not an error.** That single
rule is what keeps the shipped language set a packaging decision instead of
an architectural one, lets the `.app` and the `jbangJar` ship different sets,
and means an unsupported language never produces a broken surface — only a
coarser one.

Starter set and jar sizes, from Maven Central: java 324 KB, kotlin 1706 KB,
python 402 KB, javascript 304 KB, typescript 750 KB, go 255 KB, rust 617 KB,
c 436 KB, cpp 1456 KB, plus the 774 KB core — about 7.0 MB.

### 10.3 Deviations, stated rather than discovered

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

## 11. Degradation

Every failure has one stated outcome, and none of them is a silently empty
reading path.

| Failure | Outcome |
|---|---|
| No grammar for a file's language | Lexical scan (§4.2). Not logged — it is the normal case. |
| Native library fails to load | Every file lexical. WARNING once per process, not per file. |
| Unsupported OS/arch (`Does not support arch`) | Same as above. |
| `git grep` missing, failing or timed out | Out-of-diff fan-in absent; entry points rank on the remaining three signals. WARNING with an stderr excerpt. |
| Cycle among changed symbols | Named on screen (§6.1). |
| No edges at all (nothing references anything) | Grouping and order both fall back to today's `FallbackIntents` (kind, directory) behaviour, unchanged. |
| Edges, but no agent | Sections are grouped and ordered structurally, titled by hub symbol, explained from structural facts (§5.4). The floor this design is really about. |
| A component with no dominant symbol | Titled by directory tail, as today. |

## 12. Deletions and additions

**Added**: `ChangeGraph`, `Sections` (§5), `ReadingPath`, `GrammarRegistry`, a graph-algorithm
class (Kahn, Tarjan), the rail's PATH mode, per-hunk link footer rows, the
`p` shortcut and its overlay row, `reads` on `review_intents`, provenance
marking on order and links (§6.5), the out-of-diff caller popover source
(§7.4), content-derived intent ids and overlap re-anchoring (§9), the
`sections` include on `review_scope` (§5.5), and the tree-sitter
dependencies.

**Changed**: `FallbackIntents` (graph-backed grouping, with today's
directory clustering kept as its own fallback), `ReviewIntentRail` (two
modes), `ReviewDiffColumn` (footer
rows), `ReviewDiffRows` (the row model gains a link row), `ReviewToolCodec`
and `McpToolRouter` (`reads`, and content-derived ids at decode),
`AnnotationStore` (re-anchor by hunk overlap, generalising
`migrateLegacyVerdicts`), `ShortcutsOverlay`, `app/build.gradle.kts`.

**Deleted**: nothing. This is additive to a surface that works.

**Not built**: no concept map, no community detection, no persisted graph, no
repository index, no graph library, no new MCP tool.

## 13. Verification

Headless tests:

- `ChangeGraph`: unique-name match produces an edge; an ambiguous name does
  not; an intra-file reference does not; a file with no grammar contributes
  uses but no declarations.
- `Sections`: a `.h` groups with its same-basename `.cpp`; a header with no
  changed symbol groups with the changed `.cpp` that references it; two
  components stay two sections; a component splits into adjacent
  production/test sections, production first; and an edgeless diff reproduces
  today's (kind, directory) clustering exactly.
- Section titles: agent title wins; hub symbol when there is one; directory
  tail when no symbol dominates.
- `ReadingPath`: foundation-before-dependent on a hand-built graph; a cycle
  becomes one named unit rather than an arbitrary break; an edgeless graph
  reproduces `FallbackIntents`' existing order exactly (a pinned regression —
  this is what "no reviewer configured still works" means).
- Entry-point ranking: each of the four signals in isolation, and the tie
  order between them.
- Fan-in: `git grep` absent, failing, and timing out are three distinct
  logged outcomes, and none of them empties the path. A successful run keeps
  its `file:line` matches, and a changed file's own occurrences are excluded
  from them.
- Provenance: a measured order and a `reads`-claimed order render with
  different warrants, and a scope carrying both an agent grouping and a
  computed link set marks each correctly.
- Stability: the same diff yields a byte-identical reading path twice in one
  process **and** across two processes (the cross-process run is what catches
  a hash-ordered collection).
- Intent identity: a re-run with the same hunks keeps its verdicts and
  refreshes its titles; a re-run that regroups re-anchors by overlap, with
  `CHANGES` carrying and a partially-settled group left unapproved; an intent
  naming no hunks keeps the agent's id and is documented as not surviving.
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

## 14. Risks

- **The links are lexical and will sometimes be wrong.** The unique-name rule
  makes a false edge unlikely rather than impossible, and the mitigation is
  honesty rather than accuracy: the UI calls them occurrences, as the symbol
  popover already does, and the agent's `reads` overrides the computed order
  where it matters.
- **7 MB of grammars** in the `.app` and the `.dmg`, growing with every
  language added. §10.2 is what keeps this a decision that can be revisited
  per artifact rather than a commitment.
- **A second native-loading path** beside libghostty, with its own extraction
  directory and failure mode. It is JNI and callback-free, which is what
  keeps it from interacting with the FFM rules, but it is still a second way
  for a launch to fail on someone's machine.
- **`git grep` on a large repository.** One spawn with a short timeout, whose
  failure costs one ranking signal and nothing else.
- **Over-grouping.** The source design flags this as an open item and its
  own reference output shows it: one section carrying 17 hunks across 9
  files, explained as "the core of the PR" and then enumerating five
  unrelated sub-changes. A connected component is as large as the call graph
  makes it, and nothing here splits it. No rule is invented for this (§15) —
  it is recorded so that a 9-file section is recognised as the known failure
  rather than as a bug in the grouping.
- **Content-derived ids change the meaning of a stored key.** Verdicts
  written under an agent's string id before this lands are re-anchored by
  overlap on first read, which is the same shape as the migration that
  already exists — but it is a one-way rewrite of the human's records, and it
  is the place in this design where a mistake is least recoverable.

## 15. Open items

- **Splitting an over-large component** (§14). Articulation points and a
  size cap are the obvious candidates and both can split a section through
  the middle of one idea, which is worse than a large honest section.
  Deliberately unresolved.
- **Exposing computed *links* to the agent** through a `review_scope`
  include, so a reviewer can correct a bad lexical edge. Still deferred; the
  `sections` include (§5.5) is not this.
- **Hunk-to-symbol mapping is by line-range overlap**, which is coarse for a
  hunk touching two adjacent declarations. Carried over from v2 unresolved.
- **Entry-point ranking is a first cut.** Four signals in a fixed order, with
  no weighting and no evidence yet that the order is right.
- **A clustering tier** for same-concept links, if shared-symbol proves too
  noisy (§2.2).
- **Cross-language edges** — a Java call into a native symbol, a template
  referencing a handler. The unique-name rule spans languages by accident
  rather than by design, and nothing here decides whether that is a feature.
