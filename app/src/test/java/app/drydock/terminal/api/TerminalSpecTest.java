package app.drydock.terminal.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalSpecTest {

    @Test
    void loginShellRunsTheDefaultShellInteractivelyInTheGivenDirectory() {
        TerminalSpec spec = TerminalSpec.loginShell("/work/repo");
        // Bare $SHELL (falling back to zsh): libghostty supplies the
        // `exec -l` wrapper that makes it a login shell, so adding our own
        // would exec a program literally named "exec".
        assertEquals("${SHELL:-/bin/zsh}", spec.command());
        assertEquals("/work/repo", spec.workingDirectory());
    }

    @Test
    void plainConstructorKeepsCommandAndDirectoryVerbatim() {
        TerminalSpec spec = new TerminalSpec("claude --resume 'x'", "/work/repo");
        assertEquals("claude --resume 'x'", spec.command());
        assertEquals("/work/repo", spec.workingDirectory());
    }
}
