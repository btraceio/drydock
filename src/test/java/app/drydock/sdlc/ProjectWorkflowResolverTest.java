package app.drydock.sdlc;

import app.drydock.domain.RepositoryId;
import app.drydock.projects.SharedProject;
import app.drydock.sdlc.WorkflowResolution.Invalid;
import app.drydock.sdlc.WorkflowResolution.Missing;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowResolution.Selected;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWorkflowResolverTest {

    @TempDir
    Path tmp;

    private final ProjectWorkflowResolver resolver = new ProjectWorkflowResolver();

    private Path repo(String name, String config) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        if (config != null) {
            Files.createDirectories(root.resolve(".sdlc"));
            Files.writeString(root.resolve(".sdlc/config.json"), config);
        }
        return root;
    }

    private static SharedProject project(Path root) {
        return SharedProject.local(RepositoryId.newId(), root, "p", Instant.EPOCH);
    }

    private WorkflowResolution resolve(Path root, WorkflowTarget t) {
        return resolver.resolve(project(root), t, Cancellation.none());
    }

    private static Invalid invalid(WorkflowResolution r) {
        return assertInstanceOf(Invalid.class, r);
    }

    @Test
    void conflictingConfigsInDistinctRootsAreNeverMixed() throws IOException {
        String a = "{\"version\":1,\"newTask\":{\"prePrompt\":{\"text\":\"A\"}}}";
        String b = "{\"version\":1,\"newTask\":{\"prePrompt\":{\"text\":\"B\"}}}";
        Path ra = repo("a", a);
        Path rb = repo("b", b);
        Path none = repo("none", null);
        assertEquals("A", ((Selected) resolve(ra, WorkflowTarget.FREE_FORM_PRE_PROMPT)).workflow().promptText());
        assertEquals("B", ((Selected) resolve(rb, WorkflowTarget.FREE_FORM_PRE_PROMPT)).workflow().promptText());
        assertInstanceOf(Missing.class, resolve(none, WorkflowTarget.FREE_FORM_PRE_PROMPT));
        // a nested directory's own config is not consulted when the registered root has none
        Files.createDirectories(none.resolve("sub/.sdlc"));
        Files.writeString(none.resolve("sub/.sdlc/config.json"), a);
        assertInstanceOf(Missing.class, resolve(none, WorkflowTarget.FREE_FORM_PRE_PROMPT));
    }

    @Test
    void targetsAreIndependent() throws IOException {
        Path root = repo("r", "{\"version\":1,\"newTask\":{\"prePrompt\":{\"text\":\"pre\"}},"
                + "\"actions\":{\"issue\":{\"triage\":{\"file\":\"t/triage.md\"}},\"pullRequest\":{\"review\":{\"text\":\" \"}}}}");
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/triage.md"), "triage steps");
        assertEquals("pre", ((Selected) resolve(root, WorkflowTarget.FREE_FORM_PRE_PROMPT)).workflow().promptText());
        Selected triage = (Selected) resolve(root, WorkflowTarget.ISSUE_TRIAGE);
        assertEquals(WorkflowTarget.ISSUE_TRIAGE, triage.workflow().target());
        assertEquals("triage steps", triage.workflow().promptText());
        assertInstanceOf(Missing.class, resolve(root, WorkflowTarget.ISSUE_IMPLEMENT));
        // an invalid unrelated leaf does not block the others, but blocks its own target
        assertEquals(ProblemCategory.BLANK, invalid(resolve(root, WorkflowTarget.PULL_REQUEST_REVIEW)).problem().category());
    }

    @Test
    void skillSelectsItsDirectoryAndIncludesAndSnapshotIsStableWhileNextResolveIsFresh() throws IOException {
        Path root = repo("r", "{\"version\":1,\"actions\":{\"localChanges\":{\"review\":"
                + "{\"skill\":\"skills/rev/SKILL.md\",\"include\":[\"shared\"]}}}}");
        Files.createDirectories(root.resolve("skills/rev"));
        Files.createDirectories(root.resolve("shared"));
        Files.writeString(root.resolve("skills/rev/SKILL.md"), "v1 method");
        Files.writeString(root.resolve("skills/rev/extra.txt"), "x1");
        Files.writeString(root.resolve("shared/s.txt"), "s1");
        Selected first = (Selected) resolve(root, WorkflowTarget.LOCAL_CHANGES_REVIEW);
        SelectedWorkflow w1 = first.workflow();
        assertEquals(3, w1.bundle().fileCount());

        Files.writeString(root.resolve("skills/rev/SKILL.md"), "v2 method");
        Files.writeString(root.resolve("shared/s.txt"), "s2");
        Files.writeString(root.resolve(".sdlc/config.json"),
                "{\"version\":1,\"actions\":{\"localChanges\":{\"review\":{\"text\":\"other\"}}}}");

        assertEquals("v1 method", w1.promptText());
        assertEquals("s1", new String(((WorkflowBundle.File) w1.bundle().entries().stream()
                .filter(e -> e.path().equals("shared/s.txt")).findFirst().orElseThrow()).content()));
        Selected next = (Selected) resolve(root, WorkflowTarget.LOCAL_CHANGES_REVIEW);
        assertEquals("other", next.workflow().promptText());
        assertNotEquals(w1.digest(), next.workflow().digest());
    }

    @Test
    void configProblemsAreInvalidNotMissing() throws IOException {
        Path danglingCfg = repo("d", null);
        Files.createDirectories(danglingCfg.resolve(".sdlc"));
        Files.createSymbolicLink(danglingCfg.resolve(".sdlc/config.json"), Path.of("nowhere.json"));
        assertEquals(ProblemCategory.SOURCE_UNREADABLE, invalid(resolve(danglingCfg, WorkflowTarget.ISSUE_TRIAGE)).problem().category());

        Path outside = repo("out", "{\"version\":1}");
        Path escaping = repo("e", null);
        Files.createSymbolicLink(escaping.resolve(".sdlc"), outside.resolve(".sdlc"));
        assertEquals(ProblemCategory.ESCAPES_ROOT, invalid(resolve(escaping, WorkflowTarget.ISSUE_TRIAGE)).problem().category());

        Path dirCfg = repo("dc", null);
        Files.createDirectories(dirCfg.resolve(".sdlc/config.json"));
        assertEquals(ProblemCategory.SPECIAL_FILE, invalid(resolve(dirCfg, WorkflowTarget.ISSUE_TRIAGE)).problem().category());

        Path gone = tmp.resolve("gone");
        assertEquals(ProblemCategory.SOURCE_UNREADABLE, invalid(resolve(gone, WorkflowTarget.ISSUE_TRIAGE)).problem().category());

        Path bad = repo("bad", null);
        Files.createDirectories(bad.resolve(".sdlc"));
        Files.write(bad.resolve(".sdlc/config.json"), new byte[]{'{', (byte) 0xC3, (byte) 0x28});
        assertEquals(ProblemCategory.ENCODING, invalid(resolve(bad, WorkflowTarget.ISSUE_TRIAGE)).problem().category());

        Path decodeBad = repo("db", "{\"version\":2}");
        assertEquals(ProblemCategory.VERSION, invalid(resolve(decodeBad, WorkflowTarget.ISSUE_TRIAGE)).problem().category());

        Path rootSkill = repo("rs", "{\"version\":1,\"actions\":{\"issue\":{\"triage\":{\"skill\":\"SKILL.md\"}}}}");
        Files.writeString(rootSkill.resolve("SKILL.md"), "x");
        assertEquals(ProblemCategory.UNSUPPORTED_SOURCE, invalid(resolve(rootSkill, WorkflowTarget.ISSUE_TRIAGE)).problem().category());
    }

    private static String textConfig(String text) {
        return "{\"version\":1,\"newTask\":{\"prePrompt\":{\"text\":\"" + text + "\"}}}";
    }

    @Test
    void deadlineWithStalledCaptureIsLimitExceededAndWorkerIsInterrupted() throws Exception {
        Path root = repo("r", "{\"version\":1,\"actions\":{\"issue\":{\"triage\":{\"file\":\"t.md\"}}}}");
        Files.writeString(root.resolve("t.md"), "x");
        CountDownLatch interrupted = new CountDownLatch(1);
        ProjectWorkflowResolver slow = new ProjectWorkflowResolver(150, path -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        long start = System.nanoTime();
        WorkflowResolution r = slow.resolve(project(root), WorkflowTarget.ISSUE_TRIAGE, Cancellation.none());
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, invalid(r).problem().category());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelDuringStalledCaptureIsCancelled() throws Exception {
        Path root = repo("r", "{\"version\":1,\"actions\":{\"issue\":{\"triage\":{\"file\":\"t.md\"}}}}");
        Files.writeString(root.resolve("t.md"), "x");
        Cancellation cancel = Cancellation.none();
        ProjectWorkflowResolver slow = new ProjectWorkflowResolver(30_000, path -> {
            cancel.cancel();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertEquals(ProblemCategory.CANCELLED, invalid(slow.resolve(project(root), WorkflowTarget.ISSUE_TRIAGE, cancel)).problem().category());
        assertEquals(ProblemCategory.CANCELLED, invalid(resolver.resolve(project(root), WorkflowTarget.ISSUE_TRIAGE, cancel)).problem().category());
    }

    @Test
    void configSizeExactPassesTheGateAndPlusOneIsLimitExceeded() throws IOException {
        String head = "{\"version\":1,\"x\":\"";
        int pad = WorkflowLimits.CONFIG_BYTES - head.length() - 2;
        Path exact = repo("exact", head + "p".repeat(pad) + "\"}");
        assertEquals(WorkflowLimits.CONFIG_BYTES, Files.size(exact.resolve(".sdlc/config.json")));
        // past the size gate the unknown field is a decode problem, not a limit
        assertNotEquals(ProblemCategory.LIMIT_EXCEEDED, invalid(resolve(exact, WorkflowTarget.FREE_FORM_PRE_PROMPT)).problem().category());
        Path plus = repo("plus", head + "p".repeat(pad + 1) + "\"}");
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, invalid(resolve(plus, WorkflowTarget.FREE_FORM_PRE_PROMPT)).problem().category());
    }

    @Test
    void promptSizeAndBlankChecks() throws IOException {
        Path ok = repo("ok", textConfig("a".repeat(WorkflowLimits.PROMPT_BYTES)));
        assertInstanceOf(Selected.class, resolve(ok, WorkflowTarget.FREE_FORM_PRE_PROMPT));
        Path big = repo("big", textConfig("a".repeat(WorkflowLimits.PROMPT_BYTES + 1)));
        assertEquals(ProblemCategory.LIMIT_EXCEEDED, invalid(resolve(big, WorkflowTarget.FREE_FORM_PRE_PROMPT)).problem().category());
        Path blank = repo("blank", "{\"version\":1,\"actions\":{\"issue\":{\"triage\":{\"file\":\"t.md\"}}}}");
        Files.writeString(blank.resolve("t.md"), "  \n");
        assertEquals(ProblemCategory.BLANK, invalid(resolve(blank, WorkflowTarget.ISSUE_TRIAGE)).problem().category());
    }
}
