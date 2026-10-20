# Spike: agents using the IDE through IntelliJ's bundled MCP server

Question: can a Drydock task's agent use the IDE it was started from
(inspections, symbol info, refactoring, run configurations) through the MCP
server JetBrains bundles, and what would the plugin have to do to wire it up?

Answer: yes, on the 2025.3 floor the plugin already targets. A 2026.x (263)
IDE makes the wiring cleaner but is not required.

## What was run (2026-10-19)

- Read the bundled plugin `com.intellij.mcpServer` in IU 2025.3.3
  (build 253.31033.145) and IU 263.7031.23: `plugin.xml`, `javap`, and the
  class constant pools.
- Live, in a `runIde` sandbox (IU 2025.3.3) with a one-file Java repo open:
  - Spoke MCP over SSE from a script: initialize, `tools/list`, then
    `tools/call`.
  - Ran `claude -p` in that repo with only an `--mcp-config` entry pointing
    at the IDE.
- Not run:
  - Codex or pi against the server.
  - The 263 IDE live: its findings come from the jar only.
  - Any `execute_terminal_command` call.

## Findings

**It is bundled and off by default.**
- The plugin is "MCP Server", id `com.intellij.mcpServer`, in both builds,
  so no install is needed.
- It is enabled through the app-level setting `McpServerSettings`
  (`mcpServer.xml`, `enableMcpServer`); the default port is 64342.
- Pre-writing that file into the sandbox config was enough to turn it on.
- It only started listening once a project was open. The Welcome screen
  alone has no server.

**Transport in 2025.3 is SSE.**
- `GET http://127.0.0.1:64342/sse`, then POST to the endpoint it announces
  (`/message?sessionId=…`).
- It reports protocol 2024-11-05, server "IntelliJ IDEA MCP Server
  2025.3.3".
- 263 adds `/stream` (streamable HTTP) and a separate stdio module.

**Project routing is a header.**
- The server is one per IDE, not one per window. Each call is routed to an
  open project by the `IJ_MCP_SERVER_PROJECT_PATH` request header, or by a
  `projectPath` tool argument.
- With the header set to the repo, calls ran against that project.
- With the header set to a folder no window has open, every call fails with
  "doesn't correspond to any open project".
- This fits Drydock's model: a task opened in its own window is its own
  project, so the header is the task's worktree. A task sharing the main
  window uses the main checkout.
- Gap: a worktree that is open in no window has nothing to route to.

**22 tools in 2025.3.3.** They are:
- files and search: `find_files_*`, `search_in_files_*`,
  `list_directory_tree`, `get_file_text_by_path`;
- editing: `replace_text_in_file`, `create_new_file`, `reformat_file`,
  `rename_refactoring`;
- code insight: `get_file_problems` (inspections), `get_symbol_info`;
- project: `get_project_modules`, `get_project_dependencies`,
  `get_repositories`;
- running: `get_run_configurations`, `execute_run_configuration`,
  `build_project`, `execute_terminal_command`;
- other: `open_file_in_editor`, `get_all_open_file_paths`,
  `runNotebookCell`.

263 adds Diagnostics, IdeScript, Patch, Read, Search, Universal, VCS and
ChangeList toolsets.

**End to end with Claude Code.** The config was:

```json
{"mcpServers":{"intellij":{"type":"sse","url":"http://127.0.0.1:64342/sse",
  "headers":{"IJ_MCP_SERVER_PROJECT_PATH":"/tmp/mcpspike/repo"}}}}
```

- Run with `--strict-mcp-config --allowedTools mcp__intellij__get_file_problems`.
- Claude called the tool and returned the IDE's own inspection results
  ("Method 'hi()' is never used", "Class 'Greeter' is never used").
- No permission denials, 5 turns.

**Confirmations.** `execute_terminal_command` asks for confirmation in the
IDE unless the user turns on "brave mode" (`enableBraveMode`). An agent
running unattended in a Drydock task would stall on that dialog.

**Auth.**
- 2025.3 already has a token-scoped path: a "private" server on port 64442,
  `McpServerService.authorizedSession(...)`, and the `IJ_MCP_AUTH_TOKEN`
  header. With it the server runs in "restricted mode" and rejects calls
  without a valid token.
- That is how JetBrains' own agents get a session without the user enabling
  the public server.
- The catch: it is a Kotlin suspend API in an `impl` package. No
  `@ApiStatus` marker was found, but it is internal in all but name.
- 263 adds extension points that look made for this:
  - `mcpManagedSessionSupport`;
  - `mcpProjectPathCustomizer`;
  - `mcpToolFilterProvider`;
  - `IJ_MCP_ALLOWED_TOOLS`.

**Contributing tools.** Both builds expose the `com.intellij.mcpServer.mcpToolset`
extension point. Drydock could publish its own tools through the IDE's
server, for example listing tasks or starting a task, behind an optional
`<depends>`.

## How Drydock could use it

1. **MVP, 2025.3, public API only.**
   - Add an optional dependency on `com.intellij.mcpServer`.
   - When starting a task, if the server is enabled, add an `intellij`
     entry to the agent's MCP config, next to the Drydock entry it already
     writes:
     - the URL comes from the configured port;
     - the header `IJ_MCP_SERVER_PROJECT_PATH` is the task's worktree, or
       the main checkout.
   - Leave `execute_terminal_command` out of the agent's allowed tools: the
     agent has its own shell, and the IDE confirmation would block it.
   - If the server is off, the New task dialog offers one checkbox, "Let
     the agent use this IDE", which turns it on. Turning on a localhost
     server is the user's call, never silent.
   - Fits Claude (SSE config verified above).
   - Codex and pi need checking. If they cannot do SSE, the IDE's stdio
     bridge (the "copy stdio configuration" path) or 263's `/stream` is the
     route.
2. **Later, 263+.**
   - Use managed or authorized sessions, so each task gets a token-scoped
     session without enabling the public port.
   - Use `IJ_MCP_ALLOWED_TOOLS` and `mcpProjectPathCustomizer` to bind a
     session to its task's worktree and a tool allowlist, instead of
     relying on the header and the agent's allowlist.
3. **Gap to decide.** A task whose worktree is open in no window cannot use
   the IDE. Either the task always opens its window, which is the default
   today, or the tool falls back to the main project with a `projectPath`.

## Reproducing

1. Write `options/mcpServer.xml` with
   `<component name="McpServerSettings"><option name="enableMcpServer" value="true"/></component>`
   into `intellij-plugin/.intellijPlatform/sandbox/drydock-intellij/IU-2025.3.3/config_runIde/`.
2. Run `./gradlew runIde --args=<repo>` and trust the project.
3. Wait for `lsof -iTCP:64342 -sTCP:LISTEN`, then run `claude -p` with the
   config above.
