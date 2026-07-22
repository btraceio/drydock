package app.drydock.terminal.api;

/**
 * What a terminal surface should run: a single POSIX shell command string
 * (libghostty always executes it via a shell) and the working directory to
 * start it in. The seam that makes "run Claude" and "run a plain shell" just
 * two different specs of the same surface machinery.
 *
 * <p>On macOS libghostty wraps the command itself, running {@code
 * /usr/bin/login -flp <user> /bin/bash --noprofile --norc -c "exec -l
 * <command>"} (see {@code execCommand} in ghostty's {@code
 * src/termio/Exec.zig}). So a command must NOT prepend its own {@code exec}
 * or add {@code -l} for a login shell -- that is already done for it.
 */
public record TerminalSpec(String command, String workingDirectory) {

    /**
     * A plain interactive login shell: the user's {@code $SHELL} (falling back
     * to {@code /bin/zsh}), which libghostty's own {@code exec -l} wrapper
     * turns into a login shell, so it reads the user's normal profile.
     */
    public static TerminalSpec loginShell(String workingDirectory) {
        return new TerminalSpec("${SHELL:-/bin/zsh}", workingDirectory);
    }
}
