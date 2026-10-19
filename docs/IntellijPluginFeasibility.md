# Drydock → IntelliJ plugin: feasibility investigation

> **Superseded for design purposes.** The MVP is now built against the design
> handoff in [`docs/intellij-mvp-handoff/`](intellij-mvp-handoff/README.md);
> what is and is not implemented against it is tracked in
> [`IMPLEMENTATION-STATUS.md`](intellij-mvp-handoff/IMPLEMENTATION-STATUS.md).
> This document stays as the record of why a plugin was judged feasible at all.

**Date:** 2026-10-14 · **Status:** investigation complete, no decision taken
**Method:** concept inventory from this repo's code and docs (evidence paths cited
inline), IntelliJ Platform capability check via SDK docs and JetBrains platform
announcements (sources at the end). Proven/inferred is flagged per section.

## Verdict

Feasible, and cheaper than the original build — but it is a **re-platforming, not a
port**. The differentiating logic of Drydock (review scopes, findings/verdicts, the
guided tour, the MCP tool surface, agent session lifecycle) lives in UI-free packages
that compile against nothing but the JDK and can move almost as-is. The part that
would *not* port — the custom multi-repo window shell and the native Ghostty terminal —
is exactly the part IntelliJ already replaces with something better supported.
Rough sizing (inferred, not measured): an MVP with the review loop end-to-end is
~6–10 weeks of one engineer; full concept parity ~1 quarter.

## 1. What Drydock actually is (inventory, proven from the code)

342 Java files: ~50.5k LOC in UI/terminal/app/launcher/handoff packages, ~33.8k LOC in
everything else. The distinct concepts:

| # | Concept | Where |
|---|---------|-------|
| 1 | Multi-repo workspace shell: sidebar of repos → session tabs, custom undecorated window, theming | `app.drydock.ui` |
| 2 | Embedded real terminal per session (Ghostty native on macOS, JediTermFX+ConPTY on Windows) running the real `claude` CLI | `app.drydock.terminal.*`, `docs/native-integration.md` |
| 3 | Agent provider SPI: create/resume/adopt conversations, launch plans, session-id strategies, activity watcher (busy/idle/attention states) | `app.drydock.agent.*`, `app.drydock.activity` |
| 4 | Review system: HMAC scope handles (`rs_…`), queue (MINE/AGENTS/REQUESTED/STACK), findings with threads/severity/proposed patches anchored to file+line keys, verdicts keyed by hunk digest | `app.drydock.review`, `docs/ui-redesign.md` |
| 5 | Guided review tour: steps, predict/trace/risk checks, grading gates, hunk verdicts derived from step progress, tour migration across a changed diff | `app.drydock.review.tour` |
| 6 | In-app MCP server + tool router (`review_scope`, `review_finding`, `review_tour`, `review_check`, `review_answer`, `review_state`, worktree/repo/session tools) with prompt-safety and budget guards | `app.drydock.mcp` (`McpToolRouter` 1,680 LOC, `McpServer` on `com.sun.net.httpserver`) |
| 7 | Worktree lifecycle: create sessions in worktrees, PR checkout (detached worktree + `gh pr checkout`), merge-and-finish flows | `app.drydock.git`, `app.drydock.ui` |
| 8 | Git/GitHub read layer: status, diffs with fixed context lines, `gh` review requests, clone | `app.drydock.git`, `app.drydock.github` |
| 9 | Symbol intelligence for review: tree-sitter grammars, Java/Kotlin tree walkers, out-of-diff fan-in, lexical usage tiers; a separate JDT LSP tier | `app.drydock.review` walkers, `app.drydock.lsp` |
| 10 | Persistence discipline: single-writer `ApplicationState`, per-store writers (`AnnotationStore`, `TourStore`), lenient cosmetic decoding | `app.drydock.state` |
| 11 | ProcessRunner discipline (all spawns: arg-list, timeouts, forced destroy) | `app.drydock.process` |
| 12 | Handoff files between sessions; remote SSH repos; MCP console/activity log | `app.drydock.handoff`, `app.drydock.ssh` |

**Key structural fact (proven by grep):** packages `review`, `mcp`, `agent`, `git`,
`state`, `activity`, `github`, `domain`, `search`, `lsp`, `process`, `ssh`, `config`
contain **zero `javafx` imports**. The only `com.sun`/internal usages outside the
terminal host are `com.sun.net.httpserver` in `McpServer` — a JDK module, fully usable
from a plugin. The engine is already headless; JavaFX is a shell around it.

## 2. What IntelliJ gives you instead (sources at the end)

- **Terminal**: Reworked Terminal is default since 2025.2; a public plugin API landed
  in 2025.3 (`TerminalView`, `TerminalShellIntegration`, block/command listeners,
  `TerminalWidget.sendCommandToExecute/getText/isCommandRunning` for cross-engine
  use). *Experimental, but exactly the surface Drydock needs: spawn `claude` in a tab,
  watch it, send text.* The official Claude Code JetBrains plugin already runs
  `claude` in the IDE terminal and adds selection/diagnostics sharing and native diff
  viewing — so concepts 2+3 have a working precedent and a base to build on.
- **Git/VCS**: `GitRepository`, changes browser, diff/merge editors — Drydock's own
  `git` package re-implements what git4idea provides natively. Native **git worktree
  UI exists in IntelliJ 2026.1** (single-root projects; a Marketplace plugin covers
  older versions).
- **Editors/diff**: line markers, gutter icons, range highlighters, inlays, editor
  popups — the natural carriers for findings/verdicts/tour anchors (replaces
  `ReviewFindingsMargin`, `ReviewDiffRows`, `TourMarks` wholesale).
- **PSI**: real syntax trees and find-usages for Java/Kotlin/etc. Strictly supersedes
  the tree-sitter walkers + lexical usage tiers + the JDT LSP tier (which, per the
  jdtls saga in the sphinx portability investigation, never worked well for Java on
  cold repos). IntelliJ's own built-in MCP server exposes IDE-accurate
  definition/callers to agents with zero startup cost.
- **Platform plumbing**: lazy tool windows, project/application `@Service`
  singletons, `PersistentStateComponent` (maps the single-writer state discipline),
  `PasswordSafe` for secrets, background task + threading model (maps the
  "blocking work is async with progress indication" rule — IntelliJ *enforces* EDT
  discipline), `ProcessHandler`/exec APIs (maps ProcessRunner), Plugin Verifier and
  Marketplace distribution.

**Not available (verified):** no public extension point to inject custom comments into
JetBrains' bundled GitHub PR review UI. A Drydock-style review layer must be its own
tool window + editor overlay — which is what Drydock already is, so nothing is lost.

## 3. Concept-by-concept mapping

| Drydock concept | IntelliJ port | Feasibility |
|---|---|---|
| 1 Multi-repo shell | Partially **against the grain**: one IDE project per window. Port as a "Drydock" tool window listing registered repos/worktrees/sessions (platform allows acting on arbitrary paths), or accept project-centric framing (project = repo). | Medium (paradigm friction, not API risk) |
| 2 Ghostty terminal | **Drop it** — use the built-in Reworked Terminal (public API 2025.3) or the official Claude Code plugin's terminal. Removes the entire native-host/FX risk surface that dominates `docs/architecture.md`. | High (simplification) |
| 3 Agent SPI + activity watcher | Ports nearly as-is: SPI is plain Java; the CLI hooks/transcript-watching logic has no UI dependency. Terminal tabs become `TerminalWidget` handles. | High |
| 4 Review scopes, findings, verdicts | Model code ports as-is (pure). UI re-expressed: findings → gutter/line markers + review tool window; verdicts → highlighters over the changes browser; scope handles unchanged (HMAC logic is self-contained). | High (model) / Medium (UI rework) |
| 5 Guided tour | Anchors map to editor ranges; checks become panels/dialogs in the diff view; `TourValidator`/`TourCodec`/grading ports as-is. This is Drydock's most differentiating feature and IntelliJ has no equivalent — nothing to fight. | Medium-high |
| 6 MCP server + tool router | `McpToolRouter`, `ReviewToolCodec`, `PromptSafety`, `McpSessionRegistry` are headless and reuse directly; `McpServer` (JDK HttpServer) runs inside the plugin. Sessions connect via `.mcp.json` written by the existing `McpConfigWriter`. Complement with IntelliJ's built-in IDE MCP server for definition/callers. | High |
| 7 Worktrees / PR checkout | `WorktreeService`/`PrCheckoutService` port as-is (ProcessRunner → platform exec is mechanical). UI rides the 2026.1 native worktree support. | High |
| 8 Git/GitHub layer | Either port as-is or shed in favor of git4idea + GitHub APIs; keeping the ported layer first minimizes risk, converging later. | High |
| 9 Symbol intelligence | **Replaced by PSI** — delete tree-sitter walkers, lexical tiers, LSP tier. Usage/fan-in becomes platform find-usages (accurate, warm). | High (net deletion) |
| 10 Persistence | `@State` services; keep per-store writers as plain classes if preferred. Lenient decoding policy preserved by implementing it in the `State` classes. | High |
| 11 ProcessRunner | Replace with platform exec APIs (same contract: arg-list, timeouts, forced destroy). | High |
| 12 Handoff / SSH remote / MCP console | Handoff: plain files + a small panel. SSH remote: IntelliJ Remote Development changes the model entirely — treat as out of scope for v1. MCP console: tool-window panel. | Medium / Low (SSH) / High |

## 4. Risks and open questions

- **Terminal API is experimental** (2025.3 public surface, under active development).
  Pin to `TerminalWidget`'s engine-agnostic methods; expect churn. Mitigated by the
  fact that Drydock's terminal needs are shallow (spawn, text-in/out, state).
- **Multi-repo in one window** is the biggest conceptual mismatch. Two viable shapes:
  (a) a Drydock tool window that manages repos outside the current project — keeps
  Drydock's mental model, fights the IDE less than it sounds because the IDE never
  needs to open those repos; (b) project-per-repo + per-project sessions tool window —
  more native, loses the "every repo in one pane" goal. *Needs a user decision.*
- **Cross-IDE compatibility**: review overlay UI should stick to platform-level editor
  APIs (no Java-specific PSI in the core review path) so the plugin also runs in
  PyCharm/GoLand/WebStorm. Drydock's tree walkers were already language-pluggable; PSI
  keeps that via `LanguageExtension`.
- **Two review UIs**: users may have JetBrains' GitHub PR plugin review flow and ours
  side by side. We cannot integrate into theirs (no EP); positioning must be explicit
  (Drydock review = agent-interactive findings/tour layer, theirs = GitHub-native
  reading).
- **Language**: IntelliJ ecosystem is Kotlin-first but Java plugins are fully
  supported; the reused core is Java, so a Java plugin minimizes translation.
  (Inferred; no evidence of friction either way.)
- **Reuse licensing/provenance**: the moved packages keep their history only if
  extracted carefully (same-repo module split is the cheap route: make `app`'s core a
  library module the plugin depends on, then decide extraction later).

## 5. Suggested shape (for discussion, not decided)

A single plugin `drydock-intellij`, Java, depending on Platform + bundled
`Git4Idea` + `org.jetbrains.plugins.terminal`:

- **v0 (1 wk)**: plugin skeleton, settings, tool window scaffold, ProcessRunner→exec
  adapter; core packages moved into a shared module, tests green on the plugin
  toolchain.
- **v1 — sessions (2–3 wks)**: agent SPI active, `claude` tabs per repo/worktree in
  the terminal, activity watcher → status dots, resume picker via
  `ConversationSource`, worktree create/checkout actions.
- **v2 — review loop (3–5 wks)**: scope registry + queue tool window, findings as
  editor overlays + threads panel, verdict bar as diff-viewer action, MCP server +
  full `review_*` tool surface (largely reuse). This is the point where the port is
  *useful*: an agent in the IDE terminal reads and answers human review comments
  anchored in the real editor.
- **v3 — tour (2–3 wks)**: tour UI over diff viewers, checks, grading, MCP
  `review_tour`/`review_check`.
- **later**: GitHub clone/PR queue polish, handoff, MCP console; SSH remote left out.

What is intentionally **not** ported: Ghostty/AppKit host (superseded), the custom
window chrome/theming (superseded), SearchRail/Peek/navigation (superseded by IDE),
the JavaFX density/font-scale machinery (superseded).

## 6. Evidence sources

Code facts: paths in §1; FX-free grep over `app/src/main/java/app/drydock/{review,mcp,agent,git,state,activity,github,domain,search,lsp,process,ssh,config}` (run 2026-10-14).
Web: IntelliJ Platform SDK docs — Embedded Terminal (Reworked Terminal API,
plugins.jetbrains.com/docs/intellij/embedded-terminal.html), Reworked Terminal API
announcement (platform.jetbrains.com/t/3159), terminal engine changes 2025.2
(platform.jetbrains.com/t/2264), Tool Windows / Services / Persisting State /
IntelliJ Platform Gradle Plugin 2.x SDK docs, Explore-the-API (no public GitHub-PR
review EP), Claude Code JetBrains docs (docs.anthropic.com/en/docs/claude-code/jetbrains), JetBrains worktree docs (jetbrains.com/help/idea/use-git-worktrees.html, 2026.1).
Internal: cairn investigation `ddoghq-sandbox--jb-claude-marketplace/sphinx-review-mcp-portability`
(nodes `find-mcp-vs-legacy-skill-parity-gaps`, `find-pr-review-worktree-corpus-root`) —
the sphinx review MCP surface has already been exercised in a JetBrains-marketplace
plugin context, and its Java/LSP findings are the direct motivation for the PSI
replacement in §3.
