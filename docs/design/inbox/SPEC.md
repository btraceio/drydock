# Drydock tool window — "Attention Inbox" redesign

Implementation spec for the Drydock IntelliJ plugin tool window.
Reference mockup: `reference/inbox-mockup.dc.html` (logic + markup of the interactive prototype; read the `<script>` block for exact behavior).

---

## 1. Why

The current tool window is a project → (Sessions | Issues | Pull Requests) tree under status tabs (All / Active / Inactive / Needs you / Flagged). Problems:

- Every registered repo renders even when it has nothing going on → most rows are empty `Issues` / `Pull Requests` folders.
- Hiding Issues/PRs made them hard to reach (switch to *All*, scroll).
- "Focus project" is buried in the right-click menu.
- Rows repeat low-value metadata (`here · Pi`, `2d old`) on every line.

**Goal:** default view answers "what needs me / what's running" across all repos, with project scoping one click away and Issues/PRs reachable without polluting the main list.

## 2. Information architecture

```
Tool window
├─ Header: title · [Add open project]* · [New task] · [⋮]
├─ Banner (conditional): open project not registered / just added
├─ INBOX VIEW (default)
│  ├─ Search field with optional `project:<name>` chip
│  ├─ Groups by state (flat, not by repo):
│  │   Needs you → Running → Blocked → In review → Ready to start → Done today (collapsed)
│  │   each row: status icon · title · [PR #] · [project tag]
│  └─ Footer: [Browse projects] "issues & PRs" ……… "N quiet repos hidden"
└─ PROJECTS VIEW (secondary)
   ├─ [← Inbox] Projects
   ├─ [+ Add open project <name>]* (dashed row)
   └─ per repo, expandable: name · "N issues · M PRs" · [K in inbox]
        └─ Issues list · Pull requests list (mine / review)
```
`*` = only while the IDE's open project is not registered.

The old status tabs are **replaced** by state groups (Needs you / Flagged become groups or filters — see §6 open questions).

## 3. Inbox view

### 3.1 Groups
| Group | Source state | Header color | Default |
|---|---|---|---|
| Needs you | session waiting for user input | orange `#A64B0A` | expanded, **never truncated** |
| Running | active session | neutral | expanded, first 4 + "+N more" |
| Blocked | task blocked | red `#B4282E` | expanded |
| In review | task in review | neutral | expanded, 4 + more |
| Ready to start | task ready | neutral | expanded, 4 + more |
| Done today | completed within today | muted | **collapsed** |

- Group header = chevron + `Label · count`; click toggles collapse. Empty groups are not rendered.
- Truncation limit: 4 rows; "+N more" / "Show less" toggle per group.
- Collapse/expand state persists per group (project-level `PersistentStateComponent`).

### 3.2 Row
`[icon] Title ………… [#PR] [project-tag]` — single line, 30px, title ellipsized.

- Icon: sessions → 8px dot (orange = needs you, blue = running). Tasks → play (ready, green), clock (review, gray), slashed circle (blocked, red), check (done, green).
- Needs-you rows get a tinted background `#FFF4EB` (dark-theme equivalent required).
- PR link: `#<n>` only, opens PR in browser.
- **Project tag**: small bordered label with repo name. Click → apply `project:` filter. Hidden while a project filter is active (redundant).
- Removed vs. today: `here · Pi`, `ready · 1d old`, `in review · 2d old` inline text. Move age/agent/location to tooltip.
- Double-click / Enter: same as today's default action on the item. Right-click: existing context menu (keep "Focus project", now = apply project filter).

### 3.3 Search + project filter
- One `SearchTextField`. Plain text filters titles (case-insensitive substring).
- Typing `project:` opens a suggestion popup of registered projects (fuzzy/substring), with "N active" / "quiet" meta. ↑/↓ moves, Enter picks, Esc clears text.
- Picking a project renders a removable chip `project: <name> ×` inside the field; placeholder becomes "Search in project".
- Backspace in an empty field removes the chip.
- Clicking any row's project tag, or "N in inbox" in Projects view, sets the same filter.
- Empty result → centered "Nothing matches." + [Clear filters].

### 3.4 Footer
- [Browse projects] → Projects view.
- Right side: "`N` quiet repos hidden" (repos with zero active items) → also opens Projects view.

## 4. Projects view
- Back button "← Inbox" + title.
- One row per registered repo: chevron, **name**, "`I` issues · `P` PRs" meta, and (if it has active items) a pill "`K` in inbox" → returns to Inbox with `project:` filter.
- Expanding a repo shows *Issues · count* (first few + link to open all in browser) and *Pull requests · count* (each marked `mine` / `review`). "No open PRs" when empty.
- Issues/PRs load lazily on first expand (they are the expensive, noisy data — this is the point of moving them here).
- A newly added project is sorted first and auto-expanded.

## 5. "Add open project" quick action
Replaces/re-homes the existing "register current project" action.

Trigger condition: the IDE's current `Project` is not registered in Drydock.

Three entry points (all hidden once registered):
1. **Banner** under header: folder+ icon · "Open project **<name>** isn't in Drydock yet" · [Add project] (primary) · × ("Not now").
   Dismissal is remembered per IDE project.
2. **Header icon button** (folder+, accent color), tooltip "Add open project to Drydock". Stays after banner dismissal.
3. **Dashed row** at top of Projects view: "Add open project **<name>** — from IDE".

On add:
- Register via the existing registration code path.
- Banner turns green: ✓ "**<name>** added · syncing issues & PRs" · [Open in Projects] · ×.
- Project appears first in Projects view, expanded; available in `project:` suggestions.

Prefer an `EditorNotification`-style banner look (`EditorNotificationPanel` styling or `InlineBanner` from `com.intellij.ui.components.InlineBanner` if the platform version supports it).

## 6. Open questions for the implementer to confirm with the user
- **Flagged**: in the mockup it's not a group. Options: keep as a `is:flagged` search token, or a pinned "Flagged" group above Running. Ask.
- **Inactive** tab from today's UI has no direct equivalent; proposed mapping = "Done today" + older items reachable via search `is:done`. Ask.
- Should Running sessions of *other* IDE windows be shown (today shows `here`)? Proposed: yes, location in tooltip.

## 7. Visual tokens (light; derive dark via `JBColor` / `JBUI.CurrentTheme`)
| Token | Light | Use |
|---|---|---|
| text | `#1E1F22` | primary |
| text-secondary | `#6C707E` | meta, group headers |
| border | `#EBECF0` | separators |
| accent | `#3574F0` | running dot, focus, primary button |
| accent-bg | `#EDF3FF` | filter chip, selected suggestion |
| needs | `#E07A1F` dot, `#A64B0A` text, `#FFF4EB` row bg |
| blocked | `#B4282E` |
| ok | `#208A3C` | ready / done / added |
| banner-info bg | `#F3F7FF` / border `#DCE6FA` |
| banner-ok bg | `#EFF8F1` / border `#D3EBD9` |

Prefer platform colors where an equivalent exists (`JBUI.CurrentTheme.Banner.*`, `NamedColorUtil.getInactiveTextColor()`, `JBColor.namedColor(...)`). Font: IDE default (13px New UI). Row height 30px, group header top padding 12px, 12px horizontal insets. Use `AllIcons` where possible; otherwise SVG icons with `_dark` variants.

## 8. Implementation guidance
- Discover first: find the current tool window factory, the tree model, the status-tab filtering, "Focus project", and the register-project action. Reuse their models/services; this is a **view** rewrite.
- Inbox list: a `JBList` with a custom renderer + group header items, or a `Tree` with flat group roots — pick whichever fits the existing model; keep keyboard navigation (↑/↓, Enter, context menu, speed search).
- Model changes should arrive via existing listeners/message bus; never block EDT — Issues/PR fetch off-EDT with loading state.
- Persist: collapsed groups, "+more" state, active project filter, banner dismissal (per project).
- Remove: old status tabs, per-project `Issues`/`Pull Requests` folders in the main view, inline `here · <agent>` / age text.

## 9. Acceptance criteria
- [ ] Default view shows only state groups; repos with no active items do not appear in it.
- [ ] Needs-you items are always visible at the top, untruncated, tinted.
- [ ] Clicking a project tag filters to that repo in one click; × or Backspace clears it.
- [ ] `project:` autocomplete works with keyboard only.
- [ ] Issues/PRs for any repo are reachable in ≤2 clicks (Browse projects → expand).
- [ ] Unregistered open project shows banner; Add registers it; dismiss hides banner but header button remains; all entry points vanish after registration.
- [ ] Works in light and dark themes; no EDT freezes when expanding a repo.
- [ ] Existing context-menu actions still work on rows.
