---
id: find-learning-science-authoring-rules
title: Tour authoring follows evidence-based teaching rules (Mayer/Sweller/WCAG/plain language); the citations live in the spec's Authoring rules section
kind: finding
tags: [review, tour, authoring, learning-science, typography]
applies_to: [app/src/main/java/app/drydock/review/ReviewInstructions.java, app/src/main/java/app/drydock/ui/review/StepPanel.java]
source: improve-review-tour/find-learning-science-authoring-rules
check: grep -q "everyFormTeachesByTheReadingScienceRules" app/src/test/java/app/drydock/review/ReviewInstructionsTest.java
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


A tour is studying material, and its authoring contract
(`ReviewInstructions.TEACHING`, typed on Run review and refresh; the
`review_tour` descriptor's narrative text) compresses the reading-science
literature to rules an agent can hold while writing a step:

- Lead each narrative with its single most important point, then only
  what bears on the decision (plain-language main-message-first; Mayer's
  coherence).
- Explain the change's reasoning BEFORE the check asks the reviewer to
  apply it (the worked-example effect) — the same contract as the
  grounding rule: a check may ask only what the tour taught or the code
  shows.
- One concept per step, in one file's contiguous rows where possible
  (segmenting; split-attention).
- Plain active-voice sentences, one idea each, terms defined at first
  use (CDC "Simply Put").
- The reader is an expert in the language, new to the change: explain
  the change, not the language (the expertise reversal effect — support
  that helps novices harms experts).

Rendering follows the same evidence: the narrative wraps (80-glyph
ceiling, WCAG 1.4.8), its leading is widened toward the 1.5 line-height
floor (WCAG 1.4.12), and no text container in the step panel fixes its
height. Staged reveals (a PREDICT hiding added rows until answered) are
segmenting in the interface.

The citations (with URLs) live in the tour design spec's "Authoring
rules (evidence-based)" section, docs/superpowers/specs/ — keep the
rules and the citations together when either changes.
