package app.drydock.claude;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers resumable Claude conversations for a repository by scanning
 * claude's own on-disk transcript store, {@code
 * ~/.claude/projects/<encoded-cwd>/<session-uuid>.jsonl} (the same store a
 * bare {@code claude --resume} picker reads). Read-only: never writes or
 * deletes anything under {@code ~/.claude}.
 *
 * <p>Directory encoding: claude encodes the working directory into the
 * project directory name by replacing every non-alphanumeric character
 * with {@code -} (verified against real stores, e.g. {@code
 * /private/tmp/drydock-visual-repo -> -private-tmp-drydock-visual-repo}).</p>
 *
 * <p>Per transcript, the display title prefers claude's own rolling {@code
 * "type":"summary"} entry (the latest one) and falls back to the first user
 * message's text. Only cheap string probes decide which lines get fully
 * parsed; transcripts can be many MB and this may be called for every
 * repository at once (always off the FX thread).</p>
 */
public final class ConversationCatalog {

    private static final Logger LOG = System.getLogger(ConversationCatalog.class.getName());

    private static final int TITLE_MAX_CHARS = 80;

    /** One resumable conversation: exactly what the resume picker row shows. */
    public record Conversation(String sessionId, String title, int messageCount, Instant lastModified) {
    }

    /** A parsed transcript, keyed on the (size, mtime) it was scanned at. */
    private record CachedScan(long size, FileTime lastModified, Optional<Conversation> conversation) {
    }

    private final Path projectsRoot;

    /**
     * Transcripts are multi-MB and {@link #listConversations} may run for
     * every repository at once; re-scan a file only when its size or mtime
     * changed since the last scan.
     */
    private final Map<Path, CachedScan> scanCache = new ConcurrentHashMap<>();

    public ConversationCatalog() {
        this(Path.of(System.getProperty("user.home"), ".claude", "projects"));
    }

    /** For tests: an explicit projects root instead of {@code ~/.claude/projects}. */
    public ConversationCatalog(Path projectsRoot) {
        this.projectsRoot = projectsRoot;
    }

    /** The claude project directory that transcripts for {@code workingDirectory} live in. */
    public Path projectDirFor(Path workingDirectory) {
        String encoded = workingDirectory.toAbsolutePath().normalize().toString()
                .replaceAll("[^A-Za-z0-9]", "-");
        return projectsRoot.resolve(encoded);
    }

    /**
     * Lists every resumable conversation for {@code workingDirectory},
     * newest first. Conversations with no user message at all (nothing for
     * claude to resume) are skipped. Blocking -- call on a background
     * executor, never the FX thread.
     */
    public List<Conversation> listConversations(Path workingDirectory) {
        Path projectDir = projectDirFor(workingDirectory);
        if (!Files.isDirectory(projectDir)) {
            return List.of();
        }
        List<Conversation> conversations = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectDir, "*.jsonl")) {
            for (Path transcript : stream) {
                readConversation(transcript).ifPresent(conversations::add);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not scan claude project dir " + projectDir, e);
            return List.of();
        }
        conversations.sort(Comparator.comparing(Conversation::lastModified).reversed());
        return List.copyOf(conversations);
    }

    private Optional<Conversation> readConversation(Path transcript) {
        long size;
        FileTime lastModified;
        try {
            size = Files.size(transcript);
            lastModified = Files.getLastModifiedTime(transcript);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Skipping unreadable transcript " + transcript, e);
            return Optional.empty();
        }
        CachedScan cached = scanCache.get(transcript);
        if (cached != null && cached.size() == size && cached.lastModified().equals(lastModified)) {
            return cached.conversation();
        }
        Optional<Conversation> scanned = scanTranscript(transcript, lastModified.toInstant());
        scanCache.put(transcript, new CachedScan(size, lastModified, scanned));
        return scanned;
    }

    private Optional<Conversation> scanTranscript(Path transcript, Instant lastModified) {
        String fileName = transcript.getFileName().toString();
        String sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());

        int messageCount = 0;
        String summaryTitle = null;
        String firstUserText = null;

        try (var lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                // Cheap probes first; full JSON parsing only for the lines we need.
                boolean isUser = line.contains("\"type\":\"user\"");
                boolean isAssistant = line.contains("\"type\":\"assistant\"");
                boolean isSidechain = line.contains("\"isSidechain\":true");
                // Sidechain (subagent) traffic is not part of the conversation
                // the picker resumes; exclude it from the count like the
                // title path already does.
                if ((isUser || isAssistant) && !isSidechain) {
                    messageCount++;
                }
                if (firstUserText == null && isUser && !isSidechain) {
                    firstUserText = extractUserText(line);
                }
                if (line.contains("\"type\":\"summary\"")) {
                    String summary = extractSummary(line);
                    if (summary != null) {
                        summaryTitle = summary; // keep the LAST summary (claude appends fresher ones)
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.DEBUG, "Skipping unreadable transcript " + transcript, e);
            return Optional.empty();
        }

        if (messageCount == 0) {
            return Optional.empty();
        }

        String title = summaryTitle != null ? summaryTitle
                : firstUserText != null ? firstUserText
                : "Untitled conversation";
        return Optional.of(new Conversation(sessionId, truncate(title), messageCount, lastModified));
    }

    private static String extractSummary(String line) {
        try {
            if (JsonParser.parse(line) instanceof JsonObject obj
                    && obj.get("type") instanceof JsonString type && "summary".equals(type.value())
                    && obj.get("summary") instanceof JsonString summary) {
                return clean(summary.value());
            }
        } catch (RuntimeException e) {
            // Malformed line; not fatal for the whole transcript.
        }
        return null;
    }

    private static String extractUserText(String line) {
        try {
            if (!(JsonParser.parse(line) instanceof JsonObject obj)
                    || !(obj.get("message") instanceof JsonObject message)) {
                return null;
            }
            JsonValue content = message.get("content");
            if (content instanceof JsonString text) {
                return clean(text.value());
            }
            if (content instanceof JsonArray parts) {
                for (JsonValue part : parts.elements()) {
                    if (part instanceof JsonObject partObj
                            && partObj.get("type") instanceof JsonString partType
                            && "text".equals(partType.value())
                            && partObj.get("text") instanceof JsonString text) {
                        return clean(text.value());
                    }
                }
            }
        } catch (RuntimeException e) {
            // Malformed line; fall through.
        }
        return null;
    }

    private static String clean(String text) {
        String collapsed = text.replaceAll("\\s+", " ").strip();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static String truncate(String title) {
        return title.length() <= TITLE_MAX_CHARS ? title : title.substring(0, TITLE_MAX_CHARS - 1) + "…";
    }
}
