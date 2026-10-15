package app.drydock.review;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A section's decision, derived from its hunks' (spec §9.1).
 *
 * <p>The merge is deliberately asymmetric, and the asymmetry is inherited
 * rather than invented: it is the rule {@code AnnotationStore}'s legacy
 * verdict migration was written around, promoted from a one-off carry to the
 * live derivation now that sections overlap and cannot own a verdict of
 * their own.</p>
 *
 * <ul>
 *   <li>Any {@code CHANGES} makes the section {@code CHANGES}. "Something in
 *       here needs work" stays true of a section however it is drawn.</li>
 *   <li>An approval needs EVERY hunk settled. Approving a section is a claim
 *       that the human read all of it, so one unread hunk leaves it
 *       unsettled. Silently approving code nobody looked at is the one
 *       outcome this must never produce.</li>
 * </ul>
 */
public final class VerdictMerge {

    private VerdictMerge() {
    }

    /**
     * The section's decision, or empty when its hunks do not support one.
     * {@code hunkVerdicts} carries one entry per hunk in the section, empty
     * where that hunk is unsettled.
     */
    public static Optional<ReviewVerdict.Decision> derive(
            List<Optional<ReviewVerdict>> hunkVerdicts) {
        Objects.requireNonNull(hunkVerdicts, "hunkVerdicts");
        if (hunkVerdicts.isEmpty()) {
            return Optional.empty();
        }
        boolean anyUnsettled = false;
        boolean anyHumanApproval = false;
        for (Optional<ReviewVerdict> verdict : hunkVerdicts) {
            if (verdict.isEmpty()) {
                anyUnsettled = true;
                continue;
            }
            switch (verdict.get().decision()) {
                // Checked before the unsettled test: a changes request is
                // already true of the section, and waiting for the rest to be
                // read before saying so would hide it exactly when it matters.
                case CHANGES -> {
                    return Optional.of(ReviewVerdict.Decision.CHANGES);
                }
                case APPROVED -> anyHumanApproval = true;
                case AUTO_APPROVED -> { }
            }
        }
        if (anyUnsettled) {
            return Optional.empty();
        }
        return Optional.of(anyHumanApproval
                ? ReviewVerdict.Decision.APPROVED
                : ReviewVerdict.Decision.AUTO_APPROVED);
    }
}
