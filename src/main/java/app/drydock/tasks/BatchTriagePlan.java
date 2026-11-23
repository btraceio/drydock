package app.drydock.tasks;

import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure planning for the batch triage of a project's Issues section: which
 * listed issues are untriaged, and what the hybrid labeling rule appends to
 * a triage prompt. No I/O, no platform classes -- the same answers serve the
 * GitHub and the Jira sections, whose listings ({@link OpenIssue}, {@link
 * JiraIssue}) both carry the issue's current labels.
 *
 * <p>An issue is <em>untriaged</em> when it does not carry the exact label
 * {@link #TRIAGED_LABEL}. Issues already in flight (a triage session was
 * queued or launched for them in this IDE) are filtered by the caller's
 * in-flight set: the tracker's own listing can be minutes stale, so absence
 * of the label alone would double-queue an issue whose session has not
 * labeled it yet.</p>
 */
public final class BatchTriagePlan {

    /** The label that marks an issue as triaged, on GitHub and Jira alike. */
    public static final String TRIAGED_LABEL = "triaged";

    private BatchTriagePlan() {
    }

    /** The GitHub issues of {@code issues} that lack the triaged label and are not in flight. Order preserved. */
    public static List<OpenIssue> untriagedGitHub(List<OpenIssue> issues, Set<Integer> inFlight) {
        Objects.requireNonNull(issues, "issues");
        Objects.requireNonNull(inFlight, "inFlight");
        return issues.stream()
                .filter(issue -> !issue.labels().contains(TRIAGED_LABEL) && !inFlight.contains(issue.number()))
                .toList();
    }

    /** The Jira issues of {@code issues} that lack the triaged label and are not in flight. Order preserved. */
    public static List<JiraIssue> untriagedJira(List<JiraIssue> issues, Set<String> inFlight) {
        Objects.requireNonNull(issues, "issues");
        Objects.requireNonNull(inFlight, "inFlight");
        return issues.stream()
                .filter(issue -> !issue.labels().contains(TRIAGED_LABEL) && !inFlight.contains(issue.key()))
                .toList();
    }

    /**
     * Whether a triage methodology already talks about labeling. The hybrid
     * labeling rule consults this before anything is appended to a prompt:
     * a registered skill that handles labeling keeps full control, and the
     * built-in steps -- which never mention labels -- always get the step.
     * Deliberately broad: any mention of labels at all counts as handling
     * them, because a duplicated instruction is harmless but a contradiction
     * is not.
     */
    public static boolean mentionsLabeling(String methodology) {
        return methodology != null && methodology.toLowerCase(Locale.ROOT).contains("label");
    }

    /**
     * The labeling step appended after a triage methodology that does not
     * already cover labeling: the session adds the {@code triaged} label
     * itself, through the target system's own tooling. GitHub only -- a Jira
     * session has no Jira credentials, so there the plugin labels the issue
     * instead and no such step exists.
     */
    public static String githubLabelingStep() {
        return "\n\nWhen the triage is complete, label the issue so it is not triaged again:"
                + " run `gh issue edit <number> --add-label " + TRIAGED_LABEL + "`.";
    }

    /** The session display name for one GitHub issue's triage, as the tree and tab show it. */
    public static String sessionName(OpenIssue issue) {
        return "Triage #" + issue.number() + " " + issue.title();
    }

    /** The session display name for one Jira issue's triage, as the tree and tab show it. */
    public static String sessionName(JiraIssue issue) {
        return "Triage " + issue.key() + " " + issue.summary();
    }

    /**
     * The GitHub issues of a paused queue that still need triage: entries the
     * fresh listing still shows, without the triaged label, by number. An
     * entry the listing no longer knows (closed, deleted, filtered away) is
     * dropped, not failed -- a paused queue is a snapshot, the tracker is the
     * truth. Pure.
     */
    public static List<OpenIssue> resumeSurvivorsGitHub(List<BatchTriageQueue.Entry> entries,
                                                        List<OpenIssue> listed) {
        Map<Integer, OpenIssue> byNumber = new HashMap<>();
        for (OpenIssue issue : listed) {
            byNumber.put(issue.number(), issue);
        }
        List<OpenIssue> survivors = new ArrayList<>();
        for (BatchTriageQueue.Entry entry : entries) {
            OpenIssue issue;
            try {
                issue = byNumber.get(Integer.parseInt(entry.key().strip()));
            } catch (NumberFormatException e) {
                issue = null;
            }
            if (issue != null && !issue.labels().contains(TRIAGED_LABEL)) {
                survivors.add(issue);
            }
        }
        return survivors;
    }

    /** The Jira analogue of {@link #resumeSurvivorsGitHub}, matched by issue key. Pure. */
    public static List<JiraIssue> resumeSurvivorsJira(List<BatchTriageQueue.Entry> entries,
                                                      List<JiraIssue> listed) {
        Map<String, JiraIssue> byKey = new HashMap<>();
        for (JiraIssue issue : listed) {
            byKey.put(issue.key(), issue);
        }
        List<JiraIssue> survivors = new ArrayList<>();
        for (BatchTriageQueue.Entry entry : entries) {
            JiraIssue issue = byKey.get(entry.key().strip());
            if (issue != null && !issue.labels().contains(TRIAGED_LABEL)) {
                survivors.add(issue);
            }
        }
        return survivors;
    }
}
