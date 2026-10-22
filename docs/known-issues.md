# Known issues

Defects that are understood but not fixed yet. Each entry says how the defect
occurs, what the user sees, and a possible fix. Remove an entry when its fix
lands.

## IntelliJ plugin: a Start that races a finished delete brings the row back

Found by the in-depth review on 2026-10-22. The defect existed before
`LaunchClaims`, which does not cover it.

**Sequence** (`intellij-plugin/.../session/DrydockSessionService.java`):

1. `startSession` reads the row with `requireSession` and builds a `STARTING`
   copy of it.
2. `deleteSession` runs to the end on another executor thread. It takes and
   ends its removal claim, and removes the row.
3. `startSession` continues in `launchPrepared`. `tryClaim` succeeds, because
   no claim and no live execution exist now.
4. `startLaunch` calls `persistSession`, an upsert. The row is gone, so the
   upsert adds it again.

**Effect**: the user forgot the task, but it comes back as a `STARTING`
session and the launch continues. The launch mints MCP access for it, as for
any other launch. A delete that runs while a launch holds its claim is refused,
so only this narrow window remains.

**Possible fix**: after `tryClaim` succeeds, read the row again on the
`startSession` path and refuse the launch when the row is gone. Alternatively,
let only the create path insert, and let `startSession` replace only an
existing row.
