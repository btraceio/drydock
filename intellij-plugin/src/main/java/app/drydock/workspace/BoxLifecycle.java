package app.drydock.workspace;

import java.util.Objects;

/**
 * The pure lifecycle decision for launching against one workspace box
 * (AC-16, INV-13). {@link #decide} folds exactly four inputs — a box
 * reachability probe, the workspaces CLI's status answer for the box's
 * name, whether the repo already maps to a box, and whether that box is
 * already bootstrapped — into one {@link Outcome}. The function spawns
 * nothing, consults no process, and logs nothing: every input is produced
 * and warned about by its owner ({@link BoxShell} for the probe,
 * {@link WorkspacesCli} for the table), so this class carries only the
 * decision table.
 *
 * <p>The table is exhaustive, and its ordering principle is that
 * <em>ambiguity must never imply destruction</em>
 * ({@code workspace-persistent-sessions/find-restart-wake-refuted}): the
 * only answers that can move toward {@code workspaces create} are a box
 * the CLI affirmatively lists as absent while no mapping exists, and a
 * user-confirmed {@link Outcome#OFFER_RECREATE}. Every ambiguous input —
 * an ssh failure together with a listed non-stopped status, a CLI that
 * failed, timed out, printed an unparseable table, or is not installed —
 * lands in {@link Outcome#TRANSIENT_ERROR}, because the investigation
 * showed a stopped box is not woken by any CLI subcommand (restart left
 * one at status 8 and unreachable for 18+ minutes), and a misread status
 * could otherwise destroy a box the user merely lost sight of.</p>
 *
 * <p>The decision, in full:</p>
 * <ul>
 *   <li>ssh probe ok → {@code REUSE} if the box is bootstrapped, else
 *   {@code BOOTSTRAP_THEN_REUSE}. The CLI answer is not consulted: the box
 *   answers ssh, so its listed status is irrelevant.</li>
 *   <li>ssh fail + the CLI lists the box stopped (status 8) →
 *   {@code OFFER_RECREATE(STOPPED)}. Stopped boxes stay stopped; the
 *   conversations are unreachable, and the only path forward is a new
 *   generation — offered, never automatic.</li>
 *   <li>ssh fail + the CLI lists the box absent + no mapping exists →
 *   {@code CREATE} under the base name (a first box for this repo; the
 *   base name is the caller's, {@link WorkspaceBoxNames#defaultName} or a
 *   user override).</li>
 *   <li>ssh fail + the CLI lists the box absent + a mapping exists →
 *   {@code OFFER_RECREATE(GONE)} (D2): the mapped box was deleted out from
 *   under the plugin, and a same-name re-create would bind old rows to a
 *   fresh box where their conversations do not exist.</li>
 *   <li>ssh fail + the CLI lists the box with any other status →
 *   {@code TRANSIENT_ERROR} (ASM-8: only status 8 is interpreted; any
 *   other listed code means a state this design does not presume to
 *   read).</li>
 *   <li>ssh fail + the CLI failed, timed out, printed an unparseable
 *   table, or is not installed → {@code TRANSIENT_ERROR}. A missing CLI
 *   answer is never a box gone, and never a license to create.</li>
 * </ul>
 */
public final class BoxLifecycle {

    private BoxLifecycle() {
    }

    /**
     * The CLI-side answer {@link #decide} consumes: either a parsed table
     * reduced to the relevant box's {@link WorkspacesCli.BoxStatus}, or
     * the typed reason the CLI could not produce a trustworthy table. The
     * failure variants stay distinct ({@link Unavailable} carries the
     * exact {@link WorkspacesCli.ListResult}) so callers and tests can
     * tell a CLI failure from a missing CLI — both of which decide treats
     * as ambiguous, but neither of which is a conflated "empty" answer.
     */
    public sealed interface CliStatus {

        /** The CLI listed the table; {@code status} is the box's classification. */
        record Listed(WorkspacesCli.BoxStatus status) implements CliStatus {
            public Listed {
                Objects.requireNonNull(status, "status");
            }
        }

        /**
         * The CLI could not answer: failed, timed out, refused as
         * over-large, unparseable, or not installed. {@code failure} is
         * the exact outcome {@link WorkspacesCli} already logged.
         */
        record Unavailable(WorkspacesCli.ListResult failure) implements CliStatus {
            public Unavailable {
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    /**
     * Reduces a raw {@link WorkspacesCli#list()} outcome to the
     * {@link CliStatus} {@link #decide} consumes: a parsed table is
     * classified for {@code boxName} ({@code 8} → STOPPED, any other
     * listed status → OTHER, unlisted → ABSENT); every typed failure is
     * carried through as {@link CliStatus.Unavailable}. Building the
     * reduction here keeps the whole decision table — including "a CLI
     * failure is ambiguous" — in one place.
     */
    public static CliStatus cliStatus(WorkspacesCli.ListResult result, String boxName) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(boxName, "boxName");
        return switch (result) {
            case WorkspacesCli.Listed listed -> new CliStatus.Listed(listed.statusOf(boxName));
            default -> new CliStatus.Unavailable(result);
        };
    }

    /** Why a re-create is on offer: the mapped box stopped, or it is gone entirely. */
    public enum RecreateReason { STOPPED, GONE }

    /**
     * What a launch should do next. The table is total: every combination
     * of inputs lands in exactly one of these, and the destructive ones
     * ({@link OfferRecreate}) always require a user confirmation upstream.
     */
    public sealed interface Outcome {

        /** The box is reachable and bootstrapped: attach and go. */
        record Reuse() implements Outcome {
        }

        /** The box is reachable but not bootstrapped: run the bootstrap, then reuse. */
        record BootstrapThenReuse() implements Outcome {
        }

        /**
         * No mapping exists and the CLI affirmatively lists no such box:
         * create the first box under the base name. This is the only
         * outcome that leads straight to {@code workspaces create}
         * without a confirmation.
         */
        record Create() implements Outcome {
        }

        /**
         * The mapped box is stopped or gone: offer a re-create under a new
         * generation name ({@link WorkspaceBoxNames#nextGeneration}); the
         * old box is left in place and its conversations are not
         * recovered by the re-create. Requires a destructive confirmation
         * upstream — never automatic.
         */
        record OfferRecreate(RecreateReason reason) implements Outcome {
            public OfferRecreate {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /**
         * The inputs do not support any decision: an ssh failure the CLI
         * could not explain (non-stopped status), or a CLI that failed,
         * timed out, was unparseable, or is missing. The launch refuses
         * and the user retries; nothing is created or offered.
         */
        record TransientError() implements Outcome {
        }
    }

    /**
     * The decision table (AC-16, INV-13). {@code sshProbe} is the
     * {@link BoxShell#probe} result — {@link BoxShell.BoxResult.Ok} is the
     * only reachable answer; every failure class (unreachable, timeout,
     * remote failure, ssh not installed) counts as an ssh failure.
     * {@code cliStatus} is the CLI answer reduced by {@link #cliStatus};
     * it is consulted only when the probe failed. {@code mappingKnown} is
     * whether the repo already maps to a box (a recorded {@code .box}
     * name — an override or a previous generation); it distinguishes a
     * first box ({@code CREATE}) from a gone mapped box
     * ({@code OFFER_RECREATE(GONE)}). {@code bootstrapped} is whether the
     * box has already been provisioned with the proxy and checkout; it is
     * consulted only when the probe succeeded.
     */
    public static Outcome decide(BoxShell.BoxResult sshProbe, CliStatus cliStatus,
                                 boolean mappingKnown, boolean bootstrapped) {
        Objects.requireNonNull(sshProbe, "sshProbe");
        Objects.requireNonNull(cliStatus, "cliStatus");
        if (sshProbe instanceof BoxShell.BoxResult.Ok) {
            return bootstrapped ? new Outcome.Reuse() : new Outcome.BootstrapThenReuse();
        }
        return switch (cliStatus) {
            case CliStatus.Listed(WorkspacesCli.BoxStatus status) when status == WorkspacesCli.BoxStatus.STOPPED ->
                    new Outcome.OfferRecreate(RecreateReason.STOPPED);
            case CliStatus.Listed(WorkspacesCli.BoxStatus status) when status == WorkspacesCli.BoxStatus.ABSENT ->
                    mappingKnown
                            ? new Outcome.OfferRecreate(RecreateReason.GONE)
                            : new Outcome.Create();
            default -> new Outcome.TransientError();
        };
    }
}
