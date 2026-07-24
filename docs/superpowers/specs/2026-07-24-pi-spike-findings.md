# Pi de-risking spike — findings

**Date:** 2026-07-24
**Gates:** Plan C (Pi provider). Pi is the third agent; the SPI/API seam (Plan A)
and the DISCOVERED id-capture machinery (Plan B) already exist and are generic.
**Method:** empirical, against the installed `pi` (0.71.1, a Node script at
`/usr/local/bin/pi`, package `@mariozechner/pi-coding-agent`), inspecting
`~/.pi/agent/sessions/**` and two non-interactive `pi` runs in a throwaway
`--session-dir`.

## Headline: Pi is DISCOVERED, not PRESET (spec assumption overturned)

The multi-agent design spec assumed Pi could preset the id via `--session <id>`.
**It cannot.** `pi --session <fresh-uuid>` fails with
`No session found matching '<uuid>'` (exit 1) — `--session` is a **lookup** of an
existing session by (partial) UUID, not create-with-this-id. A new session is
created by plain `pi` (no `--session`), which **mints its own UUID**.

So Pi uses the same **DISCOVERED** strategy as Codex, reusing the generic
`SessionIdDiscovery` SPI capability + the `SessionManager` snapshot-before-launch /
detached-discover-after wiring built in Plan B. No new id-strategy plumbing.

## Session storage (cleaner than Codex)

- Default layout: `~/.pi/agent/sessions/--<enc-cwd>--/<ISO-ts>_<uuid>.jsonl`.
- **Per-cwd directory.** cwd encoding: drop the leading `/`, replace `/` → `-`,
  wrap in `--`…`--`. Verified: `/Users/jbachorik` → `--Users-jbachorik--`;
  `/Users/jbachorik/dev/wt/olifer-multi-agent` →
  `--Users-jbachorik-dev-wt-olifer-multi-agent--`.
- Filename: `<ISO-ts>_<uuid>.jsonl` (e.g.
  `2026-07-24T10-09-09-964Z_019f9399-ad4a-739e-9378-2bacbfa4179a.jsonl`).
- First line (the session-meta): `{"type":"session","version":3,"id":"<uuid>",
  "timestamp":"<ISO>","cwd":"<abs cwd>"}`. The `id` equals the filename UUID.
- **Discovery is simpler than Codex:** because sessions bucket into the cwd's own
  directory, discovery scans **one directory** (`--<enc-cwd>--/`) — no date
  buckets, no full-tree walk. Verify the encoded dir against each file's first-line
  `cwd` field defensively.
- **The session file is written on launch**, before/independent of the model call
  (observed: the file existed even though the model request errored), so it is
  discoverable immediately.

## Create / resume / fork

- **Create:** plain `pi` (mints id, writes the session file). No flag sets the id;
  no `--settings`-style flag.
- **Resume by id:** `pi --session '<id>'` (partial-UUID lookup of an existing
  session; confirmed it errors cleanly when the id is unknown).
- **Resume picker / continue:** `pi --resume` (select a session) / `pi --continue`
  (most recent). `--no-session` = ephemeral (not used).
- **Fork:** `pi --fork <path|id>`.
- **Resume routing (Plan C):** id known → `pi --session '<id>'`; id unknown
  (discovery failed/ambiguous) → `pi --resume` (picker). Mirrors Codex's
  best-effort-then-picker contract.

## Env scrub

Only `PI_CODING_AGENT` appears as an "inside pi" marker in the binary
(`strings $(which pi) | grep PI_`). Scrub it on launch:
`env -u PI_CODING_AGENT pi`. (There is no `CODEX_HOME`-style var that must be
*preserved*; pi resolves its home from `~/.pi` / `--session-dir`.)

## Activity reporting

Pi has `--mode json|rpc` and an extension system, but **no obvious non-invasive
activity-badge hook** (the Claude `--settings`-hook / a `notify` program analog).
Consistent with Codex: **defer Pi activity badges** — `activity()` returns
`Optional.empty()`; Pi sessions launch and run without a live status badge.
Revisit if pi's rpc/extension surface offers a clean event stream later.

## Other

- **cwd:** pi uses the launching terminal's cwd (no `-C`/`--cd` flag); Drydock
  launches the terminal in the worktree, so no flag needed. The session's `cwd`
  field reflects it.
- **supportsRemote:** false (out of scope, like Codex).
- **Version:** `pi --version` → `0.71.1` (probe it, don't hardcode).
- Note: the user's pi model/provider config is pi's own concern (this spike saw it
  resolve to an anthropic model and hit a usage limit) — irrelevant to Drydock,
  which only launches `pi` and lets the user's pi config choose the model.

## Net effect on Plan C scope

- **Reuses:** the `SessionIdDiscovery` SPI + `SessionManager` DISCOVERED wiring
  (Plan B) unchanged — Pi is another DISCOVERED provider.
- **New (mirrors Codex, simpler):** `PiExecutableLocator`, `PiSessionStore`
  (scan the single `--<enc-cwd>--/` dir, parse first-line `id`/`cwd`/`timestamp`,
  `idsFor`/`newCandidates`/`existsForId`), `PiConversationSource`, `PiIdDiscovery`,
  `PiVersionProbe`, `PiAgentProvider` (kind=PI, DISCOVERED, `supportsRemote()=false`,
  env-scrub `PI_CODING_AGENT`, create `env -u PI_CODING_AGENT pi`, resume
  `pi --session '<id>'` else `pi --resume`, `activity()` empty), + `META-INF/services`
  registration. `AgentKind.PI` + preference order already exist.
- **Possible cleanup (optional):** `CodexRolloutStore` and `PiSessionStore` share a
  shape (scan dir → parse first-line json → snapshot/new-candidates/exists-by-id);
  a shared base could be extracted, but the layouts differ (Codex date-buckets +
  content-cwd vs. Pi per-cwd-dir + filename-id), so a Pi-specific store is fine for
  the first cut. Note for a future refactor, don't force it now.
