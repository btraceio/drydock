# LSP tier-3 integration — wrap-up (2026-10-08)

## What is being investigated
Tier-3 usage resolution: an LSP integration (jdt.ls first) behind the
existing UsageProvider/Provenance seam, spec-first. Parent
investigation `improve-review-tour` is paused, all its work committed
(through 7505c96d); tiers 1-2 (lexical + tree-sitter scoped, Java and
Kotlin) are shipped and answering peeks today.

## Settled so far (see ~/.cairn/.../lsp-tier3-integration/)
- **find-seam-ready (confirmed)**: `Provenance.RESOLVED` already ships
  with label + css (unused, by design); `UsageProvider` documents the
  tier-3 contract; ONE construction site exists
  (`TourController.resolveCallees`). Tier 3 slots in with zero changes
  to enum, css, or consumers.
- **ev-process-rules-today**: ProcessRunner is run-to-completion only
  (timeout + destroyForcibly — an LSP server dies by construction);
  the MCP bridge is HTTP; PTYs would corrupt JSON-RPC framing. No
  precedent fits; the client's process host needs its own discipline —
  a deliberate, documented exception to the house rule (or a
  ProcessRunner extension). USER-FACING decision, still open.
- **ev-jdtls-launch-readiness**: launch = equinox launcher jar +
  `-configuration config_mac -data <absolute per-workspace dir>`;
  readiness is a STATE MACHINE (`language/status` ServiceReady ≠
  import done; progress tasks must END); settings ride nested
  `initializationOptions.settings.java.*`; bundles not needed;
  one-server-per-worktree is the safe v1 shape.
- **ev-lsp-framing**: Content-Length = UTF-8 BYTES; null/empty
  references result is a valid answer, not a failure; positions 0-based
  (drydock Usage lines are 1-based — one adapter).
- **ev-local-machine**: NO jdt.ls on this machine — the unconfigured
  path IS the first-run experience; JDK found via PATH not java_home
  (probe `java -version`, accept explicit javaHome).

## Refuted / ruled out (from parent, don't re-investigate)
- LSP4J, auto-download of servers, the tour ever blocking on server
  readiness, tier 2 replacing tier 3.

## Open questions (all seeded as nodes)
- q-lsp-transport (the ProcessRunner decision — ask the user),
  q-lsp-server-discovery (config shape), q-lsp-readiness (state
  machine + what the peek shows meanwhile), q-lsp-provenance-
  composition (merge policy, fan-in in v1?), q-lsp-lifecycle
  (start/stop/idle/restart, memory budget).

## Spec written (2026-10-08, user said "go" on the transport decision)
docs/superpowers/specs/2026-10-08-lsp-tier3-usage-resolution.md — all
five questions RESOLVED (Resolution sections in each q-* node):
- transport: DOCUMENTED EXCEPTION (user-approved) — LspServerProcess
  hosts the single sanctioned long-lived child; AGENTS.md carve-out
  lands with the implementation commit.
- discovery: UserConfig languageServer.{jdtHome, javaHome?} +
  SettingsModal row; launcher GLOB resolved at validation; refusal
  quiet, one-per-session footer hint.
- readiness: STARTING→READY→INDEXING→INDEXED; ServiceReady ≠
  imported; sync-on-query full-text didOpen/didChange for post-import
  disk edits; no upgrade mid-peek.
- composition: UPGRADE-ONLY at the seam (RESOLVED over SCOPED over
  LEXICAL, never a downgrade, null answer erases nothing); re-centre on
  unique differing definition; fan-in stays tier 2 in v1.
- lifecycle: per-worktree, lazy start on first eligible query, idle
  stop 10 min, one restart/30 s backoff then tier-off, -Xmx1G,
  per-worktree -data kept between runs.
Parent tour spec §6.3 RESOLVED bullet now points at the new spec.
Testing plan §9: framing goldens, fake-server round-trip, ONE
real-process test (`cat`), readiness state machine, composition —
no test needs a real jdt.ls.

## Next step
Implement per spec §4 class order: LspServerProcess → LspClient →
JdtServerManager → LspUsageProvider → UserConfig/SettingsModal →
AGENTS.md carve-out, tests per §9. Cairn lock held; evidence nodes
carry the jdt.ls facts to verify against a live server when one gets
configured.
