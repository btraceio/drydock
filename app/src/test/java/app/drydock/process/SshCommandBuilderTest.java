package app.drydock.process;

import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshCommandBuilderTest {

    private final SshRemote remote = new SshRemote("user@h", "/srv/my repo");

    @Test
    void remoteGitCommandShape() {
        List<String> command = SshCommandBuilder.remoteGitCommand(remote,
                List.of("status", "--porcelain=v2", "--branch", "-z"));
        assertEquals(List.of(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=5",
                "-o", "ServerAliveInterval=3",
                "-o", "ServerAliveCountMax=2",
                "--", "user@h",
                "git -C '/srv/my repo' 'status' '--porcelain=v2' '--branch' '-z'"),
                command);
    }

    @Test
    void dashDashImmediatelyPrecedesHost() {
        // The option-injection guard: everything after -- can never be
        // parsed as an ssh option, and everything after the host is the
        // remote command (a -- placed *after* the host would reach the
        // remote shell and break it).
        List<String> command = SshCommandBuilder.remoteGitCommand(remote, List.of("rev-parse"));
        int dashDash = command.indexOf("--");
        assertEquals("user@h", command.get(dashDash + 1));
        assertEquals(command.size() - 1, dashDash + 2);
    }

    @Test
    void posixQuoteHandlesMetacharacters() {
        assertEquals("'plain'", SshCommandBuilder.posixQuote("plain"));
        assertEquals("'sp ace'", SshCommandBuilder.posixQuote("sp ace"));
        assertEquals("'a'\\''b'", SshCommandBuilder.posixQuote("a'b"));
        assertEquals("'$HOME `x` \"q\" \n *'", SshCommandBuilder.posixQuote("$HOME `x` \"q\" \n *"));
    }

    @Test
    void interactiveSessionCommandShape() {
        String command = SshCommandBuilder.interactiveSessionCommand(remote, "exec claude");
        assertEquals("ssh -t -o ServerAliveInterval=30 -o ServerAliveCountMax=6"
                + " -- 'user@h' "
                + "'export TERM=xterm-256color; cd '\\''/srv/my repo'\\'' && exec claude'",
                command);
    }

    @Test
    void interactiveKeepAliveBudgetFarOutlivesTheBatchOne() {
        // The poller behind the batch path must surface an unreachable host
        // fast; a terminal the user is sitting in must ride out a wifi
        // hiccup. Read both budgets back off the built commands so tuning
        // one of them toward the other fails here.
        int batch = keepAliveBudgetSeconds(String.join(" ",
                SshCommandBuilder.remoteGitCommand(remote, List.of("status"))));
        int interactive = keepAliveBudgetSeconds(
                SshCommandBuilder.interactiveSessionCommand(remote, "exec claude"));
        assertTrue(interactive >= 10 * batch,
                "interactive budget " + interactive + "s must dwarf batch " + batch + "s");
    }

    /** {@code ServerAliveInterval * ServerAliveCountMax}: seconds of silence ssh tolerates. */
    private static int keepAliveBudgetSeconds(String command) {
        return optionValue(command, "ServerAliveInterval") * optionValue(command, "ServerAliveCountMax");
    }

    private static int optionValue(String command, String option) {
        Matcher matcher = Pattern.compile(option + "=(\\d+)").matcher(command);
        assertTrue(matcher.find(), option + " missing from: " + command);
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    void interactiveSessionCommandQuotesEmbeddedRemoteArgs() {
        // Second quoting layer: the caller remote-quotes its own args, the
        // builder then local-quotes the whole remote command once more.
        String resume = "exec claude --resume " + SshCommandBuilder.posixQuote("abc-123");
        String command = SshCommandBuilder.interactiveSessionCommand(remote, resume);
        assertTrue(command.startsWith("ssh -t "));
        assertTrue(command.contains(" -- 'user@h' '"));
        assertTrue(command.contains("--resume"));
        assertTrue(command.endsWith("'"));
    }
}
