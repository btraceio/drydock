package app.drydock.mcp;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T42b: the committed port of the T42a handshake smoke, now as automated
 * evidence (D17/P7a). The handshake {@code instructions} are the only thing
 * that makes an agent look at a tool at all -- a description is read once
 * the hunt has started -- so their gating is the acceptance surface: the
 * task paragraph appears iff the router serves ANY task tool (a partial
 * allowlist of one task name is enough), rename and handoff paragraphs are
 * retained independently of it, and every router serving no task tool keeps
 * the pre-task text byte for byte, so a task-less session learns nothing
 * about tools it cannot call.
 *
 * <p>No mocking framework, per repo convention: real {@link McpToolRouter}s
 * over {@link FakeMcpSessionContext} and the real store-backed {@link
 * McpToolRouterTaskTest.StoreContext} delegate. The handshake gate reads
 * only the allowlist, so the delegate's store is never invoked here -- it
 * is real so the router's constructor validates real names, which is the
 * other half of what these tests pin.</p>
 */
class McpServerTaskInstructionsTest {

    /**
     * Marker substrings unique to the task paragraph. Absence checks use
     * these rather than the whole paragraph, so a leak of ANY part of the
     * paragraph -- not just its first sentence -- is caught.
     */
    private static final String[] TASK_MARKERS = {
            "Drydock also gives this session task tools",
            "done report on such a task stores review",
            "task_create instead of widening this one"};

    private static final String RENAME_MARKER = "call session_rename with a short title";
    private static final String HANDOFF_MARKER = "session_handoff current as you work";

    @TempDir
    Path taskRoot;
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private McpToolRouterTaskTest.StoreContext tasks;
    private TaskTools delegate;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        registry = new McpSessionRegistry();
        tasks = new McpToolRouterTaskTest.StoreContext(taskRoot, RepositoryId.newId());
        delegate = new TaskTools(tasks);
    }

    @AfterEach
    void tearDown() {
        tasks.store.close();
    }

    // 1. The full combination: all three paragraphs, task first.
    @Test
    void taskRenameAndHandoffTogetherCarryAllThreeParagraphsInOrder() {
        McpToolRouter router = taskRouter(tools(McpToolRouter.TASK_TOOLS,
                "session_rename", "session_handoff"));

        String instructions = McpServer.instructionsFor(router).orElse("");
        assertEquals(McpServer.TASK_INSTRUCTIONS + "\n\n" + McpServer.RENAME_INSTRUCTIONS
                + "\n\n" + McpServer.HANDOFF_INSTRUCTIONS, instructions,
                "the three paragraphs, joined by blank lines, in that order");
        // The order asserted independently of the joining, via each
        // paragraph's own marker, so a re-joined text cannot pass on
        // membership alone.
        int task = instructions.indexOf(TASK_MARKERS[0]);
        int rename = instructions.indexOf(RENAME_MARKER);
        int handoff = instructions.indexOf(HANDOFF_MARKER);
        assertTrue(task >= 0 && rename > task && handoff > rename,
                "task, then rename, then handoff, got: " + instructions);
    }

    // 2. Each subset alone: exactly its own paragraph, nothing else.
    @Test
    void eachSubsetAloneCarriesExactlyItsOwnParagraph() {
        assertEquals(Optional.of(McpServer.TASK_INSTRUCTIONS),
                McpServer.instructionsFor(taskRouter(McpToolRouter.TASK_TOOLS)),
                "a task-only router teaches only the task paragraph");
        // Rename and handoff stay independent of the task paragraph: a
        // delegate-bearing router whose allowlist carries no task tool still
        // serves them, through the unchanged legacy branch.
        assertEquals(Optional.of(McpServer.RENAME_INSTRUCTIONS),
                McpServer.instructionsFor(taskRouter(Set.of("session_rename"))),
                "rename is retained without any task tool");
        assertEquals(Optional.of(McpServer.HANDOFF_INSTRUCTIONS),
                McpServer.instructionsFor(taskRouter(Set.of("session_handoff"))),
                "handoff is retained without any task tool");
    }

    // 3. None-combination: byte-identical to the legacy handshake.
    @Test
    void everyNonTaskCombinationIsByteIdenticalToTheLegacyHandshake() {
        // The legacy text, captured from a delegate-less router -- the one
        // constructor shape that existed before the task tools, whose
        // instructions are the pre-change handshake this matrix must
        // preserve.
        List<Set<String>> nonTaskCombos = List.of(
                Set.of("repos_list"),
                Set.of("session_rename"),
                Set.of("session_handoff"),
                Set.of("session_rename", "session_handoff"),
                McpToolRouter.REVIEW_TOOLS_AND_RENAME);
        for (Set<String> allowed : nonTaskCombos) {
            Optional<String> legacy = McpServer.instructionsFor(legacyRouter(allowed));
            assertEquals(legacy, McpServer.instructionsFor(taskRouter(allowed)),
                    "a task-enabled router serving no task tool must keep the"
                            + " pre-task handshake byte for byte: " + allowed);
        }
        assertEquals(Optional.empty(),
                McpServer.instructionsFor(taskRouter(Set.of("repos_list"))),
                "serving none of the three sends no instructions at all");
    }

    // 4. Partial allowlist: any ONE served task tool gates the paragraph in.
    @Test
    void aTaskGetOnlyAllowlistStillGatesTheTaskParagraphIn() {
        McpToolRouter taskGetOnly = taskRouter(Set.of("task_get"));
        assertEquals(Set.of("task_get"), advertisedNames(taskGetOnly),
                "the allowlist really is partial: only task_get is served");
        assertEquals(Optional.of(McpServer.TASK_INSTRUCTIONS),
                McpServer.instructionsFor(taskGetOnly),
                "any served task tool, task_get alone included, teaches the"
                        + " task paragraph");
    }

    // 5. No task instruction when no task tool is served, in every non-task
    //    combination -- with the delegate present, where the names COULD have
    //    leaked in, and on the delegate-less full router.
    @Test
    void noTaskParagraphWheneverNoTaskToolIsServed() {
        List<Set<String>> nonTaskCombos = List.of(
                Set.of("repos_list"),
                Set.of("session_rename"),
                Set.of("session_handoff"),
                Set.of("session_rename", "session_handoff"),
                McpToolRouter.REVIEW_TOOLS,
                McpToolRouter.REVIEW_TOOLS_AND_RENAME);
        for (Set<String> allowed : nonTaskCombos) {
            assertNoTaskParagraph(
                    McpServer.instructionsFor(taskRouter(allowed)).orElse(""),
                    "the router serving " + allowed);
        }
        assertNoTaskParagraph(
                McpServer.instructionsFor(new McpToolRouter(context, registry)).orElse(""),
                "the delegate-less full router");
    }

    // 6. Described names exist: every task tool name the paragraph and the
    //    shipped skill reference is one of TaskTools' served tools, and the
    //    skill teaches every served tool -- no invented names either way.
    @Test
    void everyTaskToolNameTheParagraphAndSkillReferenceIsAServedTool() {
        Set<String> served = new TaskTools(tasks).toolDescriptors().stream()
                .map(d -> str(d, "name")).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(McpToolRouter.TASK_TOOLS, served,
                "the delegate serves exactly the four task tools");
        for (String referenced : toolNamesIn(McpServer.TASK_INSTRUCTIONS)) {
            assertTrue(served.contains(referenced),
                    "the paragraph names a tool the delegate does not serve: " + referenced);
        }
        String skill = skillText();
        for (String referenced : toolNamesIn(skill)) {
            assertTrue(served.contains(referenced),
                    "the skill names a tool the delegate does not serve: " + referenced);
        }
        for (String name : served) {
            assertTrue(skill.contains(name), "the skill never teaches " + name);
        }
    }

    // 7. The shipped skill teaches the three claims an agent must not have
    //    to fetch a skill to learn: default-human validation, prose-is-not-
    //    policy, and the review pause.
    @Test
    void theShippedSkillTeachesTheReviewPauseTheDefaultHumanRuleAndTheProseRule() {
        // Whitespace flattened so a claim surviving an unrelated re-wrap of
        // the skill still reads as present, and a rewording still fails.
        String flat = skillText().replaceAll("\\s+", " ");
        assertTrue(flat.contains("done_by always defaults to human -- prose cannot grant"
                + " agent-close"), "the default-human statement");
        assertTrue(flat.contains("Declaring done_by in prose does not change policy"),
                "the no-prose-policy claim");
        assertTrue(flat.contains("your task_update with status done stores review, not done"),
                "the review pause");
        assertTrue(flat.contains("and only that human"), "only the human confirms done");
    }

    // ---- helpers ----------------------------------------------------------

    private McpToolRouter taskRouter(Set<String> allowed) {
        return new McpToolRouter(context, registry, allowed, delegate);
    }

    private McpToolRouter legacyRouter(Set<String> allowed) {
        return new McpToolRouter(context, registry, allowed);
    }

    private static Set<String> tools(Set<String> base, String... extra) {
        Set<String> out = new LinkedHashSet<>(base);
        out.addAll(List.of(extra));
        return out;
    }

    private static Set<String> advertisedNames(McpToolRouter router) {
        return router.toolDescriptors().stream().map(d -> str(d, "name"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void assertNoTaskParagraph(String text, String what) {
        for (String marker : TASK_MARKERS) {
            assertFalse(text.contains(marker),
                    "task paragraph leaked into " + what + " via \"" + marker + "\"");
        }
    }

    /** Every distinct {@code task_*} name a text mentions, as written. */
    private static Set<String> toolNamesIn(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("task_[a-z]+").matcher(text);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    /**
     * The shipped skill, read from the test classpath -- the same bytes a
     * packaged plugin serves, not a filesystem path into src/, so a stale
     * or mislocated copy cannot make these claims pass.
     */
    private static String skillText() {
        try (InputStream in = McpServerTaskInstructionsTest.class
                .getResourceAsStream("/tasks/SKILL.md")) {
            assertNotNull(in, "the shipped skill is not on the classpath at /tasks/SKILL.md");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("reading the shipped skill resource", e);
        }
    }
}
