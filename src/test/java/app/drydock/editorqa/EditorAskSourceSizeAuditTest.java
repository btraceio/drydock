package app.drydock.editorqa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INV-16: reviewable source sizes; no inline fully-qualified names in the new editorqa sources. */
class EditorAskSourceSizeAuditTest {

    private static final int MAX_LINES = 1_000;
    private static final long MAX_BYTES = 50 * 1024;
    private static final Pattern INLINE_FQCN =
            Pattern.compile("(?<![\\w.\"])(?:java|javax|app\\.drydock)\\.[a-z][\\w.]*\\.[A-Z]\\w*");

    private static final List<Path> NEW_DIRS = List.of(
            Path.of("src/main/java/app/drydock/editorqa"),
            Path.of("src/platform/java/app/drydock/intellij/editorqa"));
    private static final List<Path> NEW_FILES = List.of(
            Path.of("src/main/java/app/drydock/mcp/EditorAskAnswerDelegate.java"),
            Path.of("src/main/java/app/drydock/agent/spi/EditorAskTurnObserver.java"),
            Path.of("src/main/java/app/drydock/agent/providers/claude/internal/ClaudeEditorAskObserver.java"),
            Path.of("src/main/java/app/drydock/agent/providers/codex/internal/CodexEditorAskRolloutReader.java"),
            Path.of("src/main/java/app/drydock/agent/providers/pi/internal/PiEditorAskExtensionSource.java"),
            Path.of("src/main/java/app/drydock/agent/providers/pi/internal/PiEditorAskReceipts.java"),
            Path.of("src/platform/java/app/drydock/intellij/agent/MarkedExecutionInputPolicy.java"),
            Path.of("src/platform/java/app/drydock/intellij/settings/EditorAskSettings.java"));

    private static List<Path> newSources() throws IOException {
        List<Path> all = new ArrayList<>(NEW_FILES);
        for (Path dir : NEW_DIRS) {
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(all::add);
            }
        }
        return all;
    }

    private static long lines(Path p) throws IOException {
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.count();
        }
    }

    @Test
    void everyNewSourceStaysUnderTheReviewabilityBudget() throws IOException {
        List<Path> sources = newSources();
        assertTrue(sources.size() > 20, "the audit sees the feature: " + sources.size());
        for (Path p : sources) {
            assertTrue(lines(p) < MAX_LINES, p + " has " + lines(p) + " lines");
            assertTrue(Files.size(p) < MAX_BYTES, p + " has " + Files.size(p) + " bytes");
        }
    }

    @Test
    void theOversizedHostsDoNotGrow() throws IOException {
        long host = lines(Path.of("src/platform/java/app/drydock/intellij/session/DrydockSessionService.java"));
        assertTrue(host <= 3_260, "DrydockSessionService has " + host + " lines");
        long hostTest = lines(Path.of("src/test/java/app/drydock/intellij/session/DrydockSessionServiceTest.java"));
        assertTrue(hostTest <= 5_068, "DrydockSessionServiceTest has " + hostTest + " lines");
    }

    @Test
    void routerGrowthIsConfinedToDelegation() throws IOException {
        Path router = Path.of("src/main/java/app/drydock/mcp/McpToolRouter.java");
        String text = Files.readString(router, StandardCharsets.UTF_8);
        long lines = lines(router);
        assertTrue(lines <= 2_181 + 60, "McpToolRouter grew to " + lines + " lines");
        assertEquals(1, count(text, "EditorAskAnswerDelegate.invoke("), "one delegation call, no inline Q&A subsystem");
    }

    @Test
    void newEditorQaSourcesHaveNoInlineQualifiedNames() throws IOException {
        for (Path p : newSources()) {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.stripLeading();
                if (t.startsWith("import ") || t.startsWith("package ") || t.startsWith("*") || t.startsWith("//")
                        || t.startsWith("/*")) {
                    continue;
                }
                assertTrue(!INLINE_FQCN.matcher(line).find(), p + " has an inline qualified name: " + line);
            }
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
