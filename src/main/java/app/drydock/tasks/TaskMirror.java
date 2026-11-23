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
    private static final Pattern JIRA_TERMINAL_BROWSE = Pattern.compile("(.*)/browse/([A-Z][A-Z0-9_]*-\\d+)/*");
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
        return githubBody(document, projectName, List.of());
    }

    /**
     * A direct prerequisite of the task being mirrored: the full address of
     * its GitHub issue and its title.
     */
    public record Dependency(String address, String title) {
        public Dependency {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(title, "title");
        }
    }

    /**
     * {@link #githubBody(TaskDocument, String)} with one {@code ## Dependencies}
     * section between the copied body and the rule, a line per direct
     * prerequisite in input order, each address once. The link target is the
     * full address, so cross-repository prerequisites resolve; the title is
     * escaped so it cannot break the list, the link or the hidden comment.
     * No prerequisites, no section.
     */
    public static String githubBody(TaskDocument document, String projectName, List<Dependency> dependencies) {
        StringBuilder out = new StringBuilder(document.rawBody().length() + 512);
        appendBody(out, document.rawBody());
        appendDependencies(out, dependencies);
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
     * The Jira ticket key a mirror address points at ({@code PROJ-123}),
     * or empty for anything else — a GitHub mirror, a plain URL, or a
     * malformed address. The key joins a task's mirror to the Jira
     * listings' per-issue data.
     */
    public static Optional<String> jiraKeyOf(String url) {
        return path(url).flatMap(path -> {
            Matcher jira = JIRA_BROWSE.matcher(path);
            return jira.matches() ? Optional.of(jira.group(1)) : Optional.empty();
        });
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

    private static void appendDependencies(StringBuilder out, List<Dependency> dependencies) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder lines = new StringBuilder();
        for (Dependency dependency : dependencies) {
            String address = dependency.address().strip();
            if (address.isEmpty() || !seen.add(normalize(address))) {
                continue;
            }
            lines.append("- Depends on [").append(linkText(address)).append("](")
                    .append(escapeDestination(address)).append(") (")
                    .append(escapeMarkdown(dependency.title())).append(")\n");
        }
        if (lines.length() > 0) {
            out.append("## Dependencies\n\n").append(lines).append('\n');
        }
    }

    /** {@code #12} for a GitHub issue address; anything else is escaped like a title. */
    private static String linkText(String address) {
        String label = label(address);
        return label.matches("#\\d+") ? label : escapeMarkdown(label);
    }

    /** Text safe inside a markdown list line: newlines become spaces, markup characters are backslash-escaped. */
    private static String escapeMarkdown(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        StringBuilder out = new StringBuilder(flat.length() + 8);
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\\', '`', '*', '_', '[', ']', '(', ')', '#', '|', '~' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** A link destination that cannot end the link early or open a tag. */
    private static String escapeDestination(String address) {
        StringBuilder out = new StringBuilder(address.length() + 8);
        for (char c : address.toCharArray()) {
            switch (c) {
                case ' ' -> out.append("%20");
                case '(' -> out.append("%28");
                case ')' -> out.append("%29");
                case '<' -> out.append("%3C");
                case '>' -> out.append("%3E");
                case '[' -> out.append("%5B");
                case ']' -> out.append("%5D");
                case '\n', '\r', '\t' -> out.append("%20");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** A Jira ticket's site identity and key, or why the address cannot be linked. */
    public sealed interface JiraAddress {
        /**
         * @param site the normalized {@code scheme://host[:port]} plus the preserved context path, no trailing slash
         * @param key  the issue key
         */
        record Usable(String site, String key) implements JiraAddress {
        }

        /** The address looks like a Jira browse page but cannot be a safe link endpoint. */
        record Unusable(String reason) implements JiraAddress {
        }

        /** Not a Jira browse address at all. */
        record Unrecognized() implements JiraAddress {
        }
    }

    /**
     * Splits a Jira browse address at its terminal {@code /browse/<KEY>}:
     * the site (scheme and host lowercased, default port dropped, context
     * path kept verbatim, case significant, trailing slashes dropped) and
     * the key. An address with userinfo, a query, a fragment, a scheme other
     * than http/https or no host is {@link JiraAddress.Unusable}; its
     * recorded mirror stays valid, it just cannot be linked.
     */
    public static JiraAddress jiraAddress(String url) {
        if (url == null) {
            return new JiraAddress.Unrecognized();
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return new JiraAddress.Unrecognized();
        }
        String rawPath = uri.getRawPath();
        Matcher matcher = rawPath == null ? null : JIRA_TERMINAL_BROWSE.matcher(rawPath);
        if (matcher == null || !matcher.matches()) {
            return new JiraAddress.Unrecognized();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new JiraAddress.Unusable("unusable for linking: scheme is not http or https");
        }
        if (uri.getRawUserInfo() != null) {
            return new JiraAddress.Unusable("unusable for linking: the address carries credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return new JiraAddress.Unusable("unusable for linking: the address has a query or fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new JiraAddress.Unusable("unusable for linking: the address has no host");
        }
        int port = uri.getPort();
        if (port == (scheme.equals("https") ? 443 : 80)) {
            port = -1;
        }
        String context = matcher.group(1);
        while (context.endsWith("/")) {
            context = context.substring(0, context.length() - 1);
        }
        String site = scheme + "://" + host.toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port) + context;
        return new JiraAddress.Usable(site, matcher.group(2));
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
