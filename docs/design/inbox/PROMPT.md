# Kickoff prompt for Claude Code

Paste this into Claude Code from the root of the Drydock plugin repo, with this folder copied into the repo (e.g. `docs/design/inbox/`).

---

I want to redesign the Drydock tool window. The full spec is in `docs/design/inbox/SPEC.md`, and the interactive prototype it's based on is in `docs/design/inbox/reference/inbox-mockup.dc.html` (read its `<script>` block for exact behavior; the markup shows layout and colors). `reference/current-ui.png` is today's UI.

Please:
1. Read the spec and the mockup.
2. Explore the codebase: find the tool window factory, the current tree/tab model, the "Focus project" action, and the action that registers the currently opened project. Summarize what exists and how you'll map it onto the spec.
3. Ask me the open questions in SPEC §6 before you start building.
4. Propose a short plan in steps (e.g. inbox list + grouping → search/project filter → projects view → add-project banner → persistence → cleanup of old tabs). Wait for my OK.
5. Implement step by step. Run the build and tests after each step, and check it in `runIde`.

Constraints: reuse the existing services and models (this is a view rewrite), never block the EDT, support light and dark themes, and keep the existing context-menu actions working.
