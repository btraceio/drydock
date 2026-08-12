package app.drydock.handoff;

import app.drydock.domain.HandoffBrief;

import java.util.List;
import java.util.Optional;

/**
 * Builds the prompt a forked session starts with: the outgoing session's
 * brief, plus the facts drydock derived itself, with the two kept visibly
 * apart.
 *
 * <p>That separation is a mitigation, not a formatting choice. The brief is
 * authored by an agent that reads untrusted diffs, and it lands in a fresh
 * model's first prompt with no history to contradict it, at the moment it is
 * deciding what to do first. Marking it as testimony -- and the git facts as
 * checkable -- is what lets the successor weigh it instead of simply believing
 * it.</p>
 */
public final class HandoffSeed {

    private HandoffSeed() {
    }

    public static String compose(Optional<HandoffBrief> brief, ForkFacts facts) {
        StringBuilder seed = new StringBuilder();
        seed.append("You are continuing work another agent started. The working tree, branch and ")
                .append("commits are real and already in place; only the conversation did not carry over.\n\n");

        brief.ifPresentOrElse(
                b -> appendBrief(seed, b),
                () -> seed.append("## Handoff\n\nNo handoff brief was recorded before this fork, so nothing ")
                        .append("is known about why the work was done this way. Read the diff before ")
                        .append("changing direction.\n\n"));

        appendFacts(seed, facts);

        seed.append("Start by confirming the state of the tree against what you were told above, ")
                .append("then take the next step.\n");
        return seed.toString();
    }

    private static void appendBrief(StringBuilder seed, HandoffBrief brief) {
        String provenance = brief.author() == HandoffBrief.Author.HUMAN
                ? "written by the human who is watching this work"
                : "reported by the previous session -- testimony, not verified fact";
        seed.append("## Handoff (").append(provenance).append(")\n\n");
        seed.append("**Goal:** ").append(brief.goal()).append("\n\n");
        appendSlot(seed, "Approach", brief.approach());
        appendSlot(seed, "Decisions", brief.decisions());
        appendSlot(seed, "Ruled out", brief.ruledOut());
        appendSlot(seed, "Corrections from the human", brief.corrections());
        seed.append("**Next step:** ").append(brief.nextStep()).append("\n\n");
    }

    private static void appendSlot(StringBuilder seed, String heading, Optional<String> value) {
        value.ifPresent(v -> seed.append("**").append(heading).append(":** ").append(v).append("\n\n"));
    }

    private static void appendFacts(StringBuilder seed, ForkFacts facts) {
        seed.append("## State (derived by drydock, checkable)\n\n");
        seed.append("**Branch:** ").append(facts.branch())
                .append(" (forked from ").append(facts.baseBranch()).append(")\n\n");
        appendList(seed, "Commits", facts.commitSubjects());
        appendList(seed, "Uncommitted changes", facts.changedFiles());
        appendList(seed, "Open review intents", facts.openIntents());
    }

    /** Omits an empty section rather than printing a heading with nothing under it. */
    private static void appendList(StringBuilder seed, String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        seed.append("**").append(heading).append(":**\n");
        for (String item : items) {
            seed.append("- ").append(item).append('\n');
        }
        seed.append('\n');
    }
}
