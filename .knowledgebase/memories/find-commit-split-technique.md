---
id: find-commit-split-technique
title: Split one file across single-purpose commits by staging intermediate content, never by partial patches
kind: finding
tags: [git, commits, workflow]
applies_to: []
source: improve-review-tour/find-commit-split-technique
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


This repo requires single-purpose commits, and one feedback round often
changes the same file (McpToolRouter, ReviewInstructions, the tour
spec, ReviewDiffColumn) for two different commits. The technique that
works without patch surgery:

1. Save the final file aside under a randomized name in an ignored dir
   (build/patches/) — never a fixed /tmp path, concurrent sessions share
   it.
2. Reverse-apply the not-yet-committed hunks by exact string replace to
   produce the intermediate content; assert count==1 per replace so a
   mismatch fails loudly instead of silently not applying.
3. `git add` + commit the intermediate; restore the final and commit
   the rest. Verify HEAD's file is byte-identical to the saved final
   (`diff`), and compile the intermediate where practical by checking
   out HEAD copies of the other not-yet-committed files.
4. A companion file forgotten outside a staged list is repaired by
   soft-reset re-staging and re-committing with the same messages — not
   by a dangling fixup commit.

Deliberately unanchored (applies_to: []) — this constrains the workflow,
not a code area; find it by topic (commits, workflow).
