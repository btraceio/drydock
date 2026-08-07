package app.drydock.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One finding against a review scope: a human's annotation or a reviewer's
 * finding, which are the same thing in this model and differ only by {@link
 * #author()} (Review MCP schema §3).
 *
 * <p><strong>Identity is {@code (scopeId, id)}, never {@code id} alone.</strong>
 * Finding ids are agent-chosen and stable across re-runs, so the <em>same</em>
 * id genuinely appears in two worktrees at once -- a re-run of the same
 * reviewer against two branches produces {@code f_leak_1} in both. Keying on
 * the id alone caused a real bug where resolving a finding in one worktree
 * resolved it in another.</p>
 *
 * <p>The anchor is stored as <em>stable line keys</em> -- {@code n<newLine>}
 * for lines that exist in the post-image, {@code o<oldLine>} for deleted
 * lines (see {@link app.drydock.git.UnifiedDiff.Line#lineKey()}) -- so
 * findings survive a re-diff of the same scope.</p>
 */
public record ReviewAnnotation(
        String scopeId,
        String id,
        Optional<String> intentId,
        String file,
        String startKey,
        String endKey,
        Severity severity,
        Confidence confidence,
        Optional<String> title,
        String author,
        Instant at,
        List<Evidence> evidence,
        Optional<Patch> patch,
        Optional<DeviatesFrom> deviatesFrom,
        List<Ask> asks,
        List<Message> thread,
        Optional<Severity> severityOverride,
        AnnotationStatus status,
        Optional<GitHubComment> github,
        boolean postToPr
) {

    /** One message of the thread; {@code author} is "You" or the reviewer's name. */
    public record Message(String author, Instant at, String text, Optional<Evidence> code) {
        public Message {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(code, "code");
        }

        public Message(String author, Instant at, String text) {
            this(author, at, text, Optional.empty());
        }
    }

    /** A labelled code excerpt supporting a finding or a reply. */
    public record Evidence(String label, String code, String language) {
        public Evidence {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(language, "language");
        }
    }

    /**
     * A <em>proposed</em> patch. drydock never applies one: {@code Apply
     * patch} is a human click. An agent that writes to the tree behind the
     * review is a bug, not a feature (schema §3).
     */
    public record Patch(String unified, String summary) {
        public Patch {
            Objects.requireNonNull(unified, "unified");
            Objects.requireNonNull(summary, "summary");
        }
    }

    /** What the change departs from: the human's own instruction, and where. */
    public record DeviatesFrom(String prompt, Optional<Integer> step) {
        public DeviatesFrom {
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(step, "step");
        }
    }

    /** A prebaked follow-up the human can fire with one click (the ASK chips). */
    public record Ask(String label, String question) {
        public Ask {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(question, "question");
        }
    }

    /**
     * What GitHub knows about this annotation. Present means the comment
     * exists on the pull request -- whether drydock posted it or read it back
     * -- and {@code id} is the REST numeric comment id (GraphQL's
     * {@code databaseId}), because that is the id the replies endpoint takes.
     */
    public record GitHubComment(long id, String url, boolean resolved) {
        public GitHubComment {
            Objects.requireNonNull(url, "url");
        }
    }

    public ReviewAnnotation {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(startKey, "startKey");
        Objects.requireNonNull(endKey, "endKey");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(deviatesFrom, "deviatesFrom");
        Objects.requireNonNull(severityOverride, "severityOverride");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(github, "github");
        if (scopeId.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("a finding is keyed by (scopeId, id); neither may be blank");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
        thread = List.copyOf(Objects.requireNonNull(thread, "thread"));
    }

    /** The composite key everything is stored and looked up under. */
    public Key key() {
        return new Key(scopeId, id);
    }

    /** {@code (scopeId, id)} -- see the class note on why the id alone will not do. */
    public record Key(String scopeId, String id) {
        public Key {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(id, "id");
        }
    }

    /** A human annotation typed into the margin: a question at high confidence, authored by "You". */
    public static ReviewAnnotation human(String scopeId, String file, String startKey, String endKey,
                                         Message firstMessage) {
        return human(scopeId, file, startKey, endKey, firstMessage, Severity.QUESTION);
    }

    /**
     * A human annotation typed into the margin, with the severity the human
     * chose from the composer's chip row rather than the {@code QUESTION}
     * default.
     */
    public static ReviewAnnotation human(String scopeId, String file, String startKey, String endKey,
                                         Message firstMessage, Severity severity) {
        return new ReviewAnnotation(scopeId, UUID.randomUUID().toString(), Optional.empty(),
                file, startKey, endKey, severity, Confidence.HIGH, Optional.empty(),
                firstMessage.author(), firstMessage.at(), List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(firstMessage), Optional.empty(), AnnotationStatus.OPEN,
                Optional.empty(), true);
    }

    /** The severity actually in force: the human's override when there is one, else the reviewer's. */
    public Severity effectiveSeverity() {
        return severityOverride.orElse(severity);
    }

    /** Whether this finding refuses approval of its intent right now. */
    public boolean blocksApproval() {
        return !resolved() && effectiveSeverity().blocksApproval();
    }

    public boolean resolved() {
        return status == AnnotationStatus.RESOLVED || status == AnnotationStatus.FIXED;
    }

    /** The one-line label the margin card and the pin tooltip show. */
    public String displayTitle() {
        return title.filter(text -> !text.isBlank()).orElseGet(() -> firstLineOfBody());
    }

    private String firstLineOfBody() {
        String body = thread.isEmpty() ? "" : thread.get(0).text();
        int newline = body.indexOf('\n');
        String line = newline < 0 ? body : body.substring(0, newline);
        return line.length() <= 80 ? line : line.substring(0, 79) + "…";
    }

    public ReviewAnnotation withStatus(AnnotationStatus newStatus) {
        return new ReviewAnnotation(scopeId, id, intentId, file, startKey, endKey, severity, confidence,
                title, author, at, evidence, patch, deviatesFrom, asks, thread, severityOverride, newStatus,
                github, postToPr);
    }

    public ReviewAnnotation withReply(Message reply) {
        List<Message> extended = new ArrayList<>(thread);
        extended.add(reply);
        return new ReviewAnnotation(scopeId, id, intentId, file, startKey, endKey, severity, confidence,
                title, author, at, evidence, patch, deviatesFrom, asks, extended, severityOverride, status,
                github, postToPr);
    }

    /**
     * Records a human's severity override. The reviewer's original {@link
     * #severity()} is deliberately left intact: an agent re-reading the state
     * must be able to see both what it said and what the human decided.
     */
    public ReviewAnnotation withSeverityOverride(Severity override) {
        return new ReviewAnnotation(scopeId, id, intentId, file, startKey, endKey, severity, confidence,
                title, author, at, evidence, patch, deviatesFrom, asks, thread,
                Optional.ofNullable(override), status, github, postToPr);
    }

    /** Re-keys this finding onto another scope (see {@code AnnotationStore.adoptLegacy}). */
    public ReviewAnnotation withScopeId(String newScopeId) {
        return new ReviewAnnotation(newScopeId, id, intentId, file, startKey, endKey, severity, confidence,
                title, author, at, evidence, patch, deviatesFrom, asks, thread, severityOverride, status,
                github, postToPr);
    }

    /** Stamps the GitHub comment this annotation became (or came from). */
    public ReviewAnnotation withGithub(GitHubComment comment) {
        return new ReviewAnnotation(scopeId, id, intentId, file, startKey, endKey, severity, confidence,
                title, author, at, evidence, patch, deviatesFrom, asks, thread, severityOverride, status,
                Optional.of(comment), postToPr);
    }

    /** The human's intent to include (or stop including) this in the next submit. */
    public ReviewAnnotation withPostToPr(boolean post) {
        return new ReviewAnnotation(scopeId, id, intentId, file, startKey, endKey, severity, confidence,
                title, author, at, evidence, patch, deviatesFrom, asks, thread, severityOverride, status,
                github, post);
    }
}
