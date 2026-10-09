---
id: find-peek-usages-mode-defects
title: A peek card body has exactly one scroll region: usages replace the code, and usage rows jump through setOnOpenOccurrence
kind: finding
tags: [peek, usages, explorer, review, ui]
applies_to: [app/src/main/java/app/drydock/ui/nav/PeekLayer.java]
source: improve-review-tour/find-peek-usages-mode-defects
check: grep -q "setOnOpenOccurrence" app/src/main/java/app/drydock/ui/nav/PeekLayer.java
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


Invariants of the peek card's usages mode (`u`), fixed from a real defect
(two nested vertical scrollbars, inert rows):

- ONE scroll region per card body. The code excerpt owns a
  VirtualizedScrollPane; the usages list must REPLACE the code in the
  body, never stack under it inside a second ScrollPane — nested vertical
  scrollbars fight over the wheel.
- Every usage row is a real Button (focusable, Enter/Space) — a row that
  claims a usage must go there, and primary actions are Buttons in this
  workspace.
- The card does not decide what "going there" means: rows route through
  `PeekLayer.setOnOpenOccurrence`. Review peeks at the occurrence in
  place (another card on the stack, `openLocationPeek`); the Explorer
  opens the file tab at that line and collapses the stack onto the
  trail, like a promote.
- The list stays honest: "lexical occurrences" label, the "… N more" cap
  line, and the no-declaration disclaimer.
