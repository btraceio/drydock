package app.drydock.agent.providers.claude.internal;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Installs the Claude Code hooks that report what a managed session is
 * actually doing, so the UI can distinguish a busy Claude from one waiting
 * for a human (see {@link SessionActivity}).
 *
 * <p>The hooks are injected per launch via {@code claude --settings <file>}
 * rather than written into any of the user's own settings files. Two
 * properties make this safe, both verified empirically against claude
 * 2.1.215 before this was built:</p>
 *
 * <ul>
 *   <li><b>Hooks merge across settings scopes.</b> Unlike ordinary settings
 *   keys, where higher precedence overrides lower, every hook registered for
 *   an event fires regardless of which scope declared it. Injecting here
 *   therefore ADDS to whatever hooks the user already configured in
 *   {@code ~/.claude/settings.json} or a project's {@code .claude/}; it
 *   never replaces them.</li>
 *   <li><b>No leakage outside this app.</b> Because the flag is present only
 *   on commands this manager builds, a {@code claude} the user starts from a
 *   plain terminal has no idea these hooks exist.</li>
 * </ul>
 *
 * <p>Sessions are correlated by claude's own session id: the {@code
 * session_id} in every hook payload is exactly the UUID passed to {@code
 * claude --session-id} (also verified empirically). The hook script reads it
 * from its stdin payload rather than from an injected environment variable,
 * which keeps reporting correct even on the bare {@code claude --resume}
 * fallback path where this app never names an id on the command line.</p>
 */
public final class ClaudeHookInstaller {

    private static final Logger LOG = System.getLogger(ClaudeHookInstaller.class.getName());

    /**
     * Stamped into the generated settings file's {@code _comment} so a user who
     * opens it can tell which build wrote it. Purely diagnostic: {@link
     * #install()} rewrites both files unconditionally on every startup, so
     * nothing compares this value.
     */
    private static final String INSTALL_VERSION = "1";

    /**
     * POSIX sh, because it runs on every hook invocation and must add no
     * measurable latency to a session. Deliberately avoids jq (not guaranteed
     * installed); field extraction is done with {@code sed} instead, keeping
     * the whole script to a handful of builtins and at most two short pipelines.
     *
     * <p>Every path exits 0. A nonzero exit from a hook surfaces a warning
     * into the user's session, and a status badge must never be able to
     * disrupt the thing it is reporting on.</p>
     */
    private static final String HOOK_SCRIPT = """
            #!/bin/sh
            # Managed by Drydock -- regenerated on launch; do not edit.
            # usage: drydock-activity.sh <busy|idle|notify|end>
            set -u
            dir="$(dirname "$0")/../activity"
            payload=$(cat 2>/dev/null || true)

            # Single-line JSON; "session_id" appears once, so a greedy match is exact.
            id=$(printf '%s' "$payload" \\
                 | sed -n 's/.*"session_id"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')
            [ -n "$id" ] || exit 0

            state="$1"
            if [ "$state" = "notify" ]; then
              # notification_type distinguishes "Claude is blocked on a human"
              # from routine chatter. Extract the VALUE and compare it exactly:
              # matching the bare keyword anywhere in the payload would also fire
              # on free text, since the same payload carries human-readable
              # "message" and "title" fields.
              ntype=$(printf '%s' "$payload" \\
                      | sed -n 's/.*"notification_type"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')
              case "$ntype" in
                permission_prompt|idle_prompt|agent_needs_input) state=attention ;;
                *) state=busy ;;
              esac
            fi

            if [ "$state" = "end" ]; then
              rm -f "$dir/$id" 2>/dev/null
              exit 0
            fi

            mkdir -p "$dir" 2>/dev/null || exit 0
            tmp="$dir/.$id.tmp.$$"
            # Write-then-rename: the reader polls this directory and must never
            # observe a half-written state word.
            printf '%s' "$state" > "$tmp" 2>/dev/null && mv "$tmp" "$dir/$id" 2>/dev/null
            rm -f "$tmp" 2>/dev/null
            exit 0
            """;

    /**
     * Editor-ask launch hook: appends one tab-separated line
     * {@code nonce event session notification_type agent markers} to the
     * per-launch event log. Records no prompt text: on a submit only the
     * canonical editor-ask IDs found in the prompt are kept. Same safety
     * rules as {@link #HOOK_SCRIPT}: every path exits 0.
     */
    private static final String EDITOR_ASK_SCRIPT = """
            #!/bin/sh
            # Managed by Drydock -- regenerated per editor-ask launch; do not edit.
            # usage: drydock-editor-ask.sh <event> <nonce> <log>
            set -u
            event="$1"; nonce="$2"; log="$3"
            payload=$(cat 2>/dev/null || true)
            sid=$(printf '%s' "$payload" \\
                  | sed -n 's/.*"session_id"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')
            [ -n "$sid" ] || exit 0
            ntype=""; agent=0; markers=""
            if [ "$event" = "UserPromptSubmit" ]; then
              markers=$(printf '%s' "$payload" \\
                        | grep -o 'editor-[0-9a-f]\\{8\\}-[0-9a-f]\\{4\\}-[0-9a-f]\\{4\\}-[0-9a-f]\\{4\\}-[0-9a-f]\\{12\\}' \\
                        | head -n 8 | tr '\\n' ',')
            else
              case "$payload" in *'"agent_id"'*) agent=1 ;; esac
              ntype=$(printf '%s' "$payload" \\
                      | sed -n 's/.*"notification_type"[[:space:]]*:[[:space:]]*"\\([^"]*\\)".*/\\1/p')
            fi
            printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "$nonce" "$event" "$sid" "$ntype" "$agent" "$markers" \\
              >> "$log" 2>/dev/null
            exit 0
            """;

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** Per-launch hook wiring for an editor-ask conversation. */
    public record EditorAskLaunchHooks(String nonce, Path settingsFile, Path eventLog) {}

    private final Path baseDirectory;

    public ClaudeHookInstaller(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    /** Directory the hook script writes state words into, one file per claude session id. */
    public Path activityDirectory() {
        return baseDirectory.resolve("activity");
    }

    /** The file to pass to {@code claude --settings}; only valid after {@link #install()}. */
    public Path settingsFile() {
        return hooksDirectory().resolve("settings.json");
    }

    private Path hooksDirectory() {
        return baseDirectory.resolve("hooks");
    }

    private Path scriptFile() {
        return hooksDirectory().resolve("drydock-activity.sh");
    }

    /**
     * Writes the hook script and its settings file, creating the activity
     * directory. Idempotent, and cheap enough to run on every startup.
     * Performs filesystem I/O, so callers must invoke it off the UI
     * application thread (AGENTS.md).
     */
    public void install() throws IOException {
        Files.createDirectories(hooksDirectory());
        Files.createDirectories(activityDirectory());
        purgeStaleActivity();
        writeAtomically(scriptFile(), HOOK_SCRIPT);
        writeAtomically(settingsFile(), settingsJson());
    }

    /**
     * Writes the per-launch settings (the ordinary hooks plus the editor-ask
     * recorder, which only appends events to a launch-private log) for a Q&A
     * launch carrying {@code nonce}. The user's own settings are untouched and
     * no permission setting is relaxed. {@link #install()} must have run.
     */
    public EditorAskLaunchHooks editorAskLaunchPaths(String nonce) {
        if (!NONCE.matcher(Objects.requireNonNull(nonce, "nonce")).matches()) {
            throw new IllegalArgumentException("invalid launch nonce");
        }
        Path directory = baseDirectory.resolve("editor-ask").resolve(nonce);
        return new EditorAskLaunchHooks(nonce, directory.resolve("settings.json"), directory.resolve("events.log"));
    }

    public EditorAskLaunchHooks installEditorAskLaunch(String nonce) throws IOException {
        EditorAskLaunchHooks hooks = editorAskLaunchPaths(nonce);
        Path dir = hooks.settingsFile().getParent();
        try {
            Files.createDirectories(dir);
            Path script = hooksDirectory().resolve("drydock-editor-ask.sh");
            writeAtomically(script, EDITOR_ASK_SCRIPT);
            Path log = hooks.eventLog();
            if (!Files.exists(log)) {
                try {
                    Files.createFile(log, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(log);
                }
            }
            String ordinary = jsonQuote(scriptFile().toString());
            String recorder = jsonQuote(script.toString());
            String tail = " " + nonce + " " + jsonQuote(log.toString());
            writeAtomically(hooks.settingsFile(), """
                    {
                      "_comment": "Generated by Drydock (v%1$s) for one editor-ask launch.",
                      "hooks": {
                        "UserPromptSubmit": [{ "hooks": [{ "type": "command", "command": "sh %2$s busy" }] },
                                             { "hooks": [{ "type": "command", "command": "sh %3$s UserPromptSubmit%4$s" }] }],
                        "Notification":     [{ "hooks": [{ "type": "command", "command": "sh %2$s notify" }] },
                                             { "hooks": [{ "type": "command", "command": "sh %3$s Notification%4$s" }] }],
                        "Stop":             [{ "hooks": [{ "type": "command", "command": "sh %2$s idle" }] },
                                             { "hooks": [{ "type": "command", "command": "sh %3$s Stop%4$s" }] }],
                        "SessionEnd":       [{ "hooks": [{ "type": "command", "command": "sh %2$s end" }] }],
                        "PreToolUse":       [{ "hooks": [{ "type": "command", "command": "sh %3$s PreToolUse%4$s" }] }],
                        "SubagentStop":     [{ "hooks": [{ "type": "command", "command": "sh %3$s SubagentStop%4$s" }] }]
                      }
                    }
                    """.formatted(INSTALL_VERSION, ordinary, recorder, tail));
            return hooks;
        } catch (IOException | RuntimeException e) {
            deleteLaunchDirectory(dir);
            throw e;
        }
    }

    private static void deleteLaunchDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Launch cleanup is best effort; shared hook artifacts are outside this directory.
        }
    }

    /**
     * Drops activity left behind by a previous process run.
     *
     * <p>This is a routine path, not a crash-only safety net. Closing a session
     * sends Ctrl+D and force-closes the child after a 3s grace period (see
     * {@code GhosttySurface.closeGracefully}); a force-killed claude never runs
     * its {@code SessionEnd} hook, so its state file survives. Observed live: a
     * clean app quit (exit 0) that hit the force-close path still left a session
     * reporting NEEDS_ATTENTION with no process behind it, which would have
     * badged on every subsequent launch.</p>
     *
     * <p>Why claude did not exit on Ctrl+D in that run is NOT established here.
     * {@code docs/claude-integration.md} attributes the general failure to a key
     * encoding bug ({@code unshifted_codepoint=0}), fixed but recorded as not
     * yet re-verified. Do not read this method as evidence about which sessions
     * ignore Ctrl+D -- it only assumes that some do.</p>
     *
     * <p>No terminal process survives an app restart, so every file present at
     * startup is by definition stale. This mirrors {@code
     * SessionManager.normalizeLoadedState}, which resets persisted RUNNING
     * statuses for the same reason.</p>
     */
    private void purgeStaleActivity() throws IOException {
        try (Stream<Path> stale = Files.list(activityDirectory())) {
            for (Path file : stale.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            // Cosmetic cleanup: a failure here must not prevent installation,
            // which is what actually makes sessions report at all.
            LOG.log(Level.WARNING, "Could not purge stale session activity: " + e.getMessage());
        }
    }

    /**
     * Hook registrations covering the full busy/waiting/idle cycle. No {@code
     * matcher} field: that filters by tool name and applies only to tool
     * events, none of which are used here.
     */
    private String settingsJson() {
        String script = jsonQuote(scriptFile().toString());
        return """
                {
                  "_comment": "Generated by Drydock (v%s). Injected per launch via --settings; merges with your own hooks.",
                  "hooks": {
                    "UserPromptSubmit": [{ "hooks": [{ "type": "command", "command": "sh %s busy" }] }],
                    "Notification":     [{ "hooks": [{ "type": "command", "command": "sh %s notify" }] }],
                    "Stop":             [{ "hooks": [{ "type": "command", "command": "sh %s idle" }] }],
                    "SessionEnd":       [{ "hooks": [{ "type": "command", "command": "sh %s end" }] }]
                  }
                }
                """.formatted(INSTALL_VERSION, script, script, script, script);
    }

    /**
     * Quotes a path for embedding inside a JSON string that is itself a shell
     * command: JSON escaping for the backslashes and quotes, then shell
     * single-quoting so spaces in "Application Support" survive {@code sh -c}.
     */
    private static String jsonQuote(String path) {
        String shellQuoted = "'" + path.replace("'", "'\\''") + "'";
        return shellQuoted.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Temp-file-plus-rename, so a concurrently launching claude never reads a partial file. */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
