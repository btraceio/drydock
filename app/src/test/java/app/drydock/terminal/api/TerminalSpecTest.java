package app.drydock.terminal.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalSpecTest {

    @Test
    void loginShellRunsTheDefaultShellInteractivelyInTheGivenDirectory() {
        TerminalSpec spec = TerminalSpec.loginShell("/work/repo");
        // exec replaces /bin/sh (libghostty runs the command via `/bin/sh -c`)
        // with the user's $SHELL as a login shell, falling back to zsh.
        assertEquals("exec ${SHELL:-/bin/zsh} -l", spec.command());
        assertEquals("/work/repo", spec.workingDirectory());
    }

    @Test
    void plainConstructorKeepsCommandAndDirectoryVerbatim() {
        TerminalSpec spec = new TerminalSpec("claude --resume 'x'", "/work/repo");
        assertEquals("claude --resume 'x'", spec.command());
        assertEquals("/work/repo", spec.workingDirectory());
    }
}
