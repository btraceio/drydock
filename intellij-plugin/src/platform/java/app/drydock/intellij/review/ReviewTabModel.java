package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.VerdictMerge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Review tab's whole tree in one pure value: one {@link Snapshot} per
 * refresh, computed from the scope's diff plus the store's findings and
 * verdicts, with no platform class in sight (spec "Rows and marks", AC5-AC8).
 *
 * <p>Pure is a hard requirement, not a convenience: the tree (T12) and the
 * header (T13) render whatever this computes, so every mark, child row,
 * footer and count they show is testable headless against a hand-built
 * {@link UnifiedDiff} -- the same shape {@code DiffService} parses -- with
 * no git checkout and no IDE. The callers feed it the one scope's data; the
 * filtering rules live here so the IDE code cannot drift from the spec.</p>
 *
 * <p>Line keys are the wire contract findings are stored under ({@code
 * n<newLine>} / {@code o<oldLine>}, {@code kb/find-line-key-contract}): they
 * are parsed here only to render a child's {@code :<line>} suffix, and never
 * migrated -- a finding whose key is gone from the current diff keeps its
 * key and gains the drift hint ({@code
 * kb/find-finding-anchor-drift-gap}).</p>
 */
public final class ReviewTabModel {

    /** The hint a row carries when its verdicts were given against an older base (Q4). */
    public static final String STALE_HINT = "reviewed against an older base";

    /** The distinct tooltip a row carries when its merged decision is {@code CHANGES} (Q3). */
    public static final String CHANGES_HINT = "changes requested";

    /** The hint a finding child carries when its start key is not a row key of the current diff (AC7). */
    public static final String DRIFT_HINT = "line not in the current diff";

    private ReviewTabModel() {
    }

    /**
     * Computes the snapshot. {@code findings} and {@code verdicts} are the
     * one scope's lists, as the store's {@code forScope}/{@code verdictsFor}
     * return them (a different scope's rows are not this tab's business);
     * verdicts on the same hunk digest keep the first in list order, which
     * is the store's own add order.
     */
    public static Snapshot snapshot(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        Map<String, ReviewVerdict> verdictsByDigest = new LinkedHashMap<>();
        for (ReviewVerdict verdict : inputs.verdicts()) {
            verdictsByDigest.putIfAbsent(verdict.hunkDigest(), verdict);
        }

        List<UnifiedDiff.FileDiff> displayed = inputs.diff().files().stream()
                .filter(file -> !ideNoise(file))
                .sorted(Comparator.comparing(UnifiedDiff.FileDiff::path))
                .toList();

        List<FileRow> rows = new ArrayList<>();
        for (UnifiedDiff.FileDiff file : displayed) {
            rows.add(rowFor(file, inputs.findings(), verdictsByDigest, inputs.currentBaseCommit()));
        }

        int reviewable = (int) rows.stream().filter(row -> !row.diff().hunks().isEmpty()).count();
        int reviewed = (int) rows.stream().filter(row -> row.mark().kind() == Mark.Kind.REVIEWED).count();
        return new Snapshot(List.copyOf(rows), reviewable, reviewed);
    }

    /**
     * The rows to hide (AC5): untracked IDE project files, and only those --
     * a tracked, modified {@code .idea/…} file is review material and stays.
     * Hiding is display-only; no hunk id or digest of any other file changes.
     */
    private static boolean ideNoise(UnifiedDiff.FileDiff file) {
        return file.untracked()
                && (file.path().startsWith(".idea/") || file.path().endsWith(".iml"));
    }

    private static FileRow rowFor(UnifiedDiff.FileDiff file,
                                  List<ReviewAnnotation> findings,
                                  Map<String, ReviewVerdict> verdictsByDigest,
                                  Optional<String> currentBaseCommit) {
        String path = file.path();
        List<ReviewAnnotation> own = findings.stream()
                .filter(f -> f.file().equals(path))
                .toList();

        List<String> hunkIds = new ArrayList<>();
        List<String> hunkDigests = new ArrayList<>();
        Set<String> distinctDigests = new LinkedHashSet<>();
        Set<String> rowKeys = new LinkedHashSet<>();
        for (int i = 0; i < file.hunks().size(); i++) {
            UnifiedDiff.Hunk hunk = file.hunks().get(i);
            hunkIds.add(HunkIds.hunkId(path, i));
            String digest = HunkDigest.of(path, hunk);
            hunkDigests.add(digest);
            distinctDigests.add(digest);
            for (UnifiedDiff.Line line : hunk.lines()) {
                rowKeys.add(line.lineKey());
            }
        }

        List<ReviewAnnotation> open = own.stream()
                .filter(f -> !f.resolved() && f.triage() != Triage.DISMISSED)
                .toList();
        long hidden = own.stream()
                .filter(f -> f.resolved() || f.triage() == Triage.DISMISSED)
                .count();

        List<FindingRow> children = new ArrayList<>();
        for (ReviewAnnotation finding : open) {
            children.add(new FindingRow(
                    finding.id(),
                    finding.effectiveSeverity(),
                    finding.displayTitle(),
                    finding.startKey(),
                    parseLine(finding.startKey()),
                    rowKeys.contains(finding.startKey()) ? Optional.empty() : Optional.of(DRIFT_HINT),
                    finding));
        }

        return new FileRow(
                path,
                file,
                markFor(file, open.size(), distinctDigests, verdictsByDigest, currentBaseCommit),
                List.copyOf(children),
                hidden == 0
                        ? Optional.empty()
                        : Optional.of(hidden + " resolved findings hidden"),
                List.copyOf(hunkIds),
                List.copyOf(hunkDigests));
    }

    /**
     * One mark per row, precedence Q3: open findings, then reviewed, then
     * unreviewed; a file with no text hunks gets the no-text mark instead of
     * the last two, because there is nothing to approve (AC6).
     */
    private static Mark markFor(UnifiedDiff.FileDiff file,
                                int openFindings,
                                Set<String> distinctDigests,
                                Map<String, ReviewVerdict> verdictsByDigest,
                                Optional<String> currentBaseCommit) {
        if (openFindings > 0) {
            return new Mark(Mark.Kind.OPEN_FINDINGS, openFindings, Optional.empty());
        }
        if (file.hunks().isEmpty()) {
            return new Mark(Mark.Kind.NO_TEXT_CHANGES, 0, Optional.empty());
        }

        List<Optional<ReviewVerdict>> hunkVerdicts = distinctDigests.stream()
                .map(digest -> Optional.ofNullable(verdictsByDigest.get(digest)))
                .toList();
        List<ReviewVerdict> counted = hunkVerdicts.stream().flatMap(Optional::stream).toList();
        // Q4, conservative: a missing base commit cannot vouch for any
        // verdict's freshness, so it leaves the row unreviewed.
        boolean fresh = currentBaseCommit.isPresent()
                && !counted.isEmpty()
                && counted.stream().noneMatch(v -> v.staleAgainst(currentBaseCommit.get()));
        Optional<ReviewVerdict.Decision> decision = VerdictMerge.derive(hunkVerdicts);

        if (fresh && decision.isPresent() && (decision.get() == ReviewVerdict.Decision.APPROVED
                || decision.get() == ReviewVerdict.Decision.AUTO_APPROVED)) {
            return new Mark(Mark.Kind.REVIEWED, 0, decision.map(ReviewVerdict.Decision::label));
        }

        List<String> hints = new ArrayList<>();
        if (decision.isPresent() && decision.get() == ReviewVerdict.Decision.CHANGES) {
            hints.add(CHANGES_HINT);
        }
        if (!counted.isEmpty() && !fresh) {
            hints.add(STALE_HINT);
        }
        return new Mark(Mark.Kind.UNREVIEWED, 0,
                hints.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", hints)));
    }

    /**
     * The number a finding's start key addresses ({@code n<N>}/{@code o<N>}
     * → N), or empty for anything else: keys are machine-written, but this
     * parses persisted files, so a malformed key is a value, not a crash.
     */
    private static Optional<Integer> parseLine(String startKey) {
        if (startKey == null || startKey.length() < 2) {
            return Optional.empty();
        }
        char kind = startKey.charAt(0);
        if (kind != 'n' && kind != 'o') {
            return Optional.empty();
        }
        for (int i = 1; i < startKey.length(); i++) {
            if (!Character.isDigit(startKey.charAt(i))) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Integer.valueOf(startKey.substring(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ---- the snapshot -------------------------------------------------------

    /** Everything one refresh computes: the diff plus the scope's review state. */
    public record Inputs(
            UnifiedDiff diff,
            List<ReviewAnnotation> findings,
            List<ReviewVerdict> verdicts,
            Optional<String> currentBaseCommit
    ) {
        public Inputs {
            Objects.requireNonNull(diff, "diff");
            Objects.requireNonNull(findings, "findings");
            Objects.requireNonNull(verdicts, "verdicts");
            Objects.requireNonNull(currentBaseCommit, "currentBaseCommit");
            findings = List.copyOf(findings);
            verdicts = List.copyOf(verdicts);
        }
    }

    /** The tree rows in path order, plus the header's counts (AC8). */
    public record Snapshot(List<FileRow> files, int reviewableFiles, int reviewedFiles) {

        /** The header text: {@code <k> / <n> reviewed}, where n counts only files with at least one hunk. */
        public String headerText() {
            return reviewedFiles + " / " + reviewableFiles + " reviewed";
        }
    }

    /**
     * One displayed file: its {@link UnifiedDiff.FileDiff} (the tree still
     * needs kind/staged/untracked), its mark, its finding children, its
     * resolved-findings footer, and the hunk ids and digests AC4's comparison
     * reads back (one entry per hunk, in hunk order, ids from {@code
     * HunkIds.hunkId(path, index)}).
     */
    public record FileRow(
            String path,
            UnifiedDiff.FileDiff diff,
            Mark mark,
            List<FindingRow> findings,
            Optional<String> resolvedFindingsFooter,
            List<String> hunkIds,
            List<String> hunkDigests
    ) {
        public FileRow {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(diff, "diff");
            Objects.requireNonNull(mark, "mark");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            Objects.requireNonNull(resolvedFindingsFooter, "resolvedFindingsFooter");
            hunkIds = List.copyOf(Objects.requireNonNull(hunkIds, "hunkIds"));
            hunkDigests = List.copyOf(Objects.requireNonNull(hunkDigests, "hunkDigests"));
        }
    }

    /** One finding child row: severity, title, the {@code :<line>} suffix's line, and the drift hint if anchored off-diff. */
    public record FindingRow(
            String id,
            Severity severity,
            String title,
            String startKey,
            Optional<Integer> startLine,
            Optional<String> driftHint,
            ReviewAnnotation annotation
    ) {
        public FindingRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(startKey, "startKey");
            Objects.requireNonNull(startLine, "startLine");
            Objects.requireNonNull(driftHint, "driftHint");
            Objects.requireNonNull(annotation, "annotation");
        }
    }

    /** One row's mark: what it shows, and the hint that explains a non-obvious one. */
    public record Mark(Kind kind, int openFindings, Optional<String> hint) {

        /** The four marks a row can carry, in Q3's precedence order. */
        public enum Kind { OPEN_FINDINGS, REVIEWED, UNREVIEWED, NO_TEXT_CHANGES }

        public Mark {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(hint, "hint");
        }

        /** The glyph (or the no-text phrase) the row renders. */
        public String text() {
            return switch (kind) {
                case OPEN_FINDINGS -> "◆ " + openFindings;
                case REVIEWED -> "✓";
                case UNREVIEWED -> "○";
                case NO_TEXT_CHANGES -> "no text changes to review";
            };
        }
    }
}
