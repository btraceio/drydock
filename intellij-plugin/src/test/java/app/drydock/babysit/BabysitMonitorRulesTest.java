package app.drydock.babysit;

import app.drydock.babysit.BabysitEvent.Kind;
import app.drydock.babysit.BabysitMonitorRules.Observation;
import app.drydock.babysit.BabysitMonitorRules.Step;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Mergeable;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BabysitMonitorRulesTest {

    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    static final String NEXT = "89abcdef0123456789abcdef0123456789abcdef";

    static Snapshot snapshot(Mergeable mergeable, List<Check> checks) {
        return snapshot(PrLifecycle.OPEN, HEAD, mergeable, checks);
    }

    static Snapshot snapshot(PrLifecycle lifecycle, String head, Mergeable mergeable, List<Check> checks) {
        return new Snapshot(42, lifecycle, false, "main", "feat/x", head, mergeable, "CLEAN", checks);
    }

    static Check run(String name, String status, String conclusion) {
        return new Check(Check.Kind.CHECK_RUN, name, status, conclusion, Optional.of("CI"),
                Optional.of("https://github.com/o/r/actions/runs/77/job/1"));
    }

    static Check context(String name, String state) {
        return new Check(Check.Kind.STATUS_CONTEXT, name, state, "", Optional.empty(), Optional.empty());
    }

    private static List<Kind> kinds(Step step) {
        return step.events().stream().map(BabysitEvent::kind).toList();
    }

    /** Walks {@code reads} from a baseline of the first, returning every step's events. */
    private static List<List<BabysitEvent>> walk(Snapshot... reads) {
        Observation observation = Observation.first(reads[0]);
        List<List<BabysitEvent>> events = new ArrayList<>();
        for (int i = 1; i < reads.length; i++) {
            Step step = BabysitMonitorRules.observe(observation, reads[i]);
            events.add(step.events());
            observation = step.observation();
        }
        return events;
    }

    @Test
    void anUnchangedPullRequestYieldsNothing() {
        Snapshot same = snapshot(Mergeable.CONFLICTING, List.of(run("test", "COMPLETED", "FAILURE")));

        Step step = BabysitMonitorRules.observe(Observation.first(same), same);

        assertEquals(List.of(), step.events());
    }

    @Test
    void aConflictAppearsFromMergeableOrFromNeverComputed() {
        for (Mergeable before : List.of(Mergeable.MERGEABLE, Mergeable.UNKNOWN)) {
            Step step = BabysitMonitorRules.observe(Observation.first(snapshot(before, List.of())),
                    snapshot(Mergeable.CONFLICTING, List.of()));
            assertEquals(List.of(Kind.CONFLICT_APPEARED), kinds(step), before.name());
        }
    }

    @Test
    void anUnknownBlipNeitherClearsNorReRaisesAConflict() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of()),
                snapshot(Mergeable.CONFLICTING, List.of()),
                snapshot(Mergeable.UNKNOWN, List.of()),
                snapshot(Mergeable.CONFLICTING, List.of()),
                snapshot(Mergeable.UNKNOWN, List.of()),
                snapshot(Mergeable.MERGEABLE, List.of()));

        assertEquals(List.of(List.of(BabysitEvent.of(Kind.CONFLICT_APPEARED)), List.of(), List.of(), List.of(),
                List.of(BabysitEvent.of(Kind.CONFLICT_CLEARED))), events);
    }

    @Test
    void aConflictPresentAtTheBaselineClearsSilently() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.CONFLICTING, List.of()),
                snapshot(Mergeable.MERGEABLE, List.of()));

        assertEquals(List.of(List.of()), events);
    }

    @Test
    void theFailingSetWidensTheBadgeSignalByTimeoutsAndStartupFailures() {
        Snapshot snapshot = snapshot(Mergeable.MERGEABLE, List.of(
                run("failure", "COMPLETED", "FAILURE"),
                run("error", "COMPLETED", "ERROR"),
                run("timeout", "COMPLETED", "TIMED_OUT"),
                run("startup", "COMPLETED", "STARTUP_FAILURE"),
                run("cancelled", "COMPLETED", "CANCELLED"),
                run("skipped", "COMPLETED", "SKIPPED"),
                run("running", "IN_PROGRESS", "TIMED_OUT"),
                context("legacy", "ERROR"),
                context("legacy-pending", "PENDING")));

        assertEquals(Set.of("CI / failure", "CI / error", "CI / timeout", "CI / startup", "legacy"),
                BabysitMonitorRules.failingKeys(snapshot));
    }

    @Test
    void onlyNewlyFailingChecksAreReported() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "FAILURE"), run("b", "QUEUED", ""))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "FAILURE"),
                        run("b", "COMPLETED", "TIMED_OUT"))));

        assertEquals(List.of(List.of(BabysitEvent.checksFailed(List.of("CI / b")))), events);
    }

    @Test
    void aRerunThatFailsAgainIsReportedAgain() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "SUCCESS"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "FAILURE"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "IN_PROGRESS", ""))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "FAILURE"))));

        BabysitEvent failed = BabysitEvent.checksFailed(List.of("CI / a"));
        assertEquals(List.of(List.of(failed), List.of(), List.of(failed)), events);
    }

    /** A check run of Actions job {@code job} in run {@code runId}, as gh's details URL names it. */
    static Check runOf(String name, String conclusion, long runId, long job) {
        return new Check(Check.Kind.CHECK_RUN, name, "COMPLETED", conclusion, Optional.of("CI"),
                Optional.of("https://github.com/o/r/actions/runs/" + runId + "/job/" + job));
    }

    @Test
    void aFailureOnANewRunIsReportedEvenWithNoSampleInBetween() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(runOf("a", "FAILURE", 77, 1))),
                snapshot(Mergeable.MERGEABLE, List.of(runOf("a", "FAILURE", 78, 5))));

        assertEquals(List.of(List.of(BabysitEvent.checksFailed(List.of("CI / a")))), events,
                "failure -> failure on a NEW run id is news");
    }

    @Test
    void aFailureOnTheSameRunIsNoNews() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(runOf("a", "FAILURE", 77, 1))),
                snapshot(Mergeable.MERGEABLE, List.of(runOf("a", "FAILURE", 77, 1))));

        assertEquals(List.of(List.of()), events, "failure -> failure on the SAME run is no event");
    }

    @Test
    void aRerunAttemptThatFailedBetweenPollsIsReportedAgain() {
        // rerun-failed-jobs keeps the run id; the attempt's jobs are new, so the job URL changes.
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(runOf("a", "FAILURE", 77, 1))),
                snapshot(Mergeable.MERGEABLE, List.of(runOf("a", "FAILURE", 77, 9))));

        assertEquals(List.of(List.of(BabysitEvent.checksFailed(List.of("CI / a")))), events);
    }

    @Test
    void aLinklessFailureIsReportedAgainOnANewHead() {
        List<List<BabysitEvent>> events = walk(
                snapshot(PrLifecycle.OPEN, HEAD, Mergeable.MERGEABLE, List.of(context("legacy", "FAILURE"))),
                snapshot(PrLifecycle.OPEN, HEAD, Mergeable.MERGEABLE, List.of(context("legacy", "FAILURE"))),
                snapshot(PrLifecycle.OPEN, NEXT, Mergeable.MERGEABLE, List.of(context("legacy", "FAILURE"))));

        assertEquals(List.of(List.of(), List.of(BabysitEvent.checksFailed(List.of("legacy")))), events);
    }

    @Test
    void recoveryNeedsReportedFailuresAndEveryCheckConcluded() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "SUCCESS"), context("ctx", "SUCCESS"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "FAILURE"), context("ctx", "SUCCESS"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "IN_PROGRESS", ""), context("ctx", "SUCCESS"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "SUCCESS"), context("ctx", "PENDING"))),
                snapshot(Mergeable.MERGEABLE, List.of()),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "SUCCESS"), context("ctx", "SUCCESS"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "SUCCESS"), context("ctx", "SUCCESS"))));

        assertEquals(List.of(List.of(BabysitEvent.checksFailed(List.of("CI / a"))), List.of(), List.of(), List.of(),
                List.of(BabysitEvent.of(Kind.CHECKS_RECOVERED)), List.of()), events);
    }

    @Test
    void failuresPresentAtTheBaselineRecoverSilently() {
        List<List<BabysitEvent>> events = walk(
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "FAILURE"))),
                snapshot(Mergeable.MERGEABLE, List.of(run("a", "COMPLETED", "SUCCESS"))));

        assertEquals(List.of(List.of()), events);
    }

    @Test
    void aNewHeadAloneIsNoEventAndMergedOrClosedEnds() {
        Step step = BabysitMonitorRules.observe(Observation.first(snapshot(Mergeable.MERGEABLE, List.of())),
                snapshot(PrLifecycle.OPEN, NEXT, Mergeable.MERGEABLE, List.of()));

        assertEquals(List.of(), step.events());
        assertTrue(BabysitMonitorRules.ended(snapshot(PrLifecycle.MERGED, HEAD, Mergeable.UNKNOWN, List.of())));
        assertTrue(BabysitMonitorRules.ended(snapshot(PrLifecycle.CLOSED, HEAD, Mergeable.UNKNOWN, List.of())));
        assertFalse(BabysitMonitorRules.ended(snapshot(PrLifecycle.UNKNOWN, HEAD, Mergeable.UNKNOWN, List.of())));
        assertFalse(BabysitMonitorRules.ended(snapshot(Mergeable.CONFLICTING, List.of())));
    }

    @Test
    void undeliveredOppositesCancelAndFailuresMerge() {
        BabysitEvent appeared = BabysitEvent.of(Kind.CONFLICT_APPEARED);
        BabysitEvent cleared = BabysitEvent.of(Kind.CONFLICT_CLEARED);
        BabysitEvent recovered = BabysitEvent.of(Kind.CHECKS_RECOVERED);

        assertEquals(List.of(), BabysitMonitorRules.coalesce(List.of(appeared), List.of(cleared)));
        assertEquals(List.of(cleared), BabysitMonitorRules.coalesce(List.of(), List.of(cleared)));
        assertEquals(List.of(appeared), BabysitMonitorRules.coalesce(List.of(cleared), List.of(appeared)));
        assertEquals(List.of(appeared), BabysitMonitorRules.coalesce(List.of(appeared), List.of(appeared)));
        assertEquals(List.of(), BabysitMonitorRules.coalesce(
                List.of(BabysitEvent.checksFailed(List.of("a"))), List.of(recovered)));
        assertEquals(List.of(appeared, BabysitEvent.checksFailed(List.of("a", "b"))), BabysitMonitorRules.coalesce(
                List.of(BabysitEvent.checksFailed(List.of("a")), appeared),
                List.of(BabysitEvent.checksFailed(List.of("b", "a")))));
        assertEquals(List.of(BabysitEvent.checksFailed(List.of("c"))), BabysitMonitorRules.coalesce(
                List.of(recovered), List.of(BabysitEvent.checksFailed(List.of("c")))));
    }

    @Test
    void backoffStartsAtTheInitialDelayDoublesAndCaps() {
        long delay = 0;
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            delay = BabysitMonitorRules.nextBackoffDelayMs(delay, BabysitMonitorRules.BACKOFF_INITIAL_MS);
            delays.add(delay);
        }

        assertEquals(List.of(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), delays);
    }

    @Test
    void aCleanBaselineReportsWhatIsAlreadyWrongAndNothingForACleanPr() {
        Snapshot broken = snapshot(Mergeable.CONFLICTING, List.of(run("test", "COMPLETED", "FAILURE"),
                run("lint", "COMPLETED", "SUCCESS"), context("legacy", "ERROR")));

        Step step = BabysitMonitorRules.observe(Observation.clean(broken), broken);

        assertEquals(List.of(Kind.CONFLICT_APPEARED, Kind.CHECKS_FAILED), kinds(step));
        assertEquals(List.of("CI / test", "legacy"), step.events().get(1).checks());
        assertTrue(step.observation().conflictReported() && step.observation().failuresReported());

        for (Snapshot clean : List.of(snapshot(Mergeable.MERGEABLE, List.of(run("test", "COMPLETED", "SUCCESS"))),
                snapshot(Mergeable.UNKNOWN, List.of(run("test", "IN_PROGRESS", ""))),
                snapshot(Mergeable.MERGEABLE, List.of()))) {
            assertEquals(List.of(), BabysitMonitorRules.observe(Observation.clean(clean), clean).events(),
                    clean.toString());
        }
    }
}
