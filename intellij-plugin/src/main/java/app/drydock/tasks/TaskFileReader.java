package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded reader for task files (P1): one YAML front-matter mapping between
 * {@code ---} fences plus a free-markdown body, parsed with snakeyaml's
 * bounded node composition ({@code Yaml.compose}) — no arbitrary Java
 * object construction. The raw envelope (fences, whole-file bound) comes
 * from {@link TaskFileEnvelope}. This class refuses visibly (checked
 * {@link TaskFormatException}) instead of guessing: non-mapping or
 * multi-document front matter, duplicate keys, aliases, unbounded
 * nesting/collections, and invalid values for known fields ({@code title},
 * {@code status}, {@code done_by}, {@code created}, {@code after},
 * {@code sessions}). Unknown keys, comments and the body are never
 * interpreted — they survive byte for byte in
 * {@link TaskDocument#rawFrontMatter()} and {@link TaskDocument#rawBody()}.
 *
 * <p>This class does no disk I/O: the source text, the task's {@link TaskId}
 * and repository identity come from the caller, and a missing {@code created}
 * uses the caller-supplied filesystem creation time (null leaves it empty).
 * Spans are UTF-16 offsets into the original source — snakeyaml marks are
 * <em>code point</em> indexes, so astral characters are translated, and the
 * astral tests pin that behavior.</p>
 */
public final class TaskFileReader {

    /** Whole-file code-point bound; source of truth
     * {@link TaskFileEnvelope#MAX_FILE_CODE_POINTS}, enforced by the envelope split. */
    public static final int MAX_FILE_CODE_POINTS = TaskFileEnvelope.MAX_FILE_CODE_POINTS;
    /** Bound for every YAML collection in the front matter, including the top-level mapping. */
    public static final int MAX_COLLECTION_SIZE = 1_000;
    /** Bound for YAML collection nesting; snakeyaml refuses deeper trees before composing them. */
    public static final int MAX_NESTING_DEPTH = 32;

    /** A half-open range of UTF-16 offsets into the exact source text that was parsed. */
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid span: [" + start + ", " + end + ")");
            }
        }

        public int length() {
            return end - start;
        }
    }

    /**
     * One parsed task file: the immutable {@link TaskDocument}, plus the
     * spans T03 needs for a lossless patch — {@code knownKeySpans} maps each
     * <em>present</em> known key to the span of its whole {@code key: value}
     * entry (key, colon and value, never the value's trailing comment), and
     * {@code frontMatter} is the span of the raw front matter between the
     * fences. Missing keys are simply absent from the map.
     */
    public record Parsed(TaskDocument document, Map<String, Span> knownKeySpans, Span frontMatter) {
        public Parsed {
            // Defensive copy, order-preserving: no caller can mutate the map.
            knownKeySpans = Collections.unmodifiableMap(new LinkedHashMap<>(knownKeySpans));
        }
    }

    private TaskFileReader() {
    }

    public static Parsed parse(TaskId id, RepositoryId repository, String source, Instant createdFallback)
            throws TaskFormatException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repository, "repository");
        // Splits the fences and enforces the whole-file bound (nulls: same message).
        TaskFileEnvelope envelope = TaskFileEnvelope.parse(source);
        Node root = compose(envelope.frontMatter());
        if (root == null) {
            throw new TaskFormatException("Front matter is empty; 'title' is required (P1)");
        }
        if (!(root instanceof MappingNode mapping)) {
            throw new TaskFormatException("Front matter must be a YAML mapping, not " + root.getTag());
        }
        checkTree(root, new IdentityHashMap<>());
        Fields fields = extract(source, envelope, mapping);
        if (fields.title == null) {
            throw new TaskFormatException("Front-matter field 'title' is required (P1)");
        }
        if (fields.title.isBlank()) {
            throw new TaskFormatException("Front-matter field 'title' must not be blank");
        }
        Optional<Instant> created = fields.created != null ? Optional.of(fields.created)
                : Optional.ofNullable(createdFallback);
        TaskDocument document = new TaskDocument(id, repository, fields.title, fields.status, fields.doneBy,
                created, fields.after, fields.sessions, envelope.frontMatter(), envelope.body());
        return new Parsed(document, fields.spans,
                new Span(envelope.frontStart(), envelope.frontStart() + envelope.frontMatter().length()));
    }

    /** Bounded composition of exactly one document: no object construction, refused on a second. */
    private static Node compose(String frontMatter) throws TaskFormatException {
        LoaderOptions options = new LoaderOptions();
        options.setNestingDepthLimit(MAX_NESTING_DEPTH);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_FILE_CODE_POINTS);
        options.setAllowDuplicateKeys(false);
        try (Reader reader = new StringReader(frontMatter)) {
            return new Yaml(options).compose(reader);
        } catch (YAMLException | IOException | StackOverflowError e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new TaskFormatException("Malformed front matter: " + detail, e);
        }
    }

    /**
     * Refuses aliases (a node reused is necessarily an alias — snakeyaml has
     * no alias node class) and oversized collections. Nesting was already
     * bounded by the composer, so this walk cannot recurse deeply.
     */
    private static void checkTree(Node node, IdentityHashMap<Node, Boolean> seen) throws TaskFormatException {
        if (seen.put(node, Boolean.TRUE) != null) {
            throw new TaskFormatException("YAML aliases are not supported in task front matter");
        }
        if (node instanceof SequenceNode sequence) {
            List<Node> children = sequence.getValue();
            if (children.size() > MAX_COLLECTION_SIZE) {
                throw new TaskFormatException("Front-matter list exceeds " + MAX_COLLECTION_SIZE + " entries");
            }
            for (Node child : children) {
                checkTree(child, seen);
            }
        } else if (node instanceof MappingNode mapping) {
            List<NodeTuple> entries = mapping.getValue();
            if (entries.size() > MAX_COLLECTION_SIZE) {
                throw new TaskFormatException("Front-matter mapping exceeds " + MAX_COLLECTION_SIZE + " keys");
            }
            for (NodeTuple tuple : entries) {
                checkTree(tuple.getKeyNode(), seen);
                checkTree(tuple.getValueNode(), seen);
            }
        }
    }

    private static final class Fields {
        String title;
        TaskStatus status;
        DoneBy doneBy;
        Instant created;
        List<String> after;
        List<String> sessions;
        final Map<String, Span> spans = new LinkedHashMap<>();
    }

    private static Fields extract(String source, TaskFileEnvelope envelope, MappingNode mapping)
            throws TaskFormatException {
        Fields fields = new Fields();
        HashSet<String> seenKeys = new HashSet<>();
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                throw new TaskFormatException(
                        "Front-matter keys must be plain scalars, not " + tuple.getKeyNode().getTag());
            }
            if (!seenKeys.add(key.getValue())) {
                throw new TaskFormatException("Duplicate front-matter key: '" + key.getValue() + "'");
            }
            Node value = tuple.getValueNode();
            switch (key.getValue()) {
                case "title" -> {
                    fields.title = stringValue(value, "title");
                    fields.spans.put("title", entrySpan(source, envelope, key, value));
                }
                case "status" -> {
                    fields.status = storedValue(value, "status", TaskStatus::fromStored);
                    fields.spans.put("status", entrySpan(source, envelope, key, value));
                }
                case "done_by" -> {
                    fields.doneBy = storedValue(value, "done_by", DoneBy::fromStored);
                    fields.spans.put("done_by", entrySpan(source, envelope, key, value));
                }
                case "created" -> {
                    fields.created = instantValue(value);
                    fields.spans.put("created", entrySpan(source, envelope, key, value));
                }
                case "after" -> {
                    fields.after = stringList(value, "after");
                    fields.spans.put("after", entrySpan(source, envelope, key, value));
                }
                case "sessions" -> {
                    fields.sessions = stringList(value, "sessions");
                    fields.spans.put("sessions", entrySpan(source, envelope, key, value));
                }
                default -> {
                    // Unknown key: preserved only as raw text, never interpreted.
                }
            }
        }
        return fields;
    }

    private interface StoredParser<T> {
        T parse(String text);
    }

    private static <T> T storedValue(Node value, String field, StoredParser<T> parser)
            throws TaskFormatException {
        String text = stringValue(value, field);
        try {
            return parser.parse(text);
        } catch (IllegalArgumentException e) {
            throw new TaskFormatException("Invalid front-matter field '" + field + "': " + e.getMessage(), e);
        }
    }

    private static String stringValue(Node value, String field) throws TaskFormatException {
        if (value instanceof ScalarNode scalar && Tag.STR.equals(scalar.getTag())) {
            return scalar.getValue();
        }
        throw new TaskFormatException("Front-matter field '" + field + "' must be a string, got "
                + (value instanceof ScalarNode ? ((ScalarNode) value).getTag().getValue() : value.getTag().getValue()));
    }

    private static Instant instantValue(Node value) throws TaskFormatException {
        if (value instanceof ScalarNode scalar
                && (Tag.STR.equals(scalar.getTag()) || Tag.TIMESTAMP.equals(scalar.getTag()))) {
            try {
                return Instant.parse(scalar.getValue());
            } catch (DateTimeParseException e) {
                throw new TaskFormatException(
                        "Front-matter field 'created' is not an ISO-8601 instant: '" + scalar.getValue() + "'", e);
            }
        }
        throw new TaskFormatException(
                "Front-matter field 'created' must be an ISO-8601 timestamp string");
    }

    private static List<String> stringList(Node value, String field) throws TaskFormatException {
        if (!(value instanceof SequenceNode sequence)) {
            throw new TaskFormatException("Front-matter field '" + field + "' must be a list");
        }
        ArrayList<String> entries = new ArrayList<>(sequence.getValue().size());
        for (Node item : sequence.getValue()) {
            entries.add(stringValue(item, field + " entry"));
        }
        return List.copyOf(entries);
    }

    /**
     * The span of a whole {@code key: value} entry: from the key's first
     * character to the end of the value, without trailing line breaks, so a
     * block list followed by a comment line is not over-selected. For a
     * block sequence the value's end is its last item, not the parser's
     * end-of-collection mark, for the same reason.
     */
    private static Span entrySpan(String source, TaskFileEnvelope envelope, ScalarNode key, Node value) {
        String front = envelope.frontMatter();
        int start = utf16Offset(front, key.getStartMark().getIndex());
        int end;
        if (value instanceof SequenceNode sequence) {
            end = sequence.getFlowStyle() == DumperOptions.FlowStyle.BLOCK && !sequence.getValue().isEmpty()
                    ? utf16Offset(front, sequence.getValue().get(sequence.getValue().size() - 1).getEndMark().getIndex())
                    : utf16Offset(front, value.getEndMark().getIndex());
        } else {
            end = utf16Offset(front, value.getEndMark().getIndex());
        }
        while (end > start && "\t \r\n".indexOf(source.charAt(envelope.frontStart() + end - 1)) >= 0) {
            end--;
        }
        return new Span(envelope.frontStart() + start, envelope.frontStart() + end);
    }

    /** Snakeyaml mark indexes count code points; spans need Java UTF-16 offsets. */
    private static int utf16Offset(String text, int codePointIndex) {
        int offset = 0;
        for (int counted = 0; counted < codePointIndex && offset < text.length(); counted++) {
            offset += Character.charCount(text.codePointAt(offset));
        }
        return Math.min(offset, text.length());
    }
}
