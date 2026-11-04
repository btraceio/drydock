package app.drydock.intellij.review.workbench.model;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The findings inbox (spec §7), pure: which tab a finding belongs to, the
 * cards a tab shows under a scope, the tab counts, and where the selection
 * goes after a decision. No new statuses: the tabs are a reading of
 * triage, status and the GitHub link.
 */
public final class InboxModel {

    /** The author a human's messages and findings carry. */
    static final String HUMAN = "You";

    private InboxModel() {
    }

    /** The inbox's tabs. */
    public enum Tab {
        TO_TRIAGE("To Triage"),
        OUTBOX("Outbox"),
        DONE("Done");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Which findings a tab lists: the whole review, or one file's. */
    public enum Scope { ALL, FILE }

    /**
     * One card. {@code challenged} marks a finding awaiting triage whose last
     * thread message is the reviewer's (a question the agent has not answered).
     */
    public record Card(ReviewAnnotation finding, boolean challenged) {
        public Card {
            Objects.requireNonNull(finding, "finding");
        }
    }

    /** What the inbox shows: the tab, its scope, its cards in review order, and every tab's count under the scope. */
    public record Snapshot(Tab tab, Scope scope, Optional<String> file, List<Card> cards, Map<Tab, Integer> counts) {
        public Snapshot {
            Objects.requireNonNull(tab, "tab");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(file, "file");
            cards = List.copyOf(cards);
            counts = Map.copyOf(counts);
        }

        public int count(Tab of) {
            return counts.getOrDefault(of, 0);
        }

        /** The empty inbox, before any finding is known. */
        public static Snapshot empty(Tab tab, Scope scope) {
            Map<Tab, Integer> zero = new EnumMap<>(Tab.class);
            for (Tab each : Tab.values()) {
                zero.put(each, 0);
            }
            return new Snapshot(tab, scope, Optional.empty(), List.of(), zero);
        }
    }

    /**
     * The tab a finding belongs to. Done first: dismissed, past OPEN (sent,
     * addressed, resolved, fixed) or posted to GitHub. Otherwise a proposed
     * finding awaits triage and a confirmed one waits in the outbox.
     */
    public static Tab tabOf(ReviewAnnotation finding) {
        if (finding.triage() == Triage.DISMISSED || finding.status() != AnnotationStatus.OPEN
                || finding.github().isPresent()) {
            return Tab.DONE;
        }
        return finding.triage() == Triage.PROPOSED ? Tab.TO_TRIAGE : Tab.OUTBOX;
    }

    /** Whether the reviewer has asked the agent something it has not answered yet. */
    static boolean challenged(ReviewAnnotation finding) {
        List<ReviewAnnotation.Message> thread = finding.thread();
        return tabOf(finding) == Tab.TO_TRIAGE && thread.size() > 1
                && HUMAN.equals(thread.get(thread.size() - 1).author());
    }

    /**
     * The inbox under {@code scope}: {@link Scope#FILE} lists the findings of
     * {@code file} only, and is the whole review while no file is selected.
     * Cards are in review order (file path, then start line, then id).
     */
    public static Snapshot snapshot(List<ReviewAnnotation> findings, Tab tab, Scope scope, Optional<String> file) {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(file, "file");
        boolean byFile = scope == Scope.FILE && file.isPresent();
        Map<Tab, Integer> counts = new EnumMap<>(Tab.class);
        for (Tab each : Tab.values()) {
            counts.put(each, 0);
        }
        List<Card> cards = new ArrayList<>();
        for (ReviewAnnotation finding : sorted(findings)) {
            if (byFile && !finding.file().equals(file.get())) {
                continue;
            }
            Tab of = tabOf(finding);
            counts.merge(of, 1, Integer::sum);
            if (of == tab) {
                cards.add(new Card(finding, challenged(finding)));
            }
        }
        return new Snapshot(tab, scope, byFile ? file : Optional.empty(), cards, counts);
    }

    /**
     * Where the selection goes after {@code decidedId} left a tab: the card
     * that followed it in {@code before} and is still in {@code after},
     * else the nearest one before it, else none.
     */
    public static Optional<String> nextAfter(List<String> before, String decidedId, List<String> after) {
        int at = before.indexOf(decidedId);
        if (at < 0) {
            return after.isEmpty() ? Optional.empty() : Optional.of(after.get(0));
        }
        for (int i = at + 1; i < before.size(); i++) {
            if (after.contains(before.get(i))) {
                return Optional.of(before.get(i));
            }
        }
        for (int i = at - 1; i >= 0; i--) {
            if (after.contains(before.get(i))) {
                return Optional.of(before.get(i));
            }
        }
        return Optional.empty();
    }

    private static List<ReviewAnnotation> sorted(List<ReviewAnnotation> findings) {
        return findings.stream()
                .sorted(Comparator.comparing(ReviewAnnotation::file)
                        .thenComparingInt(finding -> line(finding.startKey()))
                        .thenComparing(ReviewAnnotation::id))
                .toList();
    }

    /** A line key's number for ordering; a malformed key sorts first. */
    private static int line(String key) {
        if (key == null || key.length() < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(key.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
