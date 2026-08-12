package app.drydock.handoff;

import app.drydock.domain.HandoffBrief;

import java.util.Optional;

/**
 * How far a session's work has moved since its handoff brief was written.
 *
 * <p>Measured in work rather than in clock time on purpose. "Brief written 9
 * commits and 40 changed files ago" tells the human whether it still describes
 * the tree; "brief is two hours old" does not. A session idle for a day has a
 * brief that is still perfectly accurate, so elapsed time never raises the
 * warning -- only commits and changed files since {@link
 * HandoffBrief#writtenAtCommit()} can.</p>
 *
 * <p>Pure: the git counting is the caller's job (see {@code
 * SessionForkService}), so this is testable without a repository.</p>
 */
public record HandoffStaleness(int commitsSince, int changedFiles, boolean briefMissing) {

    /**
     * When {@code brief} is empty the counts are dropped rather than carried:
     * work done "since" a brief that was never written is not a measurement,
     * and a caller that displayed it would be stating a comparison it never
     * made.
     */
    public static HandoffStaleness of(Optional<HandoffBrief> brief, int commitsSince, int changedFiles) {
        if (brief.isEmpty()) {
            return new HandoffStaleness(0, 0, true);
        }
        return new HandoffStaleness(commitsSince, changedFiles, false);
    }

    public boolean shouldWarn() {
        return briefMissing || commitsSince > 0 || changedFiles > 0;
    }

    /** One line for the banner; never blank, so the caller never has to guess at a fallback. */
    public String describe() {
        if (briefMissing) {
            return "No handoff brief has been written for this session";
        }
        if (!shouldWarn()) {
            return "Brief is current";
        }
        String commits = commitsSince + (commitsSince == 1 ? " commit" : " commits");
        String files = changedFiles + (changedFiles == 1 ? " changed file" : " changed files");
        if (commitsSince == 0) {
            return "Brief written " + files + " ago";
        }
        if (changedFiles == 0) {
            return "Brief written " + commits + " ago";
        }
        return "Brief written " + commits + " and " + files + " ago";
    }
}
