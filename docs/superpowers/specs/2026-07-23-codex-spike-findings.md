# Codex de-risking spike — findings

**Date:** 2026-07-23
**Gates:** Plan B (Codex provider). Answers the three open questions from
`2026-07-23-multi-agent-cli-support-design.md` (§4 id discovery, §6 activity,
resume-picker rendering).
**Method:** empirical, against the installed `codex` (CLI 0.144.5, rollout
`cli_version` 0.77.0 for older sessions), inspecting `~/.codex/sessions/**` and
`codex exec --json` output.

## Q1 — Session-id discovery & the "ownership marker"

**No way to preset the id.** Neither `codex` (interactive) nor `codex exec`
accepts `--session-id`. Codex always mints its own UUID.

**The id IS the rollout identity, uniformly.** For a session, `thread_id`
(emitted by `exec --json`) == `session_meta.payload.session_id` ==
`session_meta.payload.id` == the UUID in the rollout filename
(`rollout-<ISO-ts>-<uuid>.jsonl`). One id, everywhere. Verified:
`019f9072-ef9d-72f0-9673-3ecfebc795b2`.

**`codex exec --json` emits the id on stdout** as its first line:
`{"type":"thread.started","thread_id":"<uuid>"}`. Useful for any *non*-interactive
flow — but Drydock launches the **interactive** `codex` TUI (the user drives it),
which has **no** stdout JSON, no `--json`, and **no flag to print/write the id**.
So exec's clean id-capture does not help the interactive launch.

**The `-c` config marker does NOT work.** Injecting `-c drydock_marker="…"` was
accepted (no `--strict-config`) but the value appears **0 times** in the rollout —
`session_meta.payload` records only `session_id`/`id`/`timestamp`/`cwd`/
`originator`/`cli_version`/`source`/`thread_source`/`model_provider`/
`base_instructions`. Config overrides are not persisted into session metadata.
**So there is no clean, invisible ownership marker.**

**The only marker that lands is the initial prompt** (recorded as the first
`response_item`), but injecting a sentinel prompt pollutes the user's session
with a visible junk first message — rejected on UX grounds.

**Decision (confirms the spec's degraded path):** `DISCOVERED` id capture for
interactive Codex is **snapshot-and-claim on the rollout dir**, filtered by
`session_meta.payload.cwd` == worktree and `timestamp >= launchedAt`, first
unclaimed match wins. There is **no** ownership marker to make same-cwd
concurrent launches unambiguous, so:
- Resume-by-id is **best-effort**, not guaranteed.
- Same-cwd concurrent launches (or an external `codex` in that cwd during the
  discovery window) may bind imperfectly; on an unknown/uncertain id, resume
  falls back to the **`codex resume` picker** (never `--last`).
This is exactly the fallback the spec already documented; the spike confirms the
hoped-for marker does not exist, so the fallback governs.

**Rollout distinguishers observed:** interactive TUI sessions carry
`originator: codex_cli_rs`, `source: cli`; `exec` sessions carry
`originator: codex_exec`, `source: exec`. Not per-launch unique, but a provider
can prefer `source: cli` rollouts when scanning (ignore exec/non-interactive
ones) — matching `codex resume`'s own `--include-non-interactive` opt-in.

## Q2 — Activity reporting (hooks / notify)

**Codex has a hooks system, but it is trust-gated, and there is no `notify`.**
- The only surface evidence is `--dangerously-bypass-hook-trust`
  ("Run enabled hooks without requiring persisted **hook trust**… DANGEROUS.
  Intended only for automation that already vets hook sources").
- A `notify` program config (the Claude-hook analog) **does not exist**: `notify`
  appears nowhere in `codex --help` / `codex exec --help` and is not a config key.
- So injecting a hook that writes activity state words would require **either** an
  interactive/persisted **trust prompt** **or** the **dangerous bypass flag** —
  neither is non-invasive, and both are unacceptable to append silently to a
  user launch.

**Decision:** **Defer Codex activity badges.** The `CodexAgentProvider` returns
`Optional.empty()` from `activity()` — Codex sessions launch and run, just with
no live status badge, exactly the graceful degradation the API is built for.
Revisit only if Codex later ships a non-invasive notify/hook mechanism. (Full
hook event/payload/trust semantics were not exhaustively mapped, because the
headline — not non-invasive — already decides the gate.)

## Q3 — Resume

`codex resume [SESSION_ID] [PROMPT]` (UUID or session name; UUID wins), `--last`
(most recent, **cwd-filtered by default**), `--all` (disables cwd filtering),
`--include-non-interactive`, else an interactive picker. `codex fork` mirrors it.
- Known id → `codex resume <id>` (reliable path).
- Unknown id → **`codex resume` picker** (cwd-filtered). Not `--last` (ambiguous
  with same-cwd siblings, per Q1).

## Q4 — Resume-picker rendering in the terminal

Not directly testable outside a real terminal surface here, but `codex resume`'s
picker is a standard interactive TUI that renders in any terminal; Drydock's
Ghostty is a real terminal, so it should render. **Low risk — validate visually
during implementation**, not a blocker.

## Net effect on Plan B scope

- **Ships:** locate, capabilities, `buildCreate/ResumeCommand` (local; remote
  unsupported → `supportsRemote()=false`), env-scrub (`CODEX_*`, keep
  `CODEX_HOME`), `DISCOVERED` id strategy (snapshot+claim+cwd+timestamp,
  prefer `source: cli` rollouts), `ConversationSource` (rollout-dir catalog +
  missing-conversation probe by id), resume-by-id with picker fallback.
- **Deferred (with clean no-op):** `activity()` returns empty — no Codex badges.
- **Documented degradation:** resume-by-id is best-effort; same-cwd concurrency
  and external launches fall back to the picker.
- `-C/--cd <dir>` sets cwd; `codex` requires a git repo unless
  `--skip-git-repo-check` (Drydock sessions are always in a repo/worktree, so no
  flag needed).
