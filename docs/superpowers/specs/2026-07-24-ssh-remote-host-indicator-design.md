# SSH remote-host indicator in the repository sidebar

## Problem

Drydock can register repositories on a remote host over SSH (see
`2026-07-21-ssh-remote-repos-design.md`). In the sidebar, a remote repo's row
looks identical to a local one — remote-ness is only surfaced through tooltips
(the branch label's "ahead/behind as of last fetch" note) and background
polling. A user scanning the sidebar cannot tell at a glance which repos are
remote or which host they live on.

## Goal

Each SSH-attached repo row shows a small, always-visible chip that marks it as
remote and names the host. Local repos are visually unchanged.

## Scope

In scope:

- A remote-host chip on the repo header row in the sidebar.
- Matching CSS styling using the shared theme tokens (light + dark).

Out of scope (no changes):

- The `Repository` / `SshRemote` model, persistence, and codecs.
- Remote polling, status fetch, and the existing branch-label tooltip.
- Session rows, worktree rows, and stale-bucket rows.

## Current state

- Repo rows are built by `SidebarTreeCell.buildRepoRow(Repository)` in
  `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (starts line 1139).
- The row's text column is a `VBox text = new VBox(1, name, branchRow)`
  (line 1176), where `name` is a `.repo-name` `Label` and `branchRow` is an
  `HBox` of the branch label + counts.
- Remote-ness: `repository.isRemote()` (`Repository.java`, returns
  `remote != null`). Host: `repository.remote().host()` — an `~/.ssh/config`
  alias or `user@hostname` (`SshRemote.java`, `record SshRemote(String host,
  String remotePath)`).
- Existing chip/badge styles in `app/src/main/resources/app/drydock/ui/app.css`:
  `.branch-tag` / `.branch-tag-worktree` (10.5px JetBrains Mono, 4px background
  radius, `0 5` padding) and `.attention-badge` (`-drydock-accent-soft`
  background, `-drydock-accent` text, bold, 100px radius). Glyphs in the
  sidebar are plain Unicode characters in `Label`s. Theme tokens
  (`-drydock-accent`, `-drydock-accent-soft`, `-drydock-text-dim`, …) are
  defined in `theme-dark.css` / `theme-light.css`.

## Design

### Row layout change

In `buildRepoRow`, wrap the repo name and the (conditional) host chip in a small
`HBox` and use that where the bare `name` label is used today:

```java
HBox nameRow = new HBox(6, name);
nameRow.setAlignment(Pos.CENTER_LEFT);
if (repository.isRemote()) {
    nameRow.getChildren().add(buildRemoteChip(repository));
}
VBox text = new VBox(1, nameRow, branchRow);
```

For a local repo the `HBox` contains only `name`, so the row is visually
identical to today.

### The chip

A helper `buildRemoteChip(Repository)` returns a `Label`:

- Text: `"⇅ " + host`, where `host = repository.remote().host()`.
- Style class: `repo-remote-chip` (new, added to `app.css`).
- Width cap so a long host truncates instead of widening the row:
  `chip.setMaxWidth(<cap>)` plus
  `chip.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS)` — leading ellipsis keeps
  the meaningful tail (e.g. the hostname over the `user@` prefix), consistent
  with the branch label at line 1153. `chip.setMinWidth(Region.USE_PREF_SIZE)`
  is not set — the cap is a maximum, and the chip shrinks to content when short.
- Tooltip: `new Tooltip("Remote host: " + host)`, always set, so the full host
  is available even when truncated.

### CSS

Add `.repo-remote-chip` to `app.css`, modeled on `.branch-tag` /
`.attention-badge`:

- `-fx-background-color: -drydock-accent-soft;`
- `-fx-text-fill: -drydock-accent;`
- `-fx-background-radius: 4px;`
- `-fx-padding: 0 5 0 5;`
- `-fx-font-family: "JetBrains Mono";`
- `-fx-font-size: 10.5px;`

Using shared `-drydock-*` tokens means light and dark themes both work with no
theme-specific rules.

## Testing

- Follow the existing `RepositorySidebar` UI-test pattern (confirmed during
  planning). At minimum:
  - A remote `Repository` produces a repo row containing a `.repo-remote-chip`
    node whose text includes the host.
  - A local `Repository` produces a repo row with no `.repo-remote-chip` node.
- Visual verification by running the app (register/inspect a remote repo row)
  in addition to the automated test.

## Risks / notes

- Purely additive to row visuals; no behavioral change to polling, status, or
  the existing branch tooltip.
- Width cap value chosen during implementation to fit the sidebar's typical
  width; truncation + tooltip guarantees no row-width blowout regardless.
