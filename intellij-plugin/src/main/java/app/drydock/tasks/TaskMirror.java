package app.drydock.tasks;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a task looks like once it is mirrored into a GitHub issue or a Jira
 * ticket, and how a mirror address is recognised again. Pure: no I/O, no
 * network; the writers send what this builds, and the task records the
 * address it gets back in its {@code mirrors} list.
 *
 * <p>The remote copy carries the task's title and body, a visible line
 * naming the task, and the task's whole metadata in a hidden form: inside
 * an HTML comment at the end of a GitHub issue body (GitHub does not render
 * it), and as the {@value #JIRA_PROPERTY} issue property on a Jira ticket
 * (Jira's issue view does not display issue properties). The metadata is the known
 * front-matter fields plus the raw front matter, so unknown keys travel
 * too. The local task is never removed or rewritten beyond its
 * {@code mirrors} entry.</p>
 */
public final class TaskMirror {

    /** The metadata format version, bumped only when a field changes meaning. */
    public static final int VERSION = 1;

    /** The Jira issue property that holds the metadata. */
    public static final String JIRA_PROPERTY = "drydock.task";

    /** Opens the hidden metadata comment in a GitHub issue body. */
    static final String COMMENT_OPEN = "<!-- drydock-task ";

    private static final Pattern GITHUB_ISSUE = Pattern.compile("/[^/]+/[^/]+/issues/(\\d+)/?");
    private static final Pattern JIRA_BROWSE = Pattern.compile(".*/browse/([A-Z][A-Z0-9_]*-\\d+)/?");

    /** Where a mirror lives. */
    public enum Kind { GITHUB, JIRA }

    private TaskMirror() {
    }

    /**
     * The task's metadata as one JSON object: format version, project,
     * id, every known front-matter field, and the raw front matter.
     */
    public static JsonObject metadata(TaskDocument document, String projectName) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(projectName, "projectName");
        JsonObject metadata = JsonObject.empty()
                .put("version", JsonNumber.of(VERSION))
                .put("repository", new JsonString(document.repository().toString()))
                .put("project", new JsonString(projectName))
                .put("id", new JsonString(document.id().text()))
                .put("title", new JsonString(document.title()))
                .put("status", new JsonString(document.status().storedName()))
                .put("done_by", new JsonString(document.doneBy().storedName()));
        document.created().ifPresent(created -> metadata.put("created", new JsonString(created.toString())));
        return metadata
                .put("after", strings(document.after()))
                .put("sessions", strings(document.sessions()))
                .put("front_matter", new JsonString(document.rawFrontMatter()));
    }

    /**
     * A GitHub issue body: the task body, a rule, a visible line naming the
     * task, and the metadata in an HTML comment GitHub does not render.
     */
    public static String githubBody(TaskDocument document, String projectName) {
        StringBuilder out = new StringBuilder(document.rawBody().length() + 512);
        appendBody(out, document.rawBody());
        out.append("---\n");
        out.append("Mirrored from Drydock task `").append(document.id().text()).append("` in project ")
                .append(projectName).append(".\n\n");
        out.append(hiddenComment(metadata(document, projectName))).append('\n');
        return out.toString();
    }

    /**
     * A Jira description in wiki markup, which both Cloud's and Data
     * Center's v2 issue API accept: the task body, a rule and a visible line
     * naming the task. The metadata goes into the {@value #JIRA_PROPERTY}
     * property, not here.
     */
    public static String jiraDescription(TaskDocument document, String projectName) {
        StringBuilder out = new StringBuilder(document.rawBody().length() + 128);
        appendBody(out, document.rawBody());
        out.append("----\n");
        out.append("Mirrored from Drydock task {{").append(document.id().text()).append("}} in project ")
                .append(projectName).append('.');
        return out.toString();
    }

    /**
     * The metadata as an HTML comment. JSON never needs {@code --} outside
     * a string, and inside one the second dash of each pair is written as
     * the JSON escape for a hyphen (backslash, {@code u002d}), so the
     * comment cannot end early and the JSON still decodes to the original
     * text.
     */
    static String hiddenComment(JsonObject metadata) {
        String json = JsonWriter.write(metadata).strip();
        return COMMENT_OPEN + json.replace("--", "-\\u002d") + " -->";
    }

    /**
     * The kind of mirror an address points at: a GitHub issue page
     * ({@code https://<host>/<owner>/<repo>/issues/<n>}) or a Jira browse
     * page ({@code .../browse/<KEY>-<n>}); empty for anything else.
     */
    public static Optional<Kind> kindOf(String url) {
        Optional<String> path = path(url);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        if (JIRA_BROWSE.matcher(path.get()).matches()) {
            return Optional.of(Kind.JIRA);
        }
        if (GITHUB_ISSUE.matcher(path.get()).matches()) {
            return Optional.of(Kind.GITHUB);
        }
        return Optional.empty();
    }

    /** The short name of a mirror: {@code #12} for a GitHub issue, the key for a Jira ticket, else the address. */
    public static String label(String url) {
        Optional<String> path = path(url);
        if (path.isPresent()) {
            Matcher jira = JIRA_BROWSE.matcher(path.get());
            if (jira.matches()) {
                return jira.group(1);
            }
            Matcher github = GITHUB_ISSUE.matcher(path.get());
            if (github.matches()) {
                return "#" + github.group(1);
            }
        }
        return url;
    }

    /**
     * Every recorded mirror address, normalized, to the task that records
     * it: the issue-to-task half of the link. Should two tasks record the
     * same address, the first in snapshot order keeps it.
     */
    public static Map<String, TaskDocument> byMirror(TaskSnapshot snapshot) {
        Map<String, TaskDocument> byMirror = new HashMap<>();
        for (TaskDocument document : snapshot.documents()) {
            for (String url : document.mirrors()) {
                byMirror.putIfAbsent(normalize(url), document);
            }
        }
        return Map.copyOf(byMirror);
    }

    /** Whether the task already has a mirror of {@code kind}. */
    public static boolean hasMirror(List<String> mirrors, Kind kind) {
        return mirrors.stream().anyMatch(url -> kindOf(url).filter(kind::equals).isPresent());
    }

    /**
     * The addresses in order, each address once: two tasks may record the
     * same mirror address ({@link #byMirror} keeps the first for the same
     * reason), and a repeated address would paint a duplicate link and open
     * a duplicate tab. Comparison is by {@link #normalize}, so two spellings
     * of one page count as one address; the first spelling is kept.
     */
    public static List<String> uniqueByAddress(List<String> urls) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        for (String url : urls) {
            if (seen.add(normalize(url))) {
                unique.add(url);
            }
        }
        return List.copyOf(unique);
    }

    /**
     * The same address the way two sources spell it: scheme and host
     * lowercased, a trailing slash dropped. {@code gh} and Jira both
     * return the canonical form, so this only absorbs hand edits.
     */
    public static String normalize(String url) {
        String stripped = url.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        try {
            URI uri = URI.create(stripped);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return stripped;
            }
            String prefix = uri.getScheme() + "://" + uri.getRawAuthority();
            String lowered = uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + uri.getRawAuthority().toLowerCase(Locale.ROOT);
            return lowered + stripped.substring(prefix.length());
        } catch (IllegalArgumentException e) {
            return stripped;
        }
    }

    private static Optional<String> path(String url) {
        if (url == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url.strip());
            return uri.getHost() == null || uri.getPath() == null ? Optional.empty() : Optional.of(uri.getPath());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void appendBody(StringBuilder out, String body) {
        String trimmed = body.strip();
        if (!trimmed.isEmpty()) {
            out.append(trimmed).append("\n\n");
        }
    }

    private static JsonArray strings(List<String> values) {
        return JsonArray.of(values.stream().map(value -> (JsonValue) new JsonString(value)).toList());
    }
}
