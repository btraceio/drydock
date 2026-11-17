package app.drydock.workspace;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link BoxLifecycle} decision table, table-driven: one case per
 * row of the contract's component-4 table, plus the ambiguity rows that
 * AC-16/INV-13 demand — an ssh failure with a listed non-8 status, a CLI
 * failure, a CLI timeout, an unparseable table, and a missing CLI — all
 * of which must land in TRANSIENT_ERROR, never in OFFER_RECREATE or
 * CREATE. Every input is an already-produced value: {@code decide} spawns
 * nothing and consults no process (the done-when), so nothing here
 * touches a runner or the network.
 */
class BoxLifecycleTest {

    private static final String BOX = "drydock-source";

    /** The probe results: the only good answer, and the four failure classes. */
    private static final BoxShell.BoxResult SSH_OK = new BoxShell.BoxResult.Ok("");
    private static final BoxShell.BoxResult SSH_UNREACHABLE =
            new BoxShell.BoxResult.Unreachable("ssh: connect to host workspace-drydock-source port 22: No route to host");
    private static final BoxShell.BoxResult SSH_TIMEOUT = new BoxShell.BoxResult.Timeout(Duration.ofSeconds(10));
    private static final BoxShell.BoxResult SSH_REMOTE_FAILED = new BoxShell.BoxResult.RemoteFailed(3, "sh: true: not found");
    private static final BoxShell.BoxResult SSH_NOT_INSTALLED = new BoxShell.BoxResult.SshNotInstalled("ssh");

    private static final WorkspacesCli.Listed CLI_STOPPED =
            new WorkspacesCli.Listed(Map.of(BOX, "8"));
    private static final WorkspacesCli.Listed CLI_OTHER_STATUS =
            new WorkspacesCli.Listed(Map.of(BOX, "2"));
    private static final WorkspacesCli.Listed CLI_NO_SUCH_BOX =
            new WorkspacesCli.Listed(Map.of("someone-elses-box", "2"));

    private static BoxLifecycle.CliStatus listed(WorkspacesCli.Listed table) {
        return BoxLifecycle.cliStatus(table, BOX);
    }

    /** One row of the contract's table: inputs in, expected outcome out. */
    private record Case(String name, BoxShell.BoxResult sshProbe, BoxLifecycle.CliStatus cliStatus,
                        boolean mappingKnown, boolean bootstrapped, BoxLifecycle.Outcome expected) {
    }

    /**
     * The contract's table in full. The ssh-ok rows deliberately carry
     * CLI answers that would decide otherwise, pinning that the CLI is
     * not consulted when the box answers ssh; the ssh-fail rows are
     * driven through the unreachable probe, with the other probe failure
     * classes covered separately below.
     */
    private static Stream<Case> table() {
        return Stream.of(
                // ssh ok → REUSE if bootstrapped else BOOTSTRAP_THEN_REUSE (CLI not consulted)
                new Case("ssh ok, bootstrapped → REUSE (a CLI failure is not consulted)",
                        SSH_OK, BoxLifecycle.cliStatus(new WorkspacesCli.Failed(1, "boom"), BOX),
                        true, true, new BoxLifecycle.Outcome.Reuse()),
                new Case("ssh ok, not bootstrapped → BOOTSTRAP_THEN_REUSE (a stopped listing is not consulted)",
                        SSH_OK, listed(CLI_STOPPED),
                        true, false, new BoxLifecycle.Outcome.BootstrapThenReuse()),
                // ssh fail + STOPPED → OFFER_RECREATE(STOPPED), with or without a mapping
                new Case("ssh fail, CLI stopped, mapping known → OFFER_RECREATE(STOPPED)",
                        SSH_UNREACHABLE, listed(CLI_STOPPED),
                        true, false, new BoxLifecycle.Outcome.OfferRecreate(BoxLifecycle.RecreateReason.STOPPED)),
                new Case("ssh fail, CLI stopped, mapping unknown → OFFER_RECREATE(STOPPED)",
                        SSH_UNREACHABLE, listed(CLI_STOPPED),
                        false, false, new BoxLifecycle.Outcome.OfferRecreate(BoxLifecycle.RecreateReason.STOPPED)),
                // ssh fail + ABSENT: first box vs gone mapped box (D2)
                new Case("ssh fail, CLI absent, mapping unknown → CREATE under the base name",
                        SSH_UNREACHABLE, listed(CLI_NO_SUCH_BOX),
                        false, false, new BoxLifecycle.Outcome.Create()),
                new Case("ssh fail, CLI absent, mapping known → OFFER_RECREATE(GONE)",
                        SSH_UNREACHABLE, listed(CLI_NO_SUCH_BOX),
                        true, false, new BoxLifecycle.Outcome.OfferRecreate(BoxLifecycle.RecreateReason.GONE)),
                // ssh fail + OTHER → TRANSIENT_ERROR, whatever the mapping says
                new Case("ssh fail, CLI lists a non-8 status, mapping unknown → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, listed(CLI_OTHER_STATUS),
                        false, false, new BoxLifecycle.Outcome.TransientError()),
                new Case("ssh fail, CLI lists a non-8 status, mapping known → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, listed(CLI_OTHER_STATUS),
                        true, false, new BoxLifecycle.Outcome.TransientError()),
                // ssh fail + CLI could not answer → TRANSIENT_ERROR (the ambiguity rows)
                new Case("ssh fail, CLI failed → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, BoxLifecycle.cliStatus(new WorkspacesCli.Failed(1, "rpc error"), BOX),
                        false, false, new BoxLifecycle.Outcome.TransientError()),
                new Case("ssh fail, CLI timed out → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, BoxLifecycle.cliStatus(new WorkspacesCli.TimedOut("no reply"), BOX),
                        false, false, new BoxLifecycle.Outcome.TransientError()),
                new Case("ssh fail, CLI table unparseable → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, BoxLifecycle.cliStatus(new WorkspacesCli.Unparseable("no header"), BOX),
                        false, false, new BoxLifecycle.Outcome.TransientError()),
                new Case("ssh fail, CLI not installed → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, BoxLifecycle.cliStatus(new WorkspacesCli.NotInstalled("no binary"), BOX),
                        false, false, new BoxLifecycle.Outcome.TransientError()),
                new Case("ssh fail, CLI output over the cap → TRANSIENT_ERROR",
                        SSH_UNREACHABLE, BoxLifecycle.cliStatus(new WorkspacesCli.OutputTooLarge("too much"), BOX),
                        false, false, new BoxLifecycle.Outcome.TransientError()));
    }

    @Test
    void decideYieldsExactlyTheTableRow() {
        table().forEach(caseRow -> assertEquals(caseRow.expected(),
                BoxLifecycle.decide(caseRow.sshProbe(), caseRow.cliStatus(),
                        caseRow.mappingKnown(), caseRow.bootstrapped()),
                caseRow.name()));
    }

    @Test
    void everyProbeFailureClassCountsAsSshFail() {
        // With the CLI affirmatively answering STOPPED, each of the four
        // probe failure classes must take the ssh-fail branch: ambiguity
        // about reachability never reaches CREATE, but a stopped box is a
        // definite answer worth offering a re-create for.
        for (BoxShell.BoxResult probeFail : new BoxShell.BoxResult[]{
                SSH_UNREACHABLE, SSH_TIMEOUT, SSH_REMOTE_FAILED, SSH_NOT_INSTALLED}) {
            assertEquals(new BoxLifecycle.Outcome.OfferRecreate(BoxLifecycle.RecreateReason.STOPPED),
                    BoxLifecycle.decide(probeFail, listed(CLI_STOPPED), true, false),
                    probeFail.getClass().getSimpleName());
        }
        // And with the CLI failing too, the same failures stay ambiguous.
        BoxLifecycle.CliStatus cliFailed =
                BoxLifecycle.cliStatus(new WorkspacesCli.Failed(1, "boom"), BOX);
        for (BoxShell.BoxResult probeFail : new BoxShell.BoxResult[]{
                SSH_UNREACHABLE, SSH_TIMEOUT, SSH_REMOTE_FAILED, SSH_NOT_INSTALLED}) {
            assertEquals(new BoxLifecycle.Outcome.TransientError(),
                    BoxLifecycle.decide(probeFail, cliFailed, false, false),
                    probeFail.getClass().getSimpleName());
        }
    }

    @Test
    void cliStatusReducesAListedTableToTheBoxStatus() {
        listedStatusOf(CLI_STOPPED, WorkspacesCli.BoxStatus.STOPPED);
        listedStatusOf(CLI_OTHER_STATUS, WorkspacesCli.BoxStatus.OTHER);
        listedStatusOf(CLI_NO_SUCH_BOX, WorkspacesCli.BoxStatus.ABSENT);
    }

    private static void listedStatusOf(WorkspacesCli.Listed table, WorkspacesCli.BoxStatus expected) {
        BoxLifecycle.CliStatus.Listed listed =
                assertInstanceOf(BoxLifecycle.CliStatus.Listed.class, BoxLifecycle.cliStatus(table, BOX));
        assertSame(expected, listed.status());
    }

    @Test
    void cliStatusCarriesEveryCliFailureThroughAsUnavailable() {
        for (WorkspacesCli.ListResult failure : new WorkspacesCli.ListResult[]{
                new WorkspacesCli.NotInstalled("no binary"),
                new WorkspacesCli.Failed(1, "boom"),
                new WorkspacesCli.TimedOut("no reply"),
                new WorkspacesCli.OutputTooLarge("too much"),
                new WorkspacesCli.Unparseable("no header")}) {
            BoxLifecycle.CliStatus status = BoxLifecycle.cliStatus(failure, BOX);
            BoxLifecycle.CliStatus.Unavailable unavailable =
                    assertInstanceOf(BoxLifecycle.CliStatus.Unavailable.class, status,
                            failure.getClass().getSimpleName());
            assertSame(failure, unavailable.failure());
        }
    }

    @Test
    void ambiguousInputsNeverReachCreateOrOfferRecreate() {
        // The INV-13 half of the pin, stated directly: every ambiguity
        // row of the table (and every probe failure without a CLI answer
        // that explains it) is TRANSIENT_ERROR — never OFFER_RECREATE,
        // never CREATE, whatever mappingKnown and bootstrapped say.
        table().filter(caseRow -> caseRow.expected() instanceof BoxLifecycle.Outcome.TransientError)
                .forEach(caseRow -> {
                    for (boolean mappingKnown : new boolean[]{false, true}) {
                        for (boolean bootstrapped : new boolean[]{false, true}) {
                            BoxLifecycle.Outcome outcome = BoxLifecycle.decide(
                                    caseRow.sshProbe(), caseRow.cliStatus(), mappingKnown, bootstrapped);
                            assertEquals(new BoxLifecycle.Outcome.TransientError(), outcome,
                                    caseRow.name() + " (mappingKnown=" + mappingKnown
                                            + ", bootstrapped=" + bootstrapped + ")");
                        }
                    }
                });
    }

    @Test
    void decideRejectsNullInputs() {
        BoxLifecycle.CliStatus status = listed(CLI_STOPPED);
        assertThrows(NullPointerException.class,
                () -> BoxLifecycle.decide(null, status, true, false));
        assertThrows(NullPointerException.class,
                () -> BoxLifecycle.decide(SSH_OK, null, true, false));
        assertThrows(NullPointerException.class,
                () -> BoxLifecycle.cliStatus(null, BOX));
        assertThrows(NullPointerException.class,
                () -> BoxLifecycle.cliStatus(CLI_STOPPED, null));
    }

    @Test
    void theClassSpawnsNothing() {
        // The done-when's structural half: BoxLifecycle is a static-only
        // class with no state of its own — no runner, no binary locator, no
        // way to hold a process seam — and its two methods take only
        // already-produced values, so the decision table can never itself
        // consult a process.
        assertTrue(java.util.Arrays.stream(BoxLifecycle.class.getDeclaredConstructors())
                .allMatch(c -> java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
        assertEquals(0, java.util.Arrays.stream(BoxLifecycle.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .count());
        assertEquals(0, java.util.Arrays.stream(BoxLifecycle.class.getDeclaredFields())
                .filter(f -> f.getType() == BoxShell.class || f.getType() == WorkspacesCli.class
                        || f.getType() == CommandRunner.class)
                .count());
    }
}
