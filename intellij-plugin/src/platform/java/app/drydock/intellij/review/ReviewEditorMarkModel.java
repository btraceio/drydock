package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * The source-editor gutter marks for one review scope (AC5): an immutable
 * map from diff path to the grouped open findings on that file's
 * zero-based <em>source</em> lines — the value {@code
 * ReviewEditorHighlighters} (T11) paints into every open editor and {@code
 * ReviewEditorMarks} (T12) computes at project warm-up, before Review is
 * ever opened.
 *
 * <p>Pure on purpose: no editor, no Swing, no store, no I/O — just the
 * scope's {@link UnifiedDiff} and the scope's findings. Every position
 * comes from {@link ReviewFindingPlacement#sourceEditorLine}, the one
 * source-line policy (T3): only a valid <em>and present</em> new-side key
 * {@code n<N>} addresses a working-file line. Old-side-only keys address
 * lines the working file no longer contains, drifted new-side keys were
 * given against another base, and malformed keys address nothing — none of
 * them is guessed (AC5: "old-side-only anchors and drifted anchors get no
 * guessed source line"). There is deliberately no fallback: a finding with
 * no addressable line simply gets no gutter mark and stays visible in the
 * Review diff instead.</p>
 *
 * <p>Who is marked: the same population the Review tree shows as open
 * children — unresolved ({@code OPEN}, {@code SENT}, {@code ADDRESSED})
 * findings with triage {@code PROPOSED} or {@code CONFIRMED}. {@code
 * DISMISSED} and resolved ({@code RESOLVED}/{@code FIXED}) findings are
 * excluded (the tree hides them behind its footer too). Which files: only
 * paths the current diff actually contains — a finding against any other
 * path belongs to no editor this scope can vouch for.</p>
 *
 * <p>Grouping is deterministic: findings that share a source line collapse
 * into one {@link Group} whose severity is the worst of the findings in
 * force ({@link Severity}'s own order, most severe first), whose count and
 * id list are the group's size, whose tooltip lists every finding in the
 * store's list order (the order the Review tree's children follow), and
 * whose navigation target is that order's first id — the finding the tree
 * itself lists first. Line iteration is ascending and path iteration is
 * sorted, so no consumer can observe an order the inputs did not
 * determine.</p>
 */
public final class ReviewEditorMarkModel {

    private final Map<String, FileMarks> byPath;

    private ReviewEditorMarkModel(Map<String, FileMarks> byPath) {
        this.byPath = byPath;
    }

    /**
     * Computes the marks for one scope's diff and findings. {@code findings}
     * is the one scope's list, as the store's {@code forScope} returns it
     * (add order); findings of other scopes or other paths are not this
     * model's business.
     */
    public static ReviewEditorMarkModel of(UnifiedDiff diff, List<ReviewAnnotation> findings) {
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(findings, "findings");

        // The current row keys per path, as the Review tree groups them
        // (ReviewTabModel.rowFor). Merged across hunks — and, defensively,
        // across duplicate FileDiff entries for one path, which DiffService
        // does not produce but nothing here needs to care about.
        Map<String, Set<String>> rowKeysByPath = new HashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            Set<String> rowKeys = rowKeysByPath.computeIfAbsent(file.path(), path -> new LinkedHashSet<>());
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    rowKeys.add(line.lineKey());
                }
            }
        }

        Map<String, Map<Integer, List<ReviewAnnotation>>> grouped = new TreeMap<>();
        for (ReviewAnnotation finding : findings) {
            if (finding.resolved() || finding.triage() == Triage.DISMISSED) {
                continue;
            }
            Set<String> rowKeys = rowKeysByPath.get(finding.file());
            if (rowKeys == null) {
                // Not a file of this diff: no source file to vouch for.
                continue;
            }
            OptionalInt line = ReviewFindingPlacement.sourceEditorLine(finding.startKey(), rowKeys);
            if (line.isEmpty()) {
                // Old-side-only, drifted or malformed key — never guessed.
                continue;
            }
            grouped.computeIfAbsent(finding.file(), path -> new TreeMap<>())
                    .computeIfAbsent(line.getAsInt(), lineIndex -> new ArrayList<>())
                    .add(finding);
        }

        Map<String, FileMarks> byPath = new TreeMap<>();
        for (Map.Entry<String, Map<Integer, List<ReviewAnnotation>>> entry : grouped.entrySet()) {
            Map<Integer, Group> groups = new TreeMap<>();
            entry.getValue().forEach((line, findingsOnLine) ->
                    groups.put(line, groupAt(line, findingsOnLine)));
            byPath.put(entry.getKey(), new FileMarks(entry.getKey(), groups));
        }
        return new ReviewEditorMarkModel(Collections.unmodifiableMap(byPath));
    }

    private static Group groupAt(int line, List<ReviewAnnotation> findingsOnLine) {
        List<String> ids = findingsOnLine.stream().map(ReviewAnnotation::id).toList();
        // Severity's own declaration order is most-to-least severe, so the
        // minimum is the worst severity in force among the group.
        Severity worst = findingsOnLine.stream()
                .map(ReviewAnnotation::effectiveSeverity)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        return new Group(line, worst, ids.size(), tooltip(findingsOnLine), ids);
    }

    private static String tooltip(List<ReviewAnnotation> findingsOnLine) {
        if (findingsOnLine.size() == 1) {
            return findingsOnLine.get(0).displayTitle();
        }
        StringBuilder text = new StringBuilder(findingsOnLine.size() + " findings on this line:");
        for (ReviewAnnotation finding : findingsOnLine) {
            text.append("\n• ")
                    .append(finding.effectiveSeverity().wireName())
                    .append(": ")
                    .append(finding.displayTitle());
        }
        return text.toString();
    }

    /** Whether any file carries a mark at all. */
    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    /** The marks of one diff path, or empty when that file has none. */
    public Optional<FileMarks> forPath(String path) {
        return Optional.ofNullable(byPath.get(Objects.requireNonNull(path, "path")));
    }

    /**
     * Every marked path, in sorted path order. The returned map (and its
     * nested values) is unmodifiable: a snapshot is a value, not a shared
     * mutable.
     */
    public Map<String, FileMarks> byPath() {
        return byPath;
    }

    // ---- the model's values -------------------------------------------------

    /**
     * One marked file: its diff path and the groups by zero-based source
     * line. A file with no addressable findings never appears — an entry
     * claims marks.
     */
    public record FileMarks(String path, Map<Integer, Group> byLine) {

        public FileMarks {
            Objects.requireNonNull(path, "path");
            if (path.isBlank()) {
                throw new IllegalArgumentException("a marked file is named by its path; it may not be blank");
            }
            Objects.requireNonNull(byLine, "byLine");
            if (byLine.isEmpty()) {
                throw new IllegalArgumentException("a marked file has at least one mark; an empty one claims marks it does not have");
            }
            // Defensive copy in ascending line order, so the public map is
            // both unmodifiable and deterministic.
            Map<Integer, Group> copy = new TreeMap<>();
            for (Map.Entry<Integer, Group> entry : byLine.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "byLine key");
                if (entry.getKey() != entry.getValue().line()) {
                    throw new IllegalArgumentException("a group is keyed by its own line: key "
                            + entry.getKey() + " does not match group line " + entry.getValue().line());
                }
                copy.put(entry.getKey(), entry.getValue());
            }
            byLine = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Every finding addressable at one zero-based source line, collapsed
     * into one gutter mark: the worst severity in force, the finding count,
     * the tooltip listing all of them, and every finding id in the store's
     * list order — the order the Review tree's own children follow — for
     * navigation.
     */
    public record Group(int line, Severity severity, int count, String tooltip, List<String> findingIds) {

        public Group {
            if (line < 0) {
                throw new IllegalArgumentException("source lines are zero-based and never negative: " + line);
            }
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(findingIds, "findingIds");
            findingIds = List.copyOf(findingIds);
            if (findingIds.isEmpty()) {
                throw new IllegalArgumentException("a group holds at least one finding; an empty one claims findings it does not have");
            }
            if (count != findingIds.size()) {
                throw new IllegalArgumentException("a group's count is its findings: " + count + " != " + findingIds.size());
            }
            for (String id : findingIds) {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("every finding id is a navigation target; none may be blank");
                }
            }
        }

        /**
         * The finding a gutter click navigates to: the first id of the
         * group in store order — deterministic, and the same finding the
         * Review tree lists first for that line.
         */
        public String navigationFindingId() {
            return findingIds.get(0);
        }
    }
}
