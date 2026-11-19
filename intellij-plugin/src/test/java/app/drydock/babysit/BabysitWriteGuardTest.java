package app.drydock.babysit;

import app.drydock.babysit.BabysitWriteGuard.Verdict;
import app.drydock.git.GitStatusService.RemoteBranchTip;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BabysitWriteGuardTest {

    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String MOVED = "2222222222222222222222222222222222222222";

    @Test
    void anUnmovedTipAllowsTheWrite() {
        assertEquals(new Verdict.Allowed(BASE),
                BabysitWriteGuard.verdictFor(new RemoteBranchTip(Optional.of(BASE), Optional.empty()), BASE));
        assertEquals(new Verdict.Allowed(BASE),
                BabysitWriteGuard.verdictFor(new RemoteBranchTip(Optional.of(BASE), Optional.empty()),
                        BASE.toUpperCase() + "\n"), "case and a trailing newline are not a move");
    }

    @Test
    void aMovedTipIsHeadMoved() {
        assertEquals(new Verdict.HeadMoved(MOVED, BASE),
                BabysitWriteGuard.verdictFor(new RemoteBranchTip(Optional.of(MOVED), Optional.empty()), BASE));
    }

    @Test
    void aFailedFetchNeverAllowsEvenWhenTheStaleRefMatches() {
        Verdict verdict = BabysitWriteGuard.verdictFor(
                new RemoteBranchTip(Optional.of(BASE), Optional.of("fatal: unable to access")), BASE);

        Verdict.GitFailed failed = assertInstanceOf(Verdict.GitFailed.class, verdict);
        assertTrue(failed.message().contains("unable to access"), failed.message());
    }

    @Test
    void anUnresolvableTipIsGitFailed() {
        assertInstanceOf(Verdict.GitFailed.class,
                BabysitWriteGuard.verdictFor(new RemoteBranchTip(Optional.empty(), Optional.empty()), BASE));
    }
}
