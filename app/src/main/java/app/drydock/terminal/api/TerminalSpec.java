package app.drydock.terminal.api;

/**
 * What a terminal surface should run: a single POSIX shell command string
 * (libghostty always executes it via {@code /bin/sh -c "<command>"}) and the
 * working directory to start it in. The seam that makes "run Claude" and "run
 * a plain shell" just two different specs of the same surface machinery.
 */
public record TerminalSpec(String command, String workingDirectory) {

    /**
     * A plain interactive login shell: {@code exec} replaces {@code /bin/sh}
     * with the user's {@code $SHELL} (falling back to {@code /bin/zsh}) as a
     * login shell ({@code -l}), so it reads the user's normal profile.
     */
    public static TerminalSpec loginShell(String workingDirectory) {
        return new TerminalSpec("exec ${SHELL:-/bin/zsh} -l", workingDirectory);
    }
}
