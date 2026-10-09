# LSP tier-3 integration — wrap-up (2026-10-08, implementation done)

## What this is
Tier-3 usage resolution, specced
(docs/superpowers/specs/2026-10-08-lsp-tier3-usage-resolution.md) and
IMPLEMENTED via the dev-process pipeline. All gates green; the 5
remaining :app:check failures are the known environmental git-worktree
set, PROVEN pre-existing by a stashed-baseline rerun (see
kb/find-suite-triage). NOT yet committed.

## The pieces (app.drydock.lsp + wiring)
LspServerProcess 220 (the sanctioned long-lived child; AGENTS.md
carve-out landed) · LspClient 572 (byte-exact framing, terminal-once
correlation, mandatory server-request answers) · JdtServerManager 999
(readiness machine + 10-min cap, lifecycle, sync-on-query,
deleteCache) · LspUsageProvider 388 (upgrade-only composition, the
only RESOLVED producer, 0→1-based) · UsagesAnswer on the seam ·
UserConfig.languageServer + SettingsModal row · peek RESOLVED chips +
"N resolved · M scoped · K name matches" + one-per-session hint ·
MainWorkspace LanguageServerRegistry + Host override · WorktreeService
removal listener → cache cleanup · DrydockApplication stop isolated
close. No LSP4J; ProcessRunner and OutOfDiffFanIn untouched (verified
by gate).

## Spec amendments made during implementation (all user-visible in the
spec file)
§6: 10-minute cap for a never-completing import task, no late
acceptance. §7 Positions: the identifier column comes from the RAW
file line (the carried line text is strip()ed — wrong-token hazard),
with a whole-word guard on every definition acceptance; "server
answered" for the headline = a RESOLVED row exists.

## Next step
User says commit → split into single-purpose signed commits (client
trio / seam+provider / config+UI / peek surfaces / ownership+AGENTS),
refresh the tour-spec §6.3 cross-check, then done the investigation.
