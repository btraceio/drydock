# UI redesign (design-handoff implementation)

The JavaFX UI was rebuilt against the high-fidelity design handoff
(`Claude Project Manager.dc.html` + its README, provided out-of-repo in
`/tmp/handoff`). The design README is the source of truth for measurements,
colors, and interactions.

## Structure

| Piece | Class | Notes |
|---|---|---|
| Window shell | `app.cpm.ui.AppShell` | Undecorated stage, custom 44px title bar (`TitleBar`), manual edge resize (`StageResizer`), SplitPane with sidebar clamped 220–520px, in-scene `ModalLayer`. |
| Theming | `ThemeManager` + `app.css` / `theme-dark.css` / `theme-light.css` | `app.css` is structure only; ALL colors are looked-up tokens in the two theme sheets, swapped at runtime. Theme persists in `WorkspaceUiState.theme`. JetBrains Mono is bundled under `resources/app/cpm/ui/fonts/`. |
| Sidebar | `RepositorySidebar` | `TreeView` of repos → session children, custom cells (caret, branch line, running dot, hover quick-actions), accent Add-repository menu (disk / GitHub clone), live filter, footer status line. |
| Tabs + session view | `MainWorkspace` + `OpenSessionTab` | Two-line tab graphic (repo over title) with status dot + close ×, double-click inline rename, trailing "+" repo menu; per-tab session header (back, title/meta, status pill, rename) above the ghostty host. |
| Resume picker | `ResumePickerView` + `app.cpm.claude.ConversationCatalog` | Shows when no tab is selected (or after Back/Esc). Lists real conversations from `~/.claude/projects/<encoded-cwd>/*.jsonl`; Enter/click adopts the conversation as a managed session (`SessionManager.adoptConversation`) and resumes it exactly (`claude --resume '<uuid>'`). |
| GitHub clone | `GitHubCloneModal` + `app.cpm.github.GitHubService` | Live unauthenticated GitHub search (or pasted URL), `git clone` into a chosen parent dir, auto-registers the clone. Needs the `java.net.http` jlink module (added in `app/build.gradle.kts`). |
| Shortcuts | `ShortcutsOverlay` + scene filter in `CpmApplication` | `?` overlay, Esc close/back, ⌘⇧L theme, ⌘F filter, ⌘N new session, ⌘R rename. |

## Known deviations from the handoff

- The tab-strip "+" button sits at the top-right of the strip (a TabPane
  cannot easily append a trailing node after the last tab).
- The resume picker always shows the "all projects" scope; ⌘A/⌘B scope
  toggles are documented in the keycap footer but not yet implemented.
- Conversation preview (Space) is not implemented.
