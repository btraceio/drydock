package app.drydock.babysit;

import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Mergeable;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The babysitting monitor's decisions, pure: what a new PR read means against
 * the previous one, how undelivered events coalesce, and how a failing
 * engagement backs off.
 *
 * <p><b>Failing checks.</b> Wider than {@link Check#failing()} (the badge
 * signal, FAILURE/ERROR only): a completed check run concluded {@code
 * TIMED_OUT} or {@code STARTUP_FAILURE} fails too. Those are what GitHub's
 * UI shows red and exactly the infrastructure and flake cases the babysitter
 * judges for a rerun. {@code CANCELLED}, {@code ACTION_REQUIRED}, {@code
 * NEUTRAL} and {@code SKIPPED} are not failures. A check is named by
 * {@code "<workflow> / <name>"} (or its name when it has no workflow), since
 * names repeat across workflows; a failure is identified by that name AND its
 * execution ({@link #failureKey}): the check's details URL -- for an Actions
 * job {@code .../actions/runs/<runId>/job/<jobId>}, which a new run and a
 * rerun attempt both change -- else the head commit. So a failure that is
 * still failing on the same execution is no news, but one that failed again
 * on a new run, a rerun or a new push is, even when no poll ever saw it
 * in progress.</p>
 *
 * <p><b>Edges, not levels.</b> A babysitter is woken by what changes. The
 * first read of an engagement the monitor restored at startup is a silent
 * baseline ({@link Observation#first}): what it shows was there before the
 * restart and was either reported then or predates the engagement's
 * memory. The first read of an engagement that STARTED while the monitor
 * ran is diffed against a synthetic clean baseline ({@link
 * Observation#clean}) instead, so a PR that is already conflicting or red
 * when babysitting starts wakes the babysitter at once. A conflict is reported when {@code mergeable} turns CONFLICTING
 * from anything else, and its clearing only when the babysitter was told of
 * it; {@code UNKNOWN} (GitHub recomputing after a push) is neither and
 * changes nothing. Checks that fail now and did not at the previous read are
 * reported; their recovery only when reported failures are gone and every
 * check has concluded (a rerun in progress is not a recovery).</p>
 */
public final class BabysitMonitorRules {

    /** First delay after a failed read or a failed git check (StackPrService's shape). */
    public static final long BACKOFF_INITIAL_MS = 30_000;

    /** The delay never grows past ten minutes. */
    public static final long BACKOFF_MAX_MS = 600_000;

    private BabysitMonitorRules() {
    }

    /**
     * What the monitor remembers of an engagement between reads: the last
     * snapshot, the last {@code mergeable} that was not UNKNOWN, and whether
     * the babysitter has been told of a conflict or of failing checks that
     * have not cleared since.
     */
    public record Observation(Snapshot snapshot, Mergeable lastKnownMergeable, boolean conflictReported,
                              boolean failuresReported) {
        public Observation {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(lastKnownMergeable, "lastKnownMergeable");
        }

        /** The silent baseline: nothing reported yet, whatever the PR looks like. */
        public static Observation first(Snapshot snapshot) {
            return new Observation(snapshot, snapshot.mergeable(), false, false);
        }

        /**
         * The synthetic clean baseline for a fresh engagement: {@code
         * snapshot}'s PR as if it were MERGEABLE with no checks and nothing
         * reported, at the same head. Diffing {@code snapshot} against it
         * reports an existing conflict and every failing check; a clean PR
         * yields nothing (no recovery is reported, since no failure was).
         */
        public static Observation clean(Snapshot snapshot) {
            Snapshot clean = new Snapshot(snapshot.prNumber(), snapshot.lifecycle(), snapshot.draft(),
                    snapshot.baseRefName(), snapshot.headRefName(), snapshot.headRefOid(), Mergeable.MERGEABLE,
                    snapshot.mergeStateStatus(), List.of());
            return new Observation(clean, Mergeable.MERGEABLE, false, false);
        }
    }

    /** One read applied to an observation: the new observation and the events. */
    public record Step(Observation observation, List<BabysitEvent> events) {
        public Step {
            Objects.requireNonNull(observation, "observation");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    /** Whether the PR is merged or closed: the engagement ends. */
    public static boolean ended(Snapshot snapshot) {
        return snapshot.lifecycle() == PrLifecycle.MERGED || snapshot.lifecycle() == PrLifecycle.CLOSED;
    }

    /** Whether {@code check} is failing in the monitor's widened sense (see the class doc). */
    public static boolean failing(Check check) {
        if (check.failing()) {
            return true;
        }
        return check.kind() == Check.Kind.CHECK_RUN && "COMPLETED".equals(check.status())
                && ("TIMED_OUT".equals(check.conclusion()) || "STARTUP_FAILURE".equals(check.conclusion()));
    }

    /** Whether {@code check} has a final result: a completed check run, or a status context not pending. */
    public static boolean concluded(Check check) {
        return switch (check.kind()) {
            case CHECK_RUN -> "COMPLETED".equals(check.status());
            case STATUS_CONTEXT -> !check.status().isEmpty()
                    && !"PENDING".equals(check.status()) && !"EXPECTED".equals(check.status());
        };
    }

    /** The identity a check is diffed and reported by. */
    public static String checkKey(Check check) {
        return check.workflowName().map(workflow -> workflow + " / " + check.name()).orElse(check.name());
    }

    /**
     * The identity one failure is diffed by: the check's {@link #checkKey}
     * and its execution -- the details URL (an Actions job URL carries the
     * run id and the job id, new for every run and every rerun attempt),
     * else {@code headSha}, for a check that links nowhere.
     */
    public static String failureKey(Check check, String headSha) {
        return checkKey(check) + " @ " + check.detailsUrl().orElse(headSha);
    }

    /** The failing checks of {@code snapshot}, in rollup order, by {@link #checkKey}. */
    public static Set<String> failingKeys(Snapshot snapshot) {
        Set<String> keys = new LinkedHashSet<>();
        for (Check check : snapshot.checks()) {
            if (failing(check)) {
                keys.add(checkKey(check));
            }
        }
        return keys;
    }

    /** The failures of {@code snapshot}, by {@link #failureKey}. */
    private static Set<String> failureKeys(Snapshot snapshot) {
        Set<String> keys = new LinkedHashSet<>();
        for (Check check : snapshot.checks()) {
            if (failing(check)) {
                keys.add(failureKey(check, snapshot.headRefOid()));
            }
        }
        return keys;
    }

    /** Applies the read {@code next} to {@code previous}. */
    public static Step observe(Observation previous, Snapshot next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        List<BabysitEvent> events = new ArrayList<>(2);

        boolean conflictReported = previous.conflictReported();
        if (next.mergeable() == Mergeable.CONFLICTING && previous.lastKnownMergeable() != Mergeable.CONFLICTING) {
            events.add(BabysitEvent.of(BabysitEvent.Kind.CONFLICT_APPEARED));
            conflictReported = true;
        } else if (next.mergeable() == Mergeable.MERGEABLE) {
            if (conflictReported && previous.lastKnownMergeable() == Mergeable.CONFLICTING) {
                events.add(BabysitEvent.of(BabysitEvent.Kind.CONFLICT_CLEARED));
            }
            conflictReported = false;
        }
        Mergeable lastKnown = next.mergeable() == Mergeable.UNKNOWN ? previous.lastKnownMergeable() : next.mergeable();

        boolean failuresReported = previous.failuresReported();
        Set<String> failingNow = failingKeys(next);
        Set<String> before = failureKeys(previous.snapshot());
        Set<String> fresh = new LinkedHashSet<>();
        for (Check check : next.checks()) {
            if (failing(check) && !before.contains(failureKey(check, next.headRefOid()))) {
                fresh.add(checkKey(check));
            }
        }
        if (!fresh.isEmpty()) {
            events.add(BabysitEvent.checksFailed(List.copyOf(fresh)));
            failuresReported = true;
        } else if (failingNow.isEmpty() && !next.checks().isEmpty()
                && next.checks().stream().allMatch(BabysitMonitorRules::concluded)) {
            if (failuresReported) {
                events.add(BabysitEvent.of(BabysitEvent.Kind.CHECKS_RECOVERED));
            }
            failuresReported = false;
        }

        return new Step(new Observation(next, lastKnown, conflictReported, failuresReported), events);
    }

    /**
     * Folds new {@code events} into the {@code pending} ones not delivered
     * yet: at most one event per axis (conflict, checks) survives. A clearing
     * cancels an undelivered appearance (the babysitter never needs to hear
     * of either) and a recovery an undelivered failure; failures merge by
     * check; a new appearance or failure replaces an undelivered clearing or
     * recovery. The result is in {@link BabysitEvent.Kind} order.
     */
    public static List<BabysitEvent> coalesce(List<BabysitEvent> pending, List<BabysitEvent> events) {
        List<BabysitEvent> result = new ArrayList<>(pending);
        for (BabysitEvent event : events) {
            switch (event.kind()) {
                case CONFLICT_APPEARED -> {
                    removeKind(result, BabysitEvent.Kind.CONFLICT_CLEARED);
                    addOnce(result, event);
                }
                case CONFLICT_CLEARED -> {
                    if (!removeKind(result, BabysitEvent.Kind.CONFLICT_APPEARED)) {
                        addOnce(result, event);
                    }
                }
                case CHECKS_FAILED -> {
                    removeKind(result, BabysitEvent.Kind.CHECKS_RECOVERED);
                    Set<String> checks = new LinkedHashSet<>();
                    for (BabysitEvent existing : result) {
                        if (existing.kind() == BabysitEvent.Kind.CHECKS_FAILED) {
                            checks.addAll(existing.checks());
                        }
                    }
                    checks.addAll(event.checks());
                    removeKind(result, BabysitEvent.Kind.CHECKS_FAILED);
                    result.add(BabysitEvent.checksFailed(List.copyOf(checks)));
                }
                case CHECKS_RECOVERED -> {
                    if (!removeKind(result, BabysitEvent.Kind.CHECKS_FAILED)) {
                        addOnce(result, event);
                    }
                }
            }
        }
        result.sort(Comparator.comparing(BabysitEvent::kind));
        return List.copyOf(result);
    }

    private static boolean removeKind(List<BabysitEvent> events, BabysitEvent.Kind kind) {
        return events.removeIf(event -> event.kind() == kind);
    }

    private static void addOnce(List<BabysitEvent> events, BabysitEvent event) {
        if (events.stream().noneMatch(existing -> existing.kind() == event.kind())) {
            events.add(event);
        }
    }

    /** The next backoff delay after {@code previousDelayMs} (0 = none yet): initial, then doubling, capped. */
    public static long nextBackoffDelayMs(long previousDelayMs, long initialMs) {
        if (previousDelayMs <= 0) {
            return initialMs;
        }
        return Math.min(previousDelayMs * 2, BACKOFF_MAX_MS);
    }
}
