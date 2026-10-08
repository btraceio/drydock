---
id: find-usage-link-noise-source
title: Out-of-diff fan-in is a name-match grep with two de-noising rules; a semantic resolver is the only real fix
kind: finding
tags: [review, tour, impact, fanin, grep]
applies_to: [app/src/main/java/app/drydock/review/OutOfDiffFanIn.java]
source: improve-review-tour/find-usage-link-noise-source
check: grep -q "MAX_ATTRIBUTABLE" app/src/main/java/app/drydock/review/OutOfDiffFanIn.java
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


`OutOfDiffFanIn` is ONE repo-wide word-bounded `git grep -F -w -f` per
scope — a lexical count, not a call count. A changed declaration named
like a common member (a record accessor `text`, `title`) matches every
unrelated file, which swamped the step panel's caller list and inflated
the reading path's first rank term. Two de-noising rules shape the
answer, and both are heuristics with a residue the UI labels
("occurrences, not resolved references"):

- **Self-declaring files are dropped**: a file outside the change whose
  own occurrence lines look like a declaration of the symbol (its own
  field/method/type/local — `DeclarationLine`, line-lexical, no
  per-file parse) is dropped whole for that symbol. In-changed-file
  occurrences are never dropped (the change graph owns those). The probe
  fails toward "use": receivers, `new`, unmodified assignments,
  comments, `return` never count — the opposite error would silently
  drop a genuine caller.
- **> MAX_ATTRIBUTABLE (50) surviving occurrences are counted, not
  listed**: the symbol leaves `bySymbol` and reports through
  `suppressedCounts`; `Result.occurrences()` keeps the signature flag's
  count real.

Ruled out, don't re-try: per-file tree-sitter parses (the noisy case is
exactly hundreds of hit files) and regex/-P grep modes (portability was
why -F was chosen). The real fix is a resolving `UsageProvider`
(Provenance.RESOLVED, LSP) — specced as a later milestone.
