package app.drydock.review;

import app.drydock.github.GitHubLineAnchor;
import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * What Submit would post to GitHub, computed before a single network call is
 * made: which findings become comments, which are refused because GitHub
 * would reject them, and which review event to preselect. Deliberately plain
 * JDK types (no {@code ReviewDiffRow}/{@code ReviewDiffColumn}) -- this record
 * is what crosses from {@code app.drydock.ui.review}, which owns the diff,
 * into {@code app.drydock.ui}, which owns the host and cannot see a diff row.
 */
public record SubmitPlan(Event preselected, List<Comment> comments, List<ReviewAnnotation.Key> posting,
                          List<Refusal> refusals, List<BodyNote> bodyNotes) {

    public SubmitPlan {
        comments = List.copyOf(comments);
        posting = List.copyOf(posting);
        refusals = List.copyOf(refusals);
        bodyNotes = List.copyOf(bodyNotes);
        // The invariant withBodies (and the sheet's per-finding editing)
        // relies on: posting's first comments.size() entries name the
        // findings the comments carry, in order, and the rest name the body
        // notes. Made structural so a future builder cannot silently break
        // the alignment the editing keys on.
        if (posting.size() != comments.size() + bodyNotes.size()) {
            throw new IllegalArgumentException("posting (" + posting.size() + ") must align with comments ("
                    + comments.size() + ") plus body notes (" + bodyNotes.size() + ")");
        }
    }

    /**
     * A comment on a line GitHub has no diff position for (an unchanged
     * caller, say -- the point of whole-file review). It is posted inside the
     * review body as {@code file:line}, with the line's text as an excerpt so
     * the note still reads without the code beside it.
     */
    public record BodyNote(ReviewAnnotation.Key key, String file, String lineLabel, String excerpt, String body) {
        public BodyNote {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(lineLabel, "lineLabel");
            Objects.requireNonNull(excerpt, "excerpt");
            Objects.requireNonNull(body, "body");
        }

        /** {@code file:line}, the form the review body and the submit sheet both show. */
        public String location() {
            return file + ":" + lineLabel;
        }
    }

    /** A finding GitHub would reject, and why -- named in words a human can act on. */
    public record Refusal(ReviewAnnotation.Key key, String reason) {
        public Refusal {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Where each diff line sits, so the refusal rules below are decidable.
     * Both maps are keyed by {@code file + " " + lineKey}. {@code
     * positionOfKey} is a monotonically increasing ordinal in diff order --
     * membership in the diff is {@code positionOfKey.containsKey(...)}, and
     * position is what lets the reversed-range guard be decided at all: an
     * {@code o}-key and an {@code n}-key live in different namespaces and
     * cannot be compared by their line numbers alone.
     */
    public record DiffIndex(Map<String, Integer> positionOfKey, Map<String, Integer> hunkOfKey) {
        public DiffIndex {
            Objects.requireNonNull(positionOfKey, "positionOfKey");
            Objects.requireNonNull(hunkOfKey, "hunkOfKey");
        }

        private String key(String file, String lineKey) {
            return file + " " + lineKey;
        }
    }

    /**
     * Which review event to preselect from the human's decisions on this
     * scope's files. Any {@code CHANGES} outweighs everything else; an empty
     * list of decisions (a scope with no file to decide) preselects a plain
     * comment, since {@code SessionReviewView.submitReview()} only reaches
     * {@code host.submit} once every file with hunks is decided, so this is
     * reachable only when there are none.
     * Switches over {@code Decision} exhaustively with no {@code default} --
     * a fourth constant must fail to compile here, not silently approve.
     */
    public static Event preselect(List<ReviewVerdict.Decision> decisions) {
        if (decisions.isEmpty()) {
            return Event.COMMENT;
        }
        for (ReviewVerdict.Decision decision : decisions) {
            switch (decision) {
                case CHANGES -> {
                    return Event.REQUEST_CHANGES;
                }
                case APPROVED, AUTO_APPROVED -> {
                    // still eligible for APPROVE; keep scanning for a CHANGES
                }
            }
        }
        return Event.APPROVE;
    }

    /** GitHub refuses an empty body for {@code COMMENT} and {@code REQUEST_CHANGES}, but not {@code APPROVE}. */
    public static boolean needsSummary(Event event) {
        return event == Event.COMMENT || event == Event.REQUEST_CHANGES;
    }

    /**
     * Builds the plan: every {@code postToPr} finding becomes a comment or a
     * refusal, in encounter order. A finding with {@code postToPr() == false}
     * is silently excluded from both -- it simply is not being posted.
     *
     * <p>A {@link ReviewAnnotation#resolved()} finding is excluded the same
     * way, REGARDLESS of {@code postToPr}: the margin's default filter
     * ({@code Filter.OPEN}) hides resolved cards, so a stale {@code
     * postToPr} left over from before it was resolved has no visible toggle
     * to opt back out with short of switching to "all" -- posting it anyway
     * would be a publish the human never had a real chance to review. A
     * finding the human has not {@linkplain Triage#CONFIRMED confirmed} is
     * excluded the same way.</p>
     */
    public static SubmitPlan of(List<ReviewAnnotation> findings, List<ReviewVerdict.Decision> decisions,
                                 DiffIndex index) {
        return build(findings, decisions, index, null);
    }

    /**
     * As {@link #of(List, List, DiffIndex)}, except a finding whose start or
     * end line is not in the diff becomes a {@link BodyNote} (its excerpt
     * from {@code lineText(file, lineKey)}) instead of a refusal. The other
     * refusals -- two hunks, backwards, cross-side -- are unchanged.
     */
    public static SubmitPlan of(List<ReviewAnnotation> findings, List<ReviewVerdict.Decision> decisions,
                                 DiffIndex index, BiFunction<String, String, Optional<String>> lineText) {
        return build(findings, decisions, index, Objects.requireNonNull(lineText, "lineText"));
    }

    private static SubmitPlan build(List<ReviewAnnotation> findings, List<ReviewVerdict.Decision> decisions,
                                    DiffIndex index, BiFunction<String, String, Optional<String>> lineText) {
        List<BodyNote> bodyNotes = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        List<ReviewAnnotation.Key> posting = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();

        for (ReviewAnnotation finding : findings) {
            if (!finding.postToPr() || finding.resolved() || !finding.counts()) {
                continue;
            }
            String startCompositeKey = index.key(finding.file(), finding.startKey());
            String endCompositeKey = index.key(finding.file(), finding.endKey());

            Integer startPosition = index.positionOfKey().get(startCompositeKey);
            Integer endPosition = index.positionOfKey().get(endCompositeKey);
            if ((startPosition == null || endPosition == null) && lineText != null) {
                bodyNotes.add(new BodyNote(finding.key(), finding.file(),
                        lineLabel(finding.startKey(), finding.endKey()),
                        lineText.apply(finding.file(), finding.startKey()).orElse(""), bodyOf(finding)));
                posting.add(finding.key());
                continue;
            }
            if (startPosition == null || endPosition == null) {
                refusals.add(new Refusal(finding.key(), "line %s is not in this diff"
                        .formatted(startPosition == null ? finding.startKey() : finding.endKey())));
                continue;
            }

            Integer startHunk = index.hunkOfKey().get(startCompositeKey);
            Integer endHunk = index.hunkOfKey().get(endCompositeKey);
            if (!Objects.equals(startHunk, endHunk)) {
                refusals.add(new Refusal(finding.key(), "%s lines %s–%s span two hunks; GitHub takes one hunk per comment"
                        .formatted(finding.file(), finding.startKey(), finding.endKey())));
                continue;
            }

            if (startPosition > endPosition) {
                refusals.add(new Refusal(finding.key(), "%s lines %s–%s: the range runs backwards"
                        .formatted(finding.file(), finding.startKey(), finding.endKey())));
                continue;
            }

            // A cross-side range is only legal LEFT->RIGHT -- start on a
            // deleted (`o`) line, end on a post-image (`n`) line, GitHub's
            // "comment on lines -55 to +58" shape. Git routinely interleaves
            // hunks (-a +b -c +d), so a RIGHT->LEFT drag (start on an `n`
            // line, end on an `o` line) can still have startPosition <=
            // endPosition and pass the check above. GitHubLineAnchor.of would
            // then emit start_side: RIGHT with side: LEFT, which GitHub
            // rejects -- and since the whole review is one atomic POST, that
            // single rejection would 422 every other comment in it.
            boolean startDeleted = finding.startKey().startsWith("o");
            boolean endDeleted = finding.endKey().startsWith("o");
            if (!startDeleted && endDeleted) {
                refusals.add(new Refusal(finding.key(),
                        ("%s lines %s–%s: a range across a deletion must start on the deleted line and end "
                                + "on its replacement, not the other way round")
                                .formatted(finding.file(), finding.startKey(), finding.endKey())));
                continue;
            }

            GitHubLineAnchor.Anchor anchor = GitHubLineAnchor.of(finding.startKey(), finding.endKey());
            comments.add(new Comment(finding.file(), bodyOf(finding), anchor));
            posting.add(finding.key());
        }

        return new SubmitPlan(preselect(decisions), comments, posting, refusals, bodyNotes);
    }

    /**
     * The review body: {@code summary}, then -- when there are notes -- a
     * blank line, a heading, and one bullet per note.
     *
     * <p>GitHub renders the body as markdown, so each piece is shaped to
     * survive that: the location is an inline code span; every line of a
     * multi-line comment after the first is indented two spaces so it stays
     * inside its bullet; and the excerpt -- source text, full of {@code <T>},
     * {@code *} and backticks markdown would otherwise eat -- goes in a
     * fenced code block inside the bullet, fenced with more backticks than
     * any run in it.</p>
     */
    public String composeBody(String summary) {
        if (bodyNotes.isEmpty()) {
            return summary;
        }
        StringBuilder out = new StringBuilder(summary);
        if (!summary.isBlank()) {
            out.append("\n\n");
        }
        out.append("Comments on lines outside this diff:\n");
        for (BodyNote note : bodyNotes) {
            out.append("- ").append(inlineCode(note.location())).append(" — ")
                    .append(indentContinuation(note.body())).append('\n');
            if (!note.excerpt().isEmpty()) {
                String fence = fenceFor(note.excerpt());
                out.append('\n').append(LIST_INDENT).append(fence).append('\n');
                for (String line : note.excerpt().split("\n", -1)) {
                    out.append(LIST_INDENT).append(line).append('\n');
                }
                out.append(LIST_INDENT).append(fence).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    /** How far a line is indented to stay inside a {@code "- "} bullet. */
    private static final String LIST_INDENT = "  ";

    /** Every line after the first indented into the bullet; blank lines stay blank. */
    private static String indentContinuation(String text) {
        String[] lines = text.strip().split("\n", -1);
        StringBuilder out = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            out.append('\n');
            if (!lines[i].isBlank()) {
                out.append(LIST_INDENT).append(lines[i]);
            }
        }
        return out.toString();
    }

    /** A code fence longer than the longest backtick run in {@code text}, and at least three. */
    private static String fenceFor(String text) {
        return "`".repeat(Math.max(3, longestBacktickRun(text) + 1));
    }

    /** {@code text} as an inline code span, its delimiter longer than any backtick run inside. */
    private static String inlineCode(String text) {
        int run = longestBacktickRun(text);
        if (run == 0) {
            return "`" + text + "`";
        }
        String delimiter = "`".repeat(run + 1);
        return delimiter + " " + text + " " + delimiter;
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            current = text.charAt(i) == '`' ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    /** {@code n500} reads {@code 500}; a deleted {@code o5} reads {@code 5(-)}; a range joins both ends. */
    private static String lineLabel(String startKey, String endKey) {
        return startKey.equals(endKey) ? labelOf(startKey) : labelOf(startKey) + "–" + labelOf(endKey);
    }

    private static String labelOf(String key) {
        return key.startsWith("o") ? key.substring(1) + "(-)" : key.substring(1);
    }

    /**
     * The plan with every comment's and body note's text replaced by the
     * human's edits: what the submit sheet's per-finding editing produces
     * before the post. Keyed by the finding's annotation key (the sheet
     * knows it from {@link #posting()}), applied to both routes -- an
     * inline comment and a body note are the same finding wearing two
     * transports. Everything else -- anchors, refusals, the preselected
     * event -- is carried over unchanged: the edit rewords the finding, it
     * does not re-decide it.
     */
    public SubmitPlan withBodies(Map<ReviewAnnotation.Key, String> editedBodies) {
        if (editedBodies.isEmpty()) {
            return this;
        }
        List<Comment> rewordedComments = new ArrayList<>();
        List<BodyNote> rewordedNotes = new ArrayList<>();
        for (int i = 0; i < comments.size(); i++) {
            String replacement = editedBodies.get(posting.get(i));
            rewordedComments.add(replacement == null ? comments.get(i)
                    : new Comment(comments.get(i).path(), replacement, comments.get(i).anchor()));
        }
        for (BodyNote note : bodyNotes) {
            String replacement = editedBodies.get(note.key());
            rewordedNotes.add(replacement == null ? note
                    : new BodyNote(note.key(), note.file(), note.lineLabel(), note.excerpt(), replacement));
        }
        return new SubmitPlan(preselected, rewordedComments, posting, refusals, rewordedNotes);
    }

    /** How many findings still wait for a human's confirm-or-dismiss: proposed and unresolved. */
    public static long untriagedCount(List<ReviewAnnotation> findings) {
        return findings.stream()
                .filter(finding -> finding.triage() == Triage.PROPOSED && !finding.resolved())
                .count();
    }

    /** The last message the human wrote on this thread, falling back to the finding's own first message. */
    private static String bodyOf(ReviewAnnotation finding) {
        List<ReviewAnnotation.Message> thread = finding.thread();
        for (int i = thread.size() - 1; i >= 0; i--) {
            ReviewAnnotation.Message message = thread.get(i);
            if ("You".equals(message.author())) {
                return message.text();
            }
        }
        return thread.isEmpty() ? "" : thread.get(0).text();
    }
}
